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

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*
import com.softinio.verdict4s.algebra.*
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.Uri.Path
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.typelevel.ci.CIString

/** What every vendor's client must do, run once per vendor.
  *
  * The point of the vendor abstraction is that one `Ask` gets the same typed
  * answers whichever service answers it. This suite is that claim as a test:
  * each subclass supplies an in-memory fake speaking its vendor's wire format,
  * and every assertion below is shared.
  */
abstract class VendorContractSuite extends CatsEffectSuite:

  /** The client under test, over the fake. */
  def clientFor(app: HttpApp[IO], config: ClientConfig): Verdict4sClient[IO]

  /** The vendor's evaluation endpoint, as the fake should route it. */
  def endpoint: Path

  /** The vendor's spelling of noul 0.95, choice Billing (0.88 / 0.12, 0.81),
    * and score 1.05 over Calm / Frustrated / Very angry (0.92), as q00 to q02,
    * with 296 input and 20 output tokens.
    */
  def okBody: String

  /** The body the vendor's service expects for `ask` over the state
    * `"payouts failing"`, with the vendor's default model, written out by hand
    * so the client is checked against the wire format rather than against its
    * own rendering.
    */
  def expectedBody: String

  /** The vendor's request id header. */
  def requestIdHeader: String

  enum Dept derives Options:
    case Billing, Technical

  protected val baseUri = uri"https://example.invalid/base"
  protected val key = ApiKey.fromString("sk-test").toOption.get

  protected val ask = Ask(
    Question.noul("Does this convey urgency?"),
    Question.choice[Dept]("Which team should handle this?"),
    Question.score("How frustrated?", Seq("Calm", "Frustrated", "Very angry"))
  )

  protected def client(
      app: HttpApp[IO],
      config: ClientConfig = ClientConfig.default
  ): Verdict4sClient[IO] =
    clientFor(app, config).withJitter(Jitter.constant[IO](0.5))

  protected def respond(response: IO[Response[IO]]): HttpApp[IO] =
    HttpApp[IO]:
      case req
          if req.method == Method.POST && req.uri.path == baseUri.path.concat(
            endpoint
          ) =>
        response
      case _ => NotFound()

  /** 1ms backoff keeps retries fast on Node too, where timers are
    * millisecond-granular; `Jitter.constant` keeps them deterministic.
    */
  protected val fastRetries: RetryPolicy = RetryPolicy(
    maxRetries = 2,
    backoffInitial = 1.milli,
    backoffMax = 5.millis,
    jitter = 0.0
  ).toOption.get

  protected def failing(status: Status): Response[IO] =
    Response[IO](status).withEntity("{}")

  /** Serves `serve(n)` for the n-th call, counting the calls. */
  protected def counted(
      config: ClientConfig
  )(serve: Int => IO[Response[IO]]): IO[(Verdict4sClient[IO], Ref[IO, Int])] =
    Ref[IO]
      .of(0)
      .map: calls =>
        (
          client(respond(calls.getAndUpdate(_ + 1).flatMap(serve)), config),
          calls
        )

  /** Serves `failures` in order, then `okBody` forever, counting calls. */
  protected def flaky(
      failures: List[Response[IO]],
      policy: RetryPolicy = fastRetries
  ): IO[(Verdict4sClient[IO], Ref[IO, Int])] =
    counted(ClientConfig.default.withRetry(policy))(n =>
      failures.lift(n).fold(Ok(okBody))(IO.pure)
    )

  /** The error one response becomes, with retries off. */
  protected def apiErrorFrom(response: Response[IO]): IO[Verdict4sError.Api] =
    client(respond(IO.pure(response)), ClientConfig.default.withoutRetries)
      .ask(ask, "s")
      .attempt
      .map:
        case Left(e: Verdict4sError.Api) => e
        case other => fail(s"expected an Api error, got $other")

  /** 529 is not a registered status, so http4s will not hand one over without
    * being asked directly.
    */
  private val overloaded: Status = Status.fromInt(529).toOption.get

  // -- Answers and the request ------------------------------------------------

  test("one Ask gets the same typed answers from every vendor"):
    client(respond(Ok(okBody)))
      .ask(ask, "Help! My payouts are failing.")
      .map:
        case (urgency, team, mood) =>
          val u: NoulAnswer = urgency
          val t: ChoiceAnswer[Dept] = team
          val m: ScoreAnswer = mood
          assertEquals(u.value.value: Double, 0.95)
          assertEquals(t.choice, Dept.Billing)
          assertEquals(t.confidence.value: Double, 0.81)
          assertEquals(
            t.probabilities
              .map((k, v) => (k.value: String) -> (v.value: Double)),
            Map("Billing" -> 0.88, "Technical" -> 0.12)
          )
          assertEquals(m.nearestLevel, 1)
          assertEquals(m.levelDescription, Some("Frustrated"))
          assertEquals(m.confidence.value: Double, 0.92)

  test("the request goes to the vendor's endpoint with a bearer token"):
    for
      seen <- Ref[IO].of(Option.empty[Request[IO]])
      app = HttpApp[IO](req => seen.set(Some(req)) *> Ok(okBody))
      _ <- client(app).ask(ask, "s")
      req <- seen.get.map(_.get)
    yield
      assertEquals(req.method, Method.POST)
      assertEquals(req.uri.path, baseUri.path.concat(endpoint))
      assertEquals(
        req.headers.get(CIString("Authorization")).map(_.head.value),
        Some("Bearer sk-test")
      )
      assertEquals(
        req.contentType.map(_.mediaType),
        Some(MediaType.application.json)
      )

  test("the body sent is the vendor's wire format, with its default model"):
    // The route captures the body, so this asserts the bytes on the wire
    // rather than only what came back.
    for
      seen <- Ref[IO].of(Option.empty[Json])
      c = client(
        HttpApp[IO](req =>
          req.as[Json].flatMap(j => seen.set(Some(j))) *> Ok(okBody)
        )
      )
      _ <- c.ask(ask, "payouts failing")
      sent <- seen.get
    yield assertEquals(sent, parse(expectedBody).toOption)

  test("evaluate exposes the map-based escape hatch"):
    val c = client(respond(Ok(okBody)))
    val request = ask.request("s", c.vendor.defaultModel).toOption.get
    c.evaluate(request)
      .map: evaluation =>
        assertEquals(evaluation.usage, Usage(296, 20))
        assertEquals(evaluation.answers.size, 3)

  test("a question invalid at construction never reaches the network"):
    client(respond(IO.raiseError(new AssertionError("must not be called"))))
      .ask(Ask(Question.noul("", "", "")), "s")
      .attempt
      .map:
        case Left(Verdict4sError.Invalid(errors)) =>
          assertEquals(errors.length, 3L)
        case other => fail(s"expected Invalid, got $other")

  // -- Decoding ---------------------------------------------------------------

  test("a body that is not JSON is a decoding error, not an exception"):
    client(respond(Ok("<html>502</html>")))
      .ask(ask, "s")
      .attempt
      .map:
        case Left(e: Verdict4sError.Decoding) =>
          assert(e.getMessage.contains("not JSON"))
        case other => fail(s"expected Decoding, got $other")

  test("JSON that is not the vendor's response shape is a decoding error"):
    client(respond(Ok("""{"answers": 7}""")))
      .ask(ask, "s")
      .attempt
      .map:
        case Left(_: Verdict4sError.Decoding) => ()
        case other => fail(s"expected Decoding, got $other")

  test("an answer the questions did not ask for is a decoding error"):
    // okBody answers q00 to q02 only.
    val four = Ask(
      Question.noul("Does this convey urgency?"),
      Question.choice[Dept]("Which team should handle this?"),
      Question
        .score("How frustrated?", Seq("Calm", "Frustrated", "Very angry")),
      Question.noul("Is this a duplicate?")
    )
    client(respond(Ok(okBody)))
      .ask(four, "s")
      .attempt
      .map:
        case Left(e: Verdict4sError.Decoding) =>
          assert(e.getMessage.contains("q03"), e.getMessage)
        case other => fail(s"expected Decoding, got $other")

  // -- Error mapping ----------------------------------------------------------

  test("an error carries the vendor's request id"):
    apiErrorFrom(
      Response[IO](Status.Unauthorized)
        .withEntity("""{"error":{"message":"bad key"}}""")
        .putHeaders(Header.Raw(CIString(requestIdHeader), "req-123"))
    ).map: e =>
      assertEquals(e.status, 401)
      assertEquals(e.failure, ApiFailure.Unauthorized)
      assertEquals(e.requestId, Some("req-123"))
      assertEquals(e.details, "bad key")

  test("each status maps to its failure, 529 to Overloaded, not generic 5xx"):
    List(
      Status.UnprocessableContent -> ApiFailure.Unprocessable,
      Status.InternalServerError -> ApiFailure.Server,
      overloaded -> ApiFailure.Overloaded
    ).traverse_ : (status, failure) =>
      apiErrorFrom(failing(status)).map: e =>
        assertEquals(e.status, status.code)
        assertEquals(e.failure, failure, s"for $status")

  test("Retry-After given in seconds is parsed"):
    apiErrorFrom(
      failing(Status.TooManyRequests)
        .putHeaders(Header.Raw(CIString("Retry-After"), "2"))
    ).map(e => assertEquals(e.retryAfter.map(_.toSeconds), Some(2L)))

  test("Retry-After given as an HTTP date becomes a delay from now"):
    apiErrorFrom(
      failing(Status.ServiceUnavailable).putHeaders(
        Header.Raw(CIString("Retry-After"), "Fri, 01 Jan 2100 00:00:00 GMT")
      )
    ).map(e => assert(e.retryAfter.exists(_.toDays > 365L), e.toString))

  test("Retry-After given as an HTTP date already past means no wait"):
    apiErrorFrom(
      failing(Status.ServiceUnavailable).putHeaders(
        Header.Raw(CIString("Retry-After"), "Mon, 01 Jan 2001 00:00:00 GMT")
      )
    ).map(e => assertEquals(e.retryAfter, Some(Duration.Zero)))

  test("the error body is parsed for its message"):
    apiErrorFrom(
      Response[IO](Status.UnprocessableContent)
        .withEntity("""{"message":"bad question"}""")
    ).map(e => assertEquals(e.details, "bad question"))

  test("an unparseable error body is preserved rather than hidden"):
    // Turning this into a Decoding failure would lose the only diagnostic the
    // caller has.
    apiErrorFrom(
      Response[IO](Status.BadGateway).withEntity("<html>502 Bad Gateway</html>")
    ).map: e =>
      assertEquals(e.status, 502)
      assert(e.body.isDefined)
      assert(e.body.get.raw.asString.exists(_.contains("Bad Gateway")))

  test("a body with no message falls back to a description of the status"):
    apiErrorFrom(failing(Status.Unauthorized)).map: e =>
      assertEquals(e.details, "missing or invalid API key")

  // -- Retries ----------------------------------------------------------------

  test("500, 503, 429 and 529 are retried and the eventual success returned"):
    // The decoded answer also guards the classic http4s mistake of letting
    // the response body escape the `use` block.
    List(
      Status.InternalServerError,
      Status.ServiceUnavailable,
      Status.TooManyRequests,
      overloaded
    ).traverse_ : status =>
      for
        pair <- flaky(List.fill(2)(failing(status)))
        (client, calls) = pair
        answer <- client.ask(ask, "s")
        n <- calls.get
      yield
        assertEquals(n, 3, s"two ${status.code}s then a success is three")
        assertEquals(answer.head.value, Probability.either(0.95).toOption.get)

  test("401 and 422 are attempted exactly once"):
    List(Status.Unauthorized, Status.UnprocessableContent).traverse_ : status =>
      for
        pair <- flaky(List.fill(5)(failing(status)))
        (client, calls) = pair
        result <- client.ask(ask, "s").attempt
        n <- calls.get
      yield
        assertEquals(n, 1, s"$status must not be retried")
        assert(result.isLeft)

  test("a timed-out attempt and a decoding error are not retried"):
    val config =
      ClientConfig.default.withRetry(fastRetries).withRequestTimeout(50.millis)
    List[(String, IO[Response[IO]])](
      "timeout" -> IO.never,
      "decoding" -> Ok("<html>502</html>")
    ).traverse_ : (name, response) =>
      for
        pair <- counted(config)(_ => response)
        (client, calls) = pair
        result <- client.ask(ask, "s").attempt
        n <- calls.get
      yield
        assertEquals(n, 1, s"a $name must not be retried")
        assert(result.isLeft, s"$name: $result")

  test("retries are bounded by maxRetries"):
    for
      pair <- flaky(List.fill(10)(failing(Status.TooManyRequests)))
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
      pair <- flaky(
        List.fill(5)(failing(Status.TooManyRequests)),
        RetryPolicy.none
      )
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

  test("the final error after exhaustion carries the server's Retry-After"):
    val limited = failing(Status.TooManyRequests)
      .putHeaders(Header.Raw(CIString("Retry-After"), "1"))
    for
      pair <- flaky(List.fill(10)(limited))
      (client, _) = pair
      result <- client.ask(ask, "s").attempt
    yield result match
      case Left(e: Verdict4sError.Api) =>
        assertEquals(e.retryAfter.map(_.toSeconds), Some(1L))
      case other => fail(s"expected Api error, got $other")

  test("a Retry-After past totalTimeout returns the error without waiting"):
    // An hour's ceiling, so sleeping out the header would hang the suite
    // rather than end in a quiet timeout.
    val patient = RetryPolicy(
      maxRetries = 2,
      backoffInitial = 1.milli,
      backoffMax = 5.millis,
      jitter = 0.0,
      totalTimeout = 1.hour
    ).toOption.get
    val limited = failing(Status.ServiceUnavailable)
      .putHeaders(Header.Raw(CIString("Retry-After"), "99999999999999"))
    for
      pair <- flaky(List.fill(5)(limited), patient)
      (client, calls) = pair
      start <- IO.monotonic
      result <- client.ask(ask, "s").attempt.timeout(10.seconds)
      elapsed <- IO.monotonic.map(_ - start)
      n <- calls.get
    yield
      assertEquals(n, 1)
      assert(elapsed < 5.seconds, s"waited $elapsed")
      result match
        case Left(e: Verdict4sError.Api) =>
          assertEquals(e.status, 503)
          assert(e.retryAfter.exists(_ >= patient.totalTimeout), e.toString)
        case other => fail(s"expected an Api error, got $other")

  test("a Retry-After longer than the budget left returns the error at once"):
    // The header asks for 1s, well inside the 2s ceiling, but the first
    // attempt has already spent 1.2s of it. Waiting could only end in a
    // timeout, so the client gives up with the server's error instead.
    // Seconds, not millis, because `Retry-After` is whole seconds; the margins
    // are hundreds of milliseconds, far beyond Node's timer granularity.
    val budget = RetryPolicy(
      maxRetries = 2,
      backoffInitial = 1.milli,
      backoffMax = 5.millis,
      jitter = 0.0,
      totalTimeout = 2.seconds
    ).toOption.get
    val limited = failing(Status.TooManyRequests)
      .putHeaders(Header.Raw(CIString("Retry-After"), "1"))
    for
      pair <- counted(ClientConfig.default.withRetry(budget))(_ =>
        IO.sleep(1200.millis).as(limited)
      )
      (client, calls) = pair
      start <- IO.monotonic
      result <- client.ask(ask, "s").attempt.timeout(10.seconds)
      elapsed <- IO.monotonic.map(_ - start)
      n <- calls.get
    yield
      assertEquals(n, 1, "the one retry would have slept into the timeout")
      assert(elapsed < 1800.millis, s"waited $elapsed, into the timeout")
      result match
        case Left(e: Verdict4sError.Api) =>
          assertEquals(e.status, 429)
          assertEquals(e.retryAfter, Some(1.second))
        case other => fail(s"expected an Api error, got $other")

  test("totalTimeout ends a run of retries that would otherwise go on"):
    // Fifty 20ms backoffs would take a second; the ceiling is 100ms.
    val impatient = RetryPolicy(
      maxRetries = 50,
      backoffInitial = 20.millis,
      backoffMax = 20.millis,
      jitter = 0.0,
      totalTimeout = 100.millis
    ).toOption.get
    for
      pair <- flaky(
        List.fill(100)(failing(Status.ServiceUnavailable)),
        impatient
      )
      (client, calls) = pair
      result <- client.ask(ask, "s").attempt.timeout(10.seconds)
      n <- calls.get
    yield
      assert(n < 51, s"made $n attempts, so the ceiling never applied")
      // Usually the client sees the budget running out between attempts and
      // gives up with the server's 503; when timer slop lets a wait overrun,
      // the ceiling fires instead, as a Transport. Either way it is a
      // Verdict4sError, never a raw TimeoutException. The next test pins the
      // Transport case deterministically.
      result match
        case Left(e: Verdict4sError.Api)       => assertEquals(e.status, 503)
        case Left(e: Verdict4sError.Transport) =>
          assert(e.details.contains("totalTimeout"), e.details)
        case other => fail(s"expected a Verdict4sError, got $other")

  test("totalTimeout expiring mid-attempt fails as a transport error"):
    // Three quick 503s, then an attempt that never answers. requestTimeout is
    // the default ten seconds, so only the 200ms ceiling can end the call.
    val impatient = RetryPolicy(
      maxRetries = 50,
      backoffInitial = 1.milli,
      backoffMax = 1.milli,
      jitter = 0.0,
      totalTimeout = 200.millis
    ).toOption.get
    for
      pair <- counted(ClientConfig.default.withRetry(impatient))(n =>
        if n < 3 then IO.pure(failing(Status.ServiceUnavailable)) else IO.never
      )
      (client, calls) = pair
      result <- client.ask(ask, "s").attempt.timeout(10.seconds)
      n <- calls.get
    yield
      assertEquals(n, 4)
      result match
        case Left(e: Verdict4sError.Transport) =>
          assert(e.details.contains("totalTimeout"), e.details)
          assert(e.details.contains("200 milliseconds"), e.details)
        case other => fail(s"expected a Transport error, got $other")

  // -- Timeouts ---------------------------------------------------------------

  test("an attempt slower than requestTimeout fails as a transport error"):
    client(
      respond(IO.never),
      ClientConfig.default.withoutRetries.withRequestTimeout(50.millis)
    ).ask(ask, "s")
      .attempt
      .map:
        case Left(e: Verdict4sError.Transport) =>
          assert(e.details.contains("50 milliseconds"), e.details)
          // Told apart from the whole call's ceiling by its wording.
          assert(!e.details.contains("totalTimeout"), e.details)
        case other => fail(s"expected a Transport error, got $other")
