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
  /** A copy using a different default model. */
  def withModel(m: Model): ClientConfig = copy(model = m)

  /** A copy using a different retry policy. */
  def withRetry(r: RetryPolicy): ClientConfig = copy(retry = r)

  /** A copy that never retries. */
  def withoutRetries: ClientConfig = copy(retry = RetryPolicy.none)

  /** A copy with a different ceiling on each HTTP attempt. */
  def withRequestTimeout(t: FiniteDuration): ClientConfig =
    copy(requestTimeout = t)

/** The default configuration, and instances. */
object ClientConfig:
  /** Retries on, `jev-latest`, and a 10 second timeout per attempt. */
  val default: ClientConfig = ClientConfig()

  given Eq[ClientConfig] = Eq.fromUniversalEquals
  given Show[ClientConfig] = Show.fromToString
