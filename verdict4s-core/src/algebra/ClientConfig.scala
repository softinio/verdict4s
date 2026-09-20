package com.softinio.verdict4s.algebra

import scala.concurrent.duration.*

import cats.Eq
import cats.Show

/** Settings a client applies to every request.
  *
  * Deliberately carries no base URL. `org.http4s.Uri` is not available in the
  * effect-free core, and holding the address as a `String` here would push a
  * parse failure into the happy path at runtime; the client turns
  * [[Defaults.BaseUrl]] into a compile-time-checked literal instead.
  *
  * @param model
  *   the model used when a request does not name one
  * @param retry
  *   how to back off and retry
  * @param requestTimeout
  *   ceiling on one HTTP attempt
  *
  * @group Configuration
  */
final case class ClientConfig(
    model: Model = Model.JevLatest,
    retry: RetryPolicy = RetryPolicy.default,
    requestTimeout: FiniteDuration = 10.seconds
):
  def withModel(m: Model): ClientConfig = copy(model = m)
  def withRetry(r: RetryPolicy): ClientConfig = copy(retry = r)
  def withoutRetries: ClientConfig = copy(retry = RetryPolicy.none)
  def withRequestTimeout(t: FiniteDuration): ClientConfig =
    copy(requestTimeout = t)

object ClientConfig:
  val default: ClientConfig = ClientConfig()

  given Eq[ClientConfig] = Eq.fromUniversalEquals
  given Show[ClientConfig] = Show.fromToString
