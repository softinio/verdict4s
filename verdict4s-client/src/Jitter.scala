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
