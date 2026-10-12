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

import scala.collection.immutable.SortedSet
import scala.concurrent.duration.*

import cats.Eq
import cats.Show
import cats.data.NonEmptyChain
import cats.data.ValidatedNec
import cats.syntax.all.*
import com.softinio.verdict4s.Verdict4sError

/** How to back off and retry when the service asks you to.
  *
  * The API documents 429 and 529 with an explicit instruction to retry with
  * exponential backoff, and the official SDKs do so by default;
  * [[RetryPolicy.default]] matches their settings.
  *
  * Everything here is pure. [[delayFor]] takes the jitter sample as an argument
  * rather than drawing one, which means the whole schedule is a function of its
  * inputs and can be tested exactly, with no clock, no random source, and no
  * mocking library. Drawing the sample is the client's job.
  *
  * @param maxRetries
  *   attempts after the first; 0 disables retrying
  * @param backoffInitial
  *   the first delay, doubled each attempt
  * @param backoffMax
  *   ceiling for the doubling
  * @param jitter
  *   0 to 1; the fraction by which a delay may vary, so that many clients
  *   retrying at once spread out instead of arriving together
  * @param retryableStatuses
  *   which statuses are worth retrying
  * @param respectRetryAfter
  *   whether a `Retry-After` header wins when it asks for longer than the
  *   computed backoff
  * @param totalTimeout
  *   ceiling on the whole call, retries included. Exceeding it fails the call
  *   with a [[com.softinio.verdict4s.Verdict4sError.Transport]]. A delay, from
  *   the header or the backoff, of at least the time left in it ends the
  *   retries instead, with the server's own error, since that wait could only
  *   end in a timeout.
  *
  * @group Configuration
  */
final case class RetryPolicy private (
    maxRetries: Int,
    backoffInitial: FiniteDuration,
    backoffMax: FiniteDuration,
    jitter: Double,
    retryableStatuses: SortedSet[Int],
    respectRetryAfter: Boolean,
    totalTimeout: FiniteDuration
):

  /** Whether this status is one this policy retries. */
  def retriesStatus(status: Int): Boolean = retryableStatuses.contains(status)

  /** How long to wait before `attempt`, or `None` when retries are exhausted.
    *
    * The delay is the jittered backoff, or the server's `Retry-After` when it
    * is honoured and asks for longer. Also `None` when that delay is at least
    * `totalTimeout`, by the same rule whether it came from the header or the
    * backoff: the call could never outlive that wait, so sleeping through it
    * would only trade the server's error for a timeout. Giving up returns the
    * server's own error, with its `retryAfter`, at once.
    *
    * This is pure, so it can only compare against the whole budget. The client
    * applies the same rule to the budget left: it also gives up when the delay
    * is at least `totalTimeout` minus the time the call has already taken.
    *
    * @param attempt
    *   1-based: the attempt about to be made, so the first retry is attempt 1
    * @param retryAfter
    *   the server's own request, if it sent one
    * @param jitterSample
    *   a value in [0, 1) supplied by the caller
    */
  def delayFor(
      attempt: Int,
      retryAfter: Option[FiniteDuration],
      jitterSample: Double
  ): Option[FiniteDuration] =
    if attempt < 1 || attempt > maxRetries then None
    else
      // Work in nanos: `FiniteDuration * Double` yields a Duration, not a
      // FiniteDuration, and overflows silently.
      val doubled =
        backoffInitial.toNanos.toDouble * math.pow(2.0, (attempt - 1).toDouble)
      val capped = math.min(doubled, backoffMax.toNanos.toDouble)
      val spread = capped * (1.0 + jitter * (2.0 * jitterSample - 1.0))
      val base = FiniteDuration(math.max(0.0, spread).toLong, NANOSECONDS)
      val delay = retryAfter.filter(_ => respectRetryAfter) match
        case Some(after) if after > base => after
        case _                           => base
      // The same cutoff whichever of the two the delay came from: a wait of
      // the whole budget or more could only end in a timeout.
      Option.when(delay < totalTimeout)(delay)

  /** The whole schedule, for documentation and one-assertion tests. */
  def schedule(jitterSamples: LazyList[Double]): List[FiniteDuration] =
    (1 to maxRetries).toList
      .zip(jitterSamples)
      .flatMap((attempt, sample) => delayFor(attempt, None, sample))

/** The default policy, a policy that never retries, and a validated constructor
  * for your own.
  */
object RetryPolicy:

  /** The settings the official SDKs use. */
  val default: RetryPolicy = new RetryPolicy(
    maxRetries = 2,
    backoffInitial = 500.millis,
    backoffMax = 5.seconds,
    jitter = 0.25,
    retryableStatuses = SortedSet(408, 429) ++ SortedSet.from(500 to 599),
    respectRetryAfter = true,
    totalTimeout = 30.seconds
  )

  /** Do not retry at all. */
  val none: RetryPolicy = default.copy(maxRetries = 0)

  /** Build a policy, reporting every bad field at once. */
  def apply(
      maxRetries: Int = default.maxRetries,
      backoffInitial: FiniteDuration = default.backoffInitial,
      backoffMax: FiniteDuration = default.backoffMax,
      jitter: Double = default.jitter,
      retryableStatuses: SortedSet[Int] = default.retryableStatuses,
      respectRetryAfter: Boolean = default.respectRetryAfter,
      totalTimeout: FiniteDuration = default.totalTimeout
  ): ValidatedNec[Verdict4sError.Validation, RetryPolicy] =
    (
      check(maxRetries >= 0, "maxRetries", "must not be negative"),
      check(
        backoffInitial >= Duration.Zero,
        "backoffInitial",
        "must not be negative"
      ),
      check(
        backoffMax >= backoffInitial,
        "backoffMax",
        "must be at least backoffInitial"
      ),
      check(
        jitter >= 0.0 && jitter <= 1.0,
        "jitter",
        "must be between 0 and 1"
      ),
      check(totalTimeout > Duration.Zero, "totalTimeout", "must be positive")
    ).mapN: (_, _, _, _, _) =>
      new RetryPolicy(
        maxRetries,
        backoffInitial,
        backoffMax,
        jitter,
        retryableStatuses,
        respectRetryAfter,
        totalTimeout
      )

  /** Turn a `Retry-After` given as an HTTP date into a delay.
    *
    * Clamped at zero, because clock skew between client and server can
    * otherwise produce a negative wait.
    */
  def retryAfterDelay(
      nowEpochSecond: Long,
      targetEpochSecond: Long
  ): FiniteDuration =
    serverDelay(targetEpochSecond - nowEpochSecond, SECONDS)

  /** A delay the server asked for, clamped between zero and the longest
    * `FiniteDuration`, so that neither clock skew nor an absurd header value
    * can make it negative or throw.
    */
  private[verdict4s] def serverDelay(
      length: Long,
      unit: TimeUnit
  ): FiniteDuration =
    FiniteDuration(
      math.min(math.max(0L, length), unit.convert(Long.MaxValue, NANOSECONDS)),
      unit
    )

  private def check(
      ok: Boolean,
      field: String,
      details: String
  ): ValidatedNec[Verdict4sError.Validation, Unit] =
    if ok then ().validNec
    else NonEmptyChain.one(Verdict4sError.Validation(field, details)).invalid

  given Eq[RetryPolicy] = Eq.fromUniversalEquals
  given Show[RetryPolicy] = Show.show(r =>
    s"RetryPolicy(maxRetries=${r.maxRetries}, initial=${r.backoffInitial}, " +
      s"max=${r.backoffMax}, jitter=${r.jitter})"
  )
