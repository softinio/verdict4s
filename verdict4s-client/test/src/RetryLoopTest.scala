package com.softinio.verdict4s

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.Ref
import com.softinio.verdict4s.algebra.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*

/** Retry behaviour, asserted by counting attempts.
  *
  * The double is a `Ref` and a route: no mocking library. Determinism comes
  * from `Jitter.constant`, and speed from a 1ms initial backoff, which keeps
  * this fast on Node too, where timers are millisecond-granular.
  */
class RetryLoopTest extends CatsEffectSuite:

  private val baseUri = uri"https://example.invalid"
  private val key = ApiKey.fromString("sk-test").toOption.get
  private val ask = Ask(Question.noul("urgent?"))

  private val okBody = """
    {"model":"jev-1.13.0",
     "answers":{"q00":{"type":"noul","noul":0.95}},
     "usage":{"input_tokens":1,"output_tokens":1}}
  """

  private val fastRetries = RetryPolicy(
    maxRetries = 2,
    backoffInitial = 1.milli,
    backoffMax = 5.millis,
    jitter = 0.0
  ).toOption.get

  /** Serves the given statuses in order, then 200 forever, counting calls. */
  private def flaky(
      failures: List[Status],
      policy: RetryPolicy = fastRetries
  ): IO[(Verdict4sClient[IO], Ref[IO, Int])] =
    Ref[IO]
      .of(0)
      .map: calls =>
        val app = HttpRoutes
          .of[IO] { case POST -> Root / "v1" / "systemone" =>
            calls.getAndUpdate(_ + 1).flatMap { n =>
              failures.lift(n) match
                case Some(status) =>
                  IO.pure(Response[IO](status).withEntity("{}"))
                case None => Ok(okBody)
            }
          }
          .orNotFound
        val client = Verdict4sClient[IO](
          Client.fromHttpApp(app),
          key,
          baseUri,
          ClientConfig.default.withRetry(policy)
        ).withJitter(Jitter.constant[IO](0.5))
        (client, calls)

  test("a 429 is retried and the eventual success is returned"):
    for
      pair <- flaky(List(Status.TooManyRequests, Status.TooManyRequests))
      (client, calls) = pair
      answer <- client.ask(ask, "s")
      n <- calls.get
    yield
      assertEquals(n, 3, "two failures then a success is three attempts")
      assert(answer.head.isTrue())

  test("529 Overloaded is retried too"):
    for
      pair <- flaky(List(Status(529)))
      (client, calls) = pair
      _ <- client.ask(ask, "s")
      n <- calls.get
    yield assertEquals(n, 2)

  test("a 5xx is retried"):
    for
      pair <- flaky(List(Status.InternalServerError))
      (client, calls) = pair
      _ <- client.ask(ask, "s")
      n <- calls.get
    yield assertEquals(n, 2)

  test("a non-retryable status is attempted exactly once"):
    for
      pair <- flaky(List.fill(5)(Status.Unauthorized))
      (client, calls) = pair
      result <- client.ask(ask, "s").attempt
      n <- calls.get
    yield
      assertEquals(n, 1, "401 must not be retried")
      assert(result.isLeft)

  test("422 is not retried either"):
    for
      pair <- flaky(List.fill(5)(Status.UnprocessableEntity))
      (client, calls) = pair
      _ <- client.ask(ask, "s").attempt
      n <- calls.get
    yield assertEquals(n, 1)

  test("retries are bounded by maxRetries"):
    for
      pair <- flaky(List.fill(10)(Status.TooManyRequests))
      (client, calls) = pair
      result <- client.ask(ask, "s").attempt
      n <- calls.get
    yield
      assertEquals(n, 3, "maxRetries = 2 means at most three attempts")
      result match
        case Left(e: Verdict4sError.Api) =>
          assertEquals(e.failure, ApiFailure.RateLimited)
        case other => fail(s"expected Api error, got $other")

  test("RetryPolicy.none makes exactly one attempt"):
    for
      pair <- flaky(List.fill(5)(Status.TooManyRequests), RetryPolicy.none)
      (client, calls) = pair
      _ <- client.ask(ask, "s").attempt
      n <- calls.get
    yield assertEquals(n, 1)

  test("a successful first attempt is not retried"):
    for
      pair <- flaky(Nil)
      (client, calls) = pair
      _ <- client.ask(ask, "s")
      n <- calls.get
    yield assertEquals(n, 1)

  test("the body of a retried request is still fully read"):
    // Guards the classic http4s mistake of letting the response body escape
    // the `use` block: a retried request that then decodes proves it does not.
    for
      pair <- flaky(List(Status.TooManyRequests))
      (client, _) = pair
      answer <- client.ask(ask, "s")
    yield assertEquals(answer.head.value, Probability.either(0.95).toOption.get)

  test("the final error after exhaustion carries the server's Retry-After"):
    for
      calls <- Ref[IO].of(0)
      app = HttpRoutes
        .of[IO] { case POST -> Root / "v1" / "systemone" =>
          calls.update(_ + 1) *>
            IO.pure(
              Response[IO](Status.TooManyRequests)
                .withEntity("{}")
                .putHeaders(
                  Header.Raw(org.typelevel.ci.CIString("Retry-After"), "1")
                )
            )
        }
        .orNotFound
      client = Verdict4sClient[IO](
        Client.fromHttpApp(app),
        key,
        baseUri,
        ClientConfig.default.withRetry(fastRetries)
      ).withJitter(Jitter.constant[IO](0.5))
      result <- client.ask(ask, "s").attempt
    yield result match
      case Left(e: Verdict4sError.Api) =>
        assertEquals(e.retryAfter.map(_.toSeconds), Some(1L))
      case other => fail(s"expected Api error, got $other")
