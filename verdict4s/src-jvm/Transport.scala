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
