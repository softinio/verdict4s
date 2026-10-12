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
import com.softinio.verdict4s.algebra.*
import com.softinio.verdict4s.internal.Engine
import io.circe.Encoder
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.implicits.*

/** A client for a decision API: Jev, OpenAI Decisions, or any other [[Vendor]].
  *
  * Code written against this trait works with every vendor. The questions,
  * [[Ask]] and the typed answers are the same whichever one answers them:
  *
  * ```scala
  * def triage[F[_]](
  *     client: Verdict4sClient[F]
  * ): F[(NoulAnswer, ChoiceAnswer[Dept])] =
  *   client.ask(
  *     Ask(
  *       Question.noul("Does this convey urgency?"),
  *       Question.choice[Dept]("Which team should handle this?")
  *     ),
  *     "Help! My payouts have been failing for 3 days."
  *   )
  *
  * triage(Verdict4sClient.jev[IO](httpClient, typesafeKey))
  * triage(Verdict4sClient.openAI[IO](httpClient, openAIKey))
  * ```
  *
  * What only one vendor offers is a separate capability trait, mixed into that
  * vendor's client: [[ModelCatalog]] on [[JevClient]], [[SafetyIdentity]] on
  * [[OpenAIClient]]. Ask for exactly what you use, and the compiler stops a
  * call the chosen vendor cannot serve:
  *
  * ```scala
  * def report[F[_]](using c: Verdict4sClient[F] & ModelCatalog[F]) = c.models
  * ```
  *
  * Takes an http4s `Client[F]` rather than building one. That single decision
  * buys every backend and every platform without this library owning any of
  * them — Ember, Fetch, Blaze, Netty, the JDK client — and it is why the test
  * suite needs no network: `Client.fromHttpApp` serves routes in memory, so the
  * same tests run on the JVM and on Node.
  *
  * Retries are on by default, matching the official SDKs; see
  * [[algebra.RetryPolicy]] to tune or disable them.
  *
  * @group Client
  */
trait Verdict4sClient[F[_]]:

  /** The vendor this client speaks to. */
  def vendor: Vendor

  /** The settings this client applies to every request. */
  def config: ClientConfig

  /** Evaluate a request built by hand.
    *
    * The escape hatch: answers come back in the map-based
    * [[algebra.Evaluation]], keyed by whatever question names you chose. Use
    * [[ask]] when the questions are known statically. The request is checked
    * against the vendor's own limits before it is sent.
    */
  def evaluate(request: EvaluationRequest): F[Evaluation]

  /** Ask a set of questions and get back exactly typed answers.
    *
    * ```scala
    * val (urgent, dept, mood) = client.ask(Ask(q1, q2, q3), state)
    * ```
    *
    * Fails with [[Verdict4sError.Refused]] if the vendor declines a question
    * that was not built with `orRefused`.
    */
  def ask[Q <: Tuple, S: Encoder](
      questions: Ask[Q],
      state: S
  ): F[Ask.Answers[Q]]

  /** A copy with different settings. */
  def withConfig(f: ClientConfig => ClientConfig): Verdict4sClient[F]

  /** A copy drawing retry jitter differently. Mostly useful in tests. */
  def withJitter(j: Jitter[F]): Verdict4sClient[F]

/** A vendor that lists the models available to the account. Jev does.
  *
  * @group Client
  */
trait ModelCatalog[F[_]]:

  /** The models available to this account. */
  def models: F[List[ModelCard]]

/** A vendor that accepts an identifier for your end user, to help it detect
  * abuse. OpenAI Decisions does, as `safety_identifier`.
  *
  * @group Client
  */
trait SafetyIdentity[F[_]]:

  /** The identifier sent with every request, if any. */
  def safetyIdentifier: Option[String]

  /** A copy sending this identifier with every request. At most 128 characters,
    * and never your own account identity; an invalid one fails each request
    * with [[Verdict4sError.Invalid]] before it is sent.
    */
  def withSafetyIdentifier(id: String): Verdict4sClient[F] & SafetyIdentity[F]

