package com.softinio.verdict4s.algebra

import scala.collection.immutable.SortedSet
import scala.concurrent.duration.*

/** The backoff schedule is a pure function of its inputs, including the jitter
  * sample, so every expectation below is exact: no clock, no random source, and
  * no mocking library anywhere.
  */
class RetryPolicyTest extends munit.FunSuite:

  private val p = RetryPolicy.default

  test("default matches the settings the official SDKs use"):
    assertEquals(p.maxRetries, 2)
    assertEquals(p.backoffInitial, 500.millis)
    assertEquals(p.backoffMax, 5.seconds)
    assertEquals(p.jitter, 0.25)
    assertEquals(p.respectRetryAfter, true)
    assertEquals(p.totalTimeout, 30.seconds)

  test("a mid jitter sample leaves the delay exactly on the backoff"):
    // sample 0.5 is the midpoint, so the jitter factor is exactly 1.
    assertEquals(p.delayFor(1, None, 0.5), Some(500.millis))
    assertEquals(p.delayFor(2, None, 0.5), Some(1000.millis))

  test("jitter spreads the delay by the configured fraction"):
    // 0.0 is the low end (-25%), 1.0 the high end (+25%).
    assertEquals(p.delayFor(1, None, 0.0), Some(375.millis))
    assertEquals(p.delayFor(1, None, 1.0), Some(625.millis))

  test("backoff doubles and then caps"):
    val long = RetryPolicy(maxRetries = 6, jitter = 0.0).toOption.get
    assertEquals(
      long.schedule(LazyList.continually(0.5)),
      List(500.millis, 1.second, 2.seconds, 4.seconds, 5.seconds, 5.seconds)
    )

  test("retries are exhausted after maxRetries"):
    assertEquals(p.delayFor(3, None, 0.5), None)
    assertEquals(p.delayFor(99, None, 0.5), None)

  test("attempt numbering is 1-based"):
    assertEquals(p.delayFor(0, None, 0.5), None)

  test("RetryPolicy.none never retries"):
    assertEquals(RetryPolicy.none.delayFor(1, None, 0.5), None)
    assertEquals(RetryPolicy.none.schedule(LazyList.continually(0.5)), Nil)

  test("Retry-After wins when it asks for longer than the backoff"):
    assertEquals(p.delayFor(1, Some(3.seconds), 0.5), Some(3.seconds))

  test("Retry-After is ignored when it asks for less than the backoff"):
    // Backing off less than we already planned to would defeat the point.
    assertEquals(p.delayFor(1, Some(10.millis), 0.5), Some(500.millis))

  test("respectRetryAfter = false ignores the header entirely"):
    val ignoring = RetryPolicy(respectRetryAfter = false).toOption.get
    assertEquals(ignoring.delayFor(1, Some(30.seconds), 0.5), Some(500.millis))

  test("the retryable set covers what the API documents"):
    assert(p.retriesStatus(408))
    assert(p.retriesStatus(429))
    assert(p.retriesStatus(500))
    assert(p.retriesStatus(529), "529 Overloaded must be retryable")
    assert(p.retriesStatus(599))
    assert(!p.retriesStatus(400))
    assert(!p.retriesStatus(401))
    assert(!p.retriesStatus(404))
    assert(!p.retriesStatus(422))

  test("retryAfterDelay clamps negative clock skew to zero"):
    assertEquals(RetryPolicy.retryAfterDelay(100L, 105L), 5.seconds)
    assertEquals(RetryPolicy.retryAfterDelay(100L, 100L), Duration.Zero)
    assertEquals(RetryPolicy.retryAfterDelay(100L, 90L), Duration.Zero)

  test("the smart constructor accumulates every bad field at once"):
    val bad = RetryPolicy(
      maxRetries = -1,
      backoffInitial = 10.seconds,
      backoffMax = 1.second,
      jitter = 2.0,
      totalTimeout = Duration.Zero
    )
    assertEquals(bad.swap.toOption.get.length, 4L)

  test("a valid policy passes its own checks"):
    assert(
      RetryPolicy(
        maxRetries = 5,
        backoffInitial = 1.milli,
        backoffMax = 1.second,
        jitter = 0.0,
        retryableStatuses = SortedSet(429),
        totalTimeout = 1.minute
      ).isValid
    )

  test("zero jitter makes the schedule fully deterministic"):
    val fixed = RetryPolicy(jitter = 0.0).toOption.get
    assertEquals(fixed.delayFor(1, None, 0.0), fixed.delayFor(1, None, 1.0))

  test("ClientConfig defaults to the recommended model and retry policy"):
    assertEquals(ClientConfig.default.model, Model.JevLatest)
    assertEquals(ClientConfig.default.retry, RetryPolicy.default)
    assertEquals(ClientConfig.default.requestTimeout, 10.seconds)
    assertEquals(ClientConfig.default.withoutRetries.retry, RetryPolicy.none)
