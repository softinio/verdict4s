package com.softinio.verdict4s

import cats.effect.kernel.{Async, Resource}
import fs2.io.net.Network
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder

/** JVM transport: Ember, http4s' own pure-Scala client. */
private[verdict4s] object Transport:

  def default[F[_]: Async]: Resource[F, Client[F]] =
    // Derived here rather than demanded from the caller: `Network` is an
    // fs2-io concept that the Scala.js transport has no use for, so keeping it
    // out of the signature lets both platforms share one entry point.
    given Network[F] = Network.forAsync[F]
    EmberClientBuilder.default[F].build