/** Constructing a client over a transport you supply.
  *
  * @group Client
  */
object Verdict4sClient:

  /** Jev's address, checked at compile time. */
  val JevBaseUri: Uri = uri"https://api.typesafe.ai"

  /** OpenAI's address, checked at compile time. It includes `/v1`, as it does
    * in OpenAI's own SDKs, so a base URL configured for them works here.
    */
  val OpenAIBaseUri: Uri = uri"https://api.openai.com/v1"

  /** A Jev client over a transport you supply.
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
  def jev[F[_]: Temporal](
      client: Client[F],
      apiKey: ApiKey,
      baseUri: Uri = JevBaseUri,
      config: ClientConfig = ClientConfig.default
  ): JevClient[F] =
    new JevClient(engine(client, Vendor.Jev, baseUri, apiKey, config))

  /** An OpenAI Decisions client over a transport you supply.
    *
    * @param client
    *   any http4s client, including an in-memory fake
    * @param apiKey
    *   your OpenAI API key
    * @param baseUri
    *   override for testing or a proxy; includes `/v1`
    * @param config
    *   model, retry policy and timeouts
    */
  def openAI[F[_]: Temporal](
      client: Client[F],
      apiKey: ApiKey,
      baseUri: Uri = OpenAIBaseUri,
      config: ClientConfig = ClientConfig.default
  ): OpenAIClient[F] =
    new OpenAIClient(engine(client, Vendor.OpenAI, baseUri, apiKey, config))

  /** A client for any [[Vendor]], when the vendor is chosen at runtime.
    *
    * Exposes only what every vendor shares. Use [[jev]] or [[openAI]] for a
    * client with that vendor's own capabilities.
    *
    * @param baseUri
    *   the vendor's own address when absent. A custom vendor whose
    *   `defaultBaseUrl` is not a valid URI fails here, when the client is
    *   built, rather than on the first request
    */
  def of[F[_]: Temporal](
      client: Client[F],
      vendor: Vendor,
      apiKey: ApiKey,
      baseUri: Option[Uri] = None,
      config: ClientConfig = ClientConfig.default
  ): Verdict4sClient[F] =
    val base = baseUri.getOrElse(Uri.unsafeFromString(vendor.defaultBaseUrl))
    vendor match
      case p: com.softinio.verdict4s.openai.OpenAIVendor =>
        new OpenAIClient(engine(client, p, base, apiKey, config))
      case Vendor.Jev =>
        new JevClient(engine(client, Vendor.Jev, base, apiKey, config))
      case _ => new GenericClient(engine(client, vendor, base, apiKey, config))

  private def engine[F[_]: Temporal, V <: Vendor](
      client: Client[F],
      vendor: V,
      baseUri: Uri,
      apiKey: ApiKey,
      config: ClientConfig
  ): Engine[F, V] =
    Engine(client, vendor, baseUri, apiKey, config, Jitter.scalaUtilRandom[F])

/** A client for a vendor with no capabilities beyond the shared ones. */
private final class GenericClient[F[_]](engine: Engine[F, Vendor])
    extends Verdict4sClient[F]:
  def vendor: Vendor = engine.vendor
  def config: ClientConfig = engine.config
  def evaluate(request: EvaluationRequest): F[Evaluation] =
    engine.evaluate(request)
  def ask[Q <: Tuple, S: Encoder](
      questions: Ask[Q],
      state: S
  ): F[Ask.Answers[Q]] =
    engine.ask(questions, state)
  def withConfig(f: ClientConfig => ClientConfig): Verdict4sClient[F] =
    new GenericClient(engine.withConfig(f(engine.config)))
  def withJitter(j: Jitter[F]): Verdict4sClient[F] =
    new GenericClient(engine.withJitter(j))
