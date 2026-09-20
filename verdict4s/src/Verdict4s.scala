package com.softinio.verdict4s

import cats.effect.kernel.{Async, Resource}
import org.http4s.Uri

/** Batteries-included entry point.
  *
  * Picks a sensible transport for whichever platform you are on — Ember on the
  * JVM, the browser/Node Fetch API on Scala.js — so that getting a working
  * client is a one-liner. If you already have an http4s `Client[F]`, or want a
  * backend other than the default, depend on `verdict4s-client` instead and
  * construct [[Verdict4sClient]] directly.
  */
object Verdict4s:

  /** A client backed by this platform's default transport.
    *
    * The [[cats.effect.kernel.Resource]] owns the underlying connection pool,
    * so allocate it once for the lifetime of your application rather than per
    * call.
    */
  def default[F[_]: Async](baseUri: Uri): Resource[F, Verdict4sClient[F]] =
    Transport.default[F].map(Verdict4sClient[F](_, baseUri))
