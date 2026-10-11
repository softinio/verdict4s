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

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import com.softinio.verdict4s.algebra.ApiKey
import com.softinio.verdict4s.algebra.ClientConfig
import org.http4s.Uri

/** Batteries-included entry point.
  *
  * Picks a sensible transport for whichever platform you are on — Ember on the
  * JVM, the browser and Node Fetch API on Scala.js — so getting a working
  * client is a one-liner, for either vendor:
  *
  * ```scala
  * Verdict4s.jev[IO](typesafeKey).use { client =>
  *   client.ask(Ask(Question.noul("Is this urgent?")), ticket)
  * }
  *
  * Verdict4s.openAI[IO](openAIKey).use { client =>
  *   client.ask(Ask(Question.noul("Is this urgent?")), ticket)
  * }
  * ```
  *
  * If you already have an http4s `Client[F]`, or want a backend other than the
  * default, depend on `verdict4s-client` and construct a [[Verdict4sClient]]
  * directly.
  *
  * @group Client
  */
object Verdict4s:

  /** A Jev client backed by this platform's default transport.
    *
    * The [[cats.effect.kernel.Resource]] owns the underlying connection pool,
    * so allocate it once for the lifetime of your application rather than once
    * per call.
    *
    * @param apiKey
    *   your TypeSafe API key
    * @param baseUri
    *   override for testing or for a proxy
    * @param config
    *   model, retry policy and timeouts
    */
  def jev[F[_]: Async](
      apiKey: ApiKey,
      baseUri: Uri = Verdict4sClient.JevBaseUri,
      config: ClientConfig = ClientConfig.default
  ): Resource[F, JevClient[F]] =
    Transport.default[F].map(Verdict4sClient.jev[F](_, apiKey, baseUri, config))

  /** An OpenAI Decisions client backed by this platform's default transport.
    *
    * @param apiKey
    *   your OpenAI API key
    * @param baseUri
    *   override for testing or for a proxy; includes `/v1`
    * @param config
    *   model, retry policy and timeouts
    */
  def openAI[F[_]: Async](
      apiKey: ApiKey,
      baseUri: Uri = Verdict4sClient.OpenAIBaseUri,
      config: ClientConfig = ClientConfig.default
  ): Resource[F, OpenAIClient[F]] =
    Transport
      .default[F]
      .map(Verdict4sClient.openAI[F](_, apiKey, baseUri, config))

  /** A client for any [[Vendor]], when the vendor is chosen at runtime.
    *
    * @param baseUri
    *   the vendor's own address when absent
    */
  def of[F[_]: Async](
      vendor: Vendor,
      apiKey: ApiKey,
      baseUri: Option[Uri] = None,
      config: ClientConfig = ClientConfig.default
  ): Resource[F, Verdict4sClient[F]] =
    Transport
      .default[F]
      .map(Verdict4sClient.of[F](_, vendor, apiKey, baseUri, config))
