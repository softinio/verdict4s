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

import cats.Applicative
import cats.syntax.all.*

/** Where a retry delay's randomness comes from.
  *
  * A two-method trait rather than a direct call to a random source, for two
  * reasons. Tests get a fully deterministic schedule from [[Jitter.constant]]
  * without a mocking library. And the obvious alternatives are both worse here:
  * `cats.effect.std.Random` is only transitively on the classpath, and deriving
  * randomness from `Temporal.monotonic` yields a constant on Scala.js, where
  * the clock is millisecond-granular.
  *
  * This lives in the client, not in `algebra`, because drawing a sample is an
  * effect and the core must stay effect-free.
  *
  * @group Configuration
  */
trait Jitter[F[_]]:
  /** A fresh sample in [0, 1). */
  def sample: F[Double]

/** Ready-made sources of jitter: a random one, and a constant one for tests. */
object Jitter:

  /** Samples from `scala.util.Random`. Works on both platforms. */
  def scalaUtilRandom[F[_]: Applicative]: Jitter[F] =
    new Jitter[F]:
      def sample: F[Double] =
        Applicative[F].unit.map(_ => scala.util.Random.nextDouble())

  /** Always returns the same sample, making a retry schedule reproducible. */
  def constant[F[_]: Applicative](value: Double): Jitter[F] =
    new Jitter[F]:
      def sample: F[Double] = Applicative[F].pure(value)
