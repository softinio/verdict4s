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
  * client is a one-liner:
  *
  * ```scala
  * Verdict4s.default[IO](apiKey).use { client =>
  *   client.ask(Ask(Question.noul("Is this urgent?")), ticket)
  * }
  * ```
  *
  * If you already have an http4s `Client[F]`, or want a backend other than the
  * default, depend on `verdict4s-client` and construct [[Verdict4sClient]]
  * directly.
  *
  * @group Client
  */
object Verdict4s:

  /** A client backed by this platform's default transport.
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
  def default[F[_]: Async](
      apiKey: ApiKey,
      baseUri: Uri = Verdict4sClient.DefaultBaseUri,
      config: ClientConfig = ClientConfig.default
  ): Resource[F, Verdict4sClient[F]] =
    Transport.default[F].map(Verdict4sClient[F](_, apiKey, baseUri, config))
