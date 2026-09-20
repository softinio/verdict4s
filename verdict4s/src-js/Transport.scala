package com.softinio.verdict4s

import cats.effect.kernel.{Async, Resource}
import org.http4s.client.Client
import org.http4s.dom.FetchClientBuilder

/** Scala.js transport: the Fetch API.
  *
  * Works in the browser and on Node 18+, where `fetch` is a global. Ember is
  * deliberately not used here: it needs raw TCP sockets, which a browser does
  * not expose.
  */
private[verdict4s] object Transport:

  def default[F[_]: Async]: Resource[F, Client[F]] =
    Resource.pure(FetchClientBuilder[F].create)
