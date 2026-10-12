/*
 * Copyright 2026 Salar Rahmanian
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.softinio.verdict4s

import scala.annotation.unused
import scala.concurrent.duration.*

import cats.data.ValidatedNec
import cats.syntax.all.*
import com.softinio.verdict4s.algebra.*
import com.softinio.verdict4s.jev.JevVendor
import com.softinio.verdict4s.openai.OpenAIVendor
import io.circe.Encoder
import io.circe.Json
import io.circe.parser

/** How to speak to one vendor's decision API.
  *
  * Jev and OpenAI Decisions answer the same three kinds of question with the
  * same kinds of answer, and differ only in how they spell them on the wire.
  * The questions, [[Ask]] and the typed answers are therefore shared, and a
  * `Vendor` is everything that is not: where to send a request, how to encode
  * it, how to read the reply, and which requests the vendor accepts.
  *
  * A value rather than a typeclass, so that the vendor can be chosen at
  * runtime, from configuration, without a type parameter on every signature.
  *
  * It is also the way to drive a vendor with no effect system at all. Render a
  * request, send it with whatever HTTP client you already have, and parse the
  * reply back into typed answers:
  *
  * ```scala
  * val vendor = Vendor.OpenAI
  * val body = vendor.renderAsk(ask, state) // Either[Verdict4sError, String]
  * val text = myHttpClient.post(
  *   vendor.evaluateUrl,
  *   vendor.headers(key),
  *   body
  * ) // your code
  * val answers = vendor.parseAsk(ask, text)
  * ```
  *
  * @group Protocol
  */
