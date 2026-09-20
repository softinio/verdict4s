package com.softinio.verdict4s

import cats.effect.kernel.Concurrent
import cats.syntax.all.*
import org.http4s.{Method, Request, Uri}
import org.http4s.client.Client

/** Tagless-final client for the decision service.
  *
  * Takes an http4s [[org.http4s.client.Client]] rather than building one, so
  * the same code runs on every backend and platform: Ember on the JVM, Fetch in
  * the browser, or `Client.fromHttpApp` for a test that never touches the
  * network. See the `verdict4s` module if you would rather not pick a backend.
  */
final class Verdict4sClient[F[_]: Concurrent] private (
    client: Client[F],
    baseUri: Uri
):

  /** Liveness probe against the configured service. */
  def health: F[Boolean] =
    client.status(Request[F](Method.GET, baseUri / "health")).map(_.isSuccess)

object Verdict4sClient:

  def apply[F[_]: Concurrent](
      client: Client[F],
      baseUri: Uri
  ): Verdict4sClient[F] =
    new Verdict4sClient[F](client, baseUri)
