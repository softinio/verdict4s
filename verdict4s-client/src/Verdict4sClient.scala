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

import cats.effect.kernel.Temporal
import cats.syntax.all.*
import com.softinio.verdict4s.algebra.*
import com.softinio.verdict4s.internal.RetryLoop
import com.softinio.verdict4s.internal.Wire
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.syntax.*
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.implicits.*

/** A client for the TypeSafe evaluation API.
  *
  * Takes an http4s `Client[F]` rather than building one. That single decision
  * buys every backend and every platform without this library owning any of
  * them — Ember, Fetch, Blaze, Netty, the JDK client — and it is why the test
  * suite needs no network: `Client.fromHttpApp` serves routes in memory, so the
  * same tests run on the JVM and on Node.
  *
  * ```scala
  * val client = Verdict4sClient[IO](httpClient, apiKey)
  *
  * val (urgent, dept) = client.ask(
  *   Ask(
  *     Question.noul("Does this convey urgency?"),
  *     Question.choice[Dept]("Which team should handle this?")
  *   ),
  *   "Help! My payouts have been failing for 3 days."
  * )
  * ```
  *
  * Retries are on by default, matching the official SDKs; see
  * [[algebra.RetryPolicy]] to tune or disable them.
  *
  * @group Client
  */
final class Verdict4sClient[F[_]: Temporal] private (
    client: Client[F],
    baseUri: Uri,
    apiKey: ApiKey,
    /** The settings this client applies to every request. */
    val config: ClientConfig,
    jitter: Jitter[F]
):

  /** Evaluate a request built by hand.
    *
    * The escape hatch: answers come back in the map-based
    * [[algebra.Evaluation]], keyed by whatever question names you chose. Use
    * [[ask]] when the questions are known statically.
    */
  def evaluate(request: EvaluationRequest): F[Evaluation] =
    send[Evaluation](request.asJson)

  /** Ask a set of questions and get back exactly typed answers.
    *
    * ```scala
    * val (urgent, dept, mood) = client.ask(Ask(q1, q2, q3), state)
    * ```
    */
  def ask[Q <: Tuple, S: Encoder](
      questions: Ask[Q],
      state: S
  ): F[Ask.Answers[Q]] =
    for
      request <- Temporal[F].fromEither(
        questions
          .request(state, config.model)
          .toEither
          .leftMap(Verdict4sError.Invalid(_))
      )
      evaluation <- evaluate(request)
      answers <- Temporal[F].fromEither(questions.answers(evaluation))
    yield answers

  /** The models available to this account. */
  def models: F[List[ModelCard]] =
    // The endpoint wraps its array in a `models` field; this shadows circe's
    // derived list decoder, which would expect a bare array.
    given Decoder[List[ModelCard]] = ModelCard.listDecoder
    RetryLoop.run[F, List[ModelCard]](
      client,
      Wire.get[F](Wire.modelsUri(baseUri), apiKey),
      config.retry,
      jitter
    )(Wire.decode[F, List[ModelCard]])

  /** A copy with different settings. */
  def withConfig(f: ClientConfig => ClientConfig): Verdict4sClient[F] =
    new Verdict4sClient(client, baseUri, apiKey, f(config), jitter)

  /** A copy drawing retry jitter differently. Mostly useful in tests. */
  def withJitter(j: Jitter[F]): Verdict4sClient[F] =
    new Verdict4sClient(client, baseUri, apiKey, config, j)

  private def send[A: io.circe.Decoder](body: Json): F[A] =
    RetryLoop.run[F, A](
      client,
      Wire.post[F](Wire.evaluateUri(baseUri), apiKey, body),
      config.retry,
      jitter
    )(Wire.decode[F, A])

/** Constructing a [[Verdict4sClient]] over a transport you supply. */
object Verdict4sClient:

  /** The service's own address, checked at compile time. */
  val DefaultBaseUri: Uri = uri"https://api.typesafe.ai"

  /** A client over a transport you supply.
    *
    * @param client
    *   any http4s client, including an in-memory fake
    * @param apiKey
    *   your TypeSafe API key
    * @param baseUri
    *   override for testing or a proxy
    * @param config
    *   model, retry policy and timeouts
    */
  def apply[F[_]: Temporal](
      client: Client[F],
      apiKey: ApiKey,
      baseUri: Uri = DefaultBaseUri,
      config: ClientConfig = ClientConfig.default
  ): Verdict4sClient[F] =
    new Verdict4sClient(
      client,
      baseUri,
      apiKey,
      config,
      Jitter.scalaUtilRandom[F]
    )
