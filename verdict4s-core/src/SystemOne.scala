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

import com.softinio.verdict4s.algebra.*
import io.circe.Encoder
import io.circe.parser
import io.circe.syntax.*

/** Driving the protocol with no effect system at all.
  *
  * `verdict4s-core` carries only cats-core, circe and Iron: no effect type, no
  * HTTP client, no fs2. That is what this object is for. Render a request to
  * JSON, send it with whatever HTTP client you already have, and parse the
  * reply back into typed answers:
  *
  * ```scala
  * val body = SystemOne.render(request)
  * val text = myHttpClient.post(SystemOne.evaluateUrl, body) // your code
  * val evaluation = SystemOne.parse(text)
  * ```
  *
  * This is the path for Twitter `Future`, for Akka or Pekko HTTP, for sttp, and
  * for a plain blocking client — anything without a lawful cats-effect
  * instance. If you do have one, `verdict4s-client` is less work.
  *
  * @group No effect system
  */
object SystemOne:

  /** Where to POST an evaluation, using the default base URL. */
  val evaluateUrl: String =
    s"${Defaults.BaseUrl}/${Defaults.EvaluatePath.mkString("/")}"

  /** Where to GET the list of available models. */
  val modelsUrl: String =
    s"${Defaults.BaseUrl}/${Defaults.ModelsPath.mkString("/")}"

  /** The `Authorization` header value for a key. */
  def authorization(apiKey: ApiKey): String = s"Bearer ${apiKey.bearer}"

  /** The headers every request needs, as plain pairs. */
  def headers(apiKey: ApiKey): List[(String, String)] =
    List(
      "Authorization" -> authorization(apiKey),
      "Content-Type" -> "application/json"
    )

  /** Render a request as the JSON to send. */
  def render(request: EvaluationRequest): String = request.asJson.noSpaces

  /** Render a request indented, for logs and documentation. */
  def renderPretty(request: EvaluationRequest): String = request.asJson.spaces2

  /** Build and render in one step, reporting every problem at once.
    *
    * ```scala
    * SystemOne.renderAsk(Ask(q1, q2), state, Model.JevLatest)
    * ```
    */
  def renderAsk[Q <: Tuple, S: Encoder](
      ask: Ask[Q],
      state: S,
      model: Model = Model.JevLatest
  ): Either[Verdict4sError, String] =
    ask
      .request(state, model)
      .toEither
      .left
      .map(Verdict4sError.Invalid(_))
      .map(render)

  /** Parse a successful response body into typed answers. */
  def parse(body: String): Either[Verdict4sError, Evaluation] =
    parser
      .parse(body)
      .left
      .map(f => Verdict4sError.Decoding(s"the body was not JSON: ${f.message}"))
      .flatMap: json =>
        json
          .as[Evaluation]
          .left
          .map(f =>
            Verdict4sError.Decoding(
              f.message,
              Option(io.circe.CursorOp.opsToPath(f.history)).filter(_.nonEmpty)
            )
          )

  /** Parse a response and project it through the questions that produced it. */
  def parseAsk[Q <: Tuple](
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
    *   the response headers, for the request id and `Retry-After`
    */
  def parseError(
      status: Int,
      body: String,
      headers: Map[String, String] = Map.empty
  ): Verdict4sError.Api =
    val json = parser.parse(body).getOrElse(io.circe.Json.fromString(body))
    val lower = headers.map((k, v) => k.toLowerCase -> v)
    Verdict4sError.fromResponse(
      status,
      json,
      requestId = lower.get(Defaults.RequestIdHeader),
      retryAfter = lower
        .get("retry-after")
        .flatMap(_.toLongOption)
        .map(scala.concurrent.duration.Duration(_, "seconds"))
    )