trait Vendor:

  /** A human-readable name, for logs and error messages. */
  def name: String

  /** The service's address. A `String` because core must not depend on http4s;
    * the client turns it into a `Uri`.
    */
  def defaultBaseUrl: String

  /** The model used when neither the request nor the client names one. */
  def defaultModel: Model

  /** Path segments of the evaluation endpoint, relative to the base URL. */
  def evaluatePath: List[String]

  /** Response header carrying the per-request correlation id. */
  def requestIdHeader: String

  /** A response header giving the retry delay in milliseconds, consulted before
    * the standard `Retry-After`.
    */
  def retryAfterMsHeader: Option[String] = None

  /** Whether an error the retry policy would retry is worth retrying.
    *
    * The policy decides by status alone; this lets a vendor veto a retry that
    * its error body says cannot succeed, such as OpenAI's `insufficient_quota`
    * 429, which no amount of waiting fixes.
    */
  def retryable(error: Verdict4sError.Api): Boolean = true

  /** Reject requests this vendor cannot serve, such as a feature only the other
    * vendor has, before they cost a round trip. Every problem is reported at
    * once.
    */
  def validate(
      @unused request: EvaluationRequest
  ): ValidatedNec[Verdict4sError.Validation, Unit] = ().validNec

  /** The request body. Does not validate; see [[validate]]. */
  def encode(request: EvaluationRequest): Json

  /** Read a successful response body. */
  def decode(body: Json): Either[Verdict4sError, Evaluation]

  // -- Driving the vendor without an effect system -------------------------

  /** Where to POST an evaluation, using the default base URL. */
  final def evaluateUrl: String =
    s"$defaultBaseUrl/${evaluatePath.mkString("/")}"

  /** The `Authorization` header value for a key. */
  final def authorization(apiKey: ApiKey): String = s"Bearer ${apiKey.bearer}"

  /** The headers every request needs, as plain pairs. */
  final def headers(apiKey: ApiKey): List[(String, String)] =
    List(
      "Authorization" -> authorization(apiKey),
      "Content-Type" -> "application/json"
    )

  /** Validate and encode in one step. */
  final def prepare(request: EvaluationRequest): Either[Verdict4sError, Json] =
    validate(request).toEither
      .leftMap(Verdict4sError.Invalid(_))
      .map(_ => encode(request))

  /** Render a request as the JSON to send. Does not validate; see [[renderAsk]]
    * or [[prepare]].
    */
  final def render(request: EvaluationRequest): String =
    encode(request).noSpaces

  /** Render a request indented, for logs and documentation. */
  final def renderPretty(request: EvaluationRequest): String =
    encode(request).spaces2

  /** Build, validate and render in one step, reporting every problem at once.
    *
    * ```scala
    * Vendor.Jev.renderAsk(Ask(q1, q2), state)
    * ```
    */
  final def renderAsk[Q <: Tuple, S: Encoder](
      ask: Ask[Q],
      state: S,
      model: Model = defaultModel
  ): Either[Verdict4sError, String] =
    prepareAsk(ask, state, model).map(_.noSpaces)

  /** [[renderAsk]] before rendering: the validated body as JSON. */
  private[verdict4s] final def prepareAsk[Q <: Tuple, S: Encoder](
      ask: Ask[Q],
      state: S,
      model: Model
  ): Either[Verdict4sError, Json] =
    ask
      .request(state, model)
      .toEither
      .leftMap(Verdict4sError.Invalid(_))
      .flatMap(prepare)

  /** Parse a successful response body. */
  final def parse(body: String): Either[Verdict4sError, Evaluation] =
    Vendor.parseWith(body)(decode)

  /** Parse a response and project it through the questions that produced it. */
  final def parseAsk[Q <: Tuple](
      ask: Ask[Q],
      body: String
  ): Either[Verdict4sError, Ask.Answers[Q]] =
    parse(body).flatMap(ask.answers)

  /** Turn an unsuccessful response into a typed error.
    *
    * @param status
    *   the HTTP status code
    * @param body
    *   the raw body, which need not be JSON
    * @param headers
    *   the response headers, for the request id and the retry delay
    */
  final def parseError(
      status: Int,
      body: String,
      headers: Map[String, String] = Map.empty
  ): Verdict4sError.Api =
    val lower = headers.map((k, v) => k.toLowerCase -> v)
    errorFrom(status, body, name => lower.get(name.toLowerCase))

  /** [[parseError]] over any header lookup, so the client can pass http4s'
    * headers directly. The retry delay comes from [[retryAfterMsHeader]], then
    * a `Retry-After` in seconds, then `retryAt`: the HTTP-date form needs a
    * clock, which core does not have, so the client resolves it.
    *
    * @param header
    *   a header's value by name, ignoring case
    * @param retryAt
    *   the delay a `Retry-After` HTTP date gives, if any
    */
  private[verdict4s] final def errorFrom(
      status: Int,
      body: String,
      header: String => Option[String],
      retryAt: Option[FiniteDuration] = None
  ): Verdict4sError.Api =
    val millis = retryAfterMsHeader
      .flatMap(header)
      .flatMap(_.trim.toDoubleOption)
      .filter(ms => ms >= 0 && !ms.isInfinite)
      .map(ms => RetryPolicy.serverDelay(math.round(ms), MILLISECONDS))
    val seconds = header("retry-after")
      .flatMap(_.trim.toLongOption)
      .map(RetryPolicy.serverDelay(_, SECONDS))
    Verdict4sError.fromResponse(
      status,
      parser.parse(body).getOrElse(Json.fromString(body)),
      requestId = header(requestIdHeader),
      retryAfter = millis.orElse(seconds).orElse(retryAt)
    )

/** The supported vendors. */
object Vendor:

  /** TypeSafe AI's Jev: `POST https://api.typesafe.ai/v1/systemone`. */
  val Jev: JevVendor.type = JevVendor

  /** OpenAI Decisions: `POST https://api.openai.com/v1/decisions`. */
  val OpenAI: OpenAIVendor = OpenAIVendor.default

  /** Parse a body as JSON, then read it with `f`. */
  private[verdict4s] def parseWith[A](body: String)(
      f: Json => Either[Verdict4sError, A]
  ): Either[Verdict4sError, A] =
    parser
      .parse(body)
      .leftMap(e =>
        Verdict4sError.Decoding(s"the body was not JSON: ${e.message}")
      )
      .flatMap(f)
