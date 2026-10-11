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
import com.softinio.verdict4s.algebra.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.Uri.Path
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
    * and score 1.05 over Calm / Frustrated / Very angry (0.92), as q00 to q02.
    */
  def okBody: String

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

  test("an error carries the vendor's request id"):
    client(
      respond(
        IO.pure(
          Response[IO](Status.Unauthorized)
            .withEntity("""{"error":{"message":"bad key"}}""")
            .putHeaders(Header.Raw(CIString(requestIdHeader), "req-123"))
        )
      ),
      ClientConfig.default.withoutRetries
    ).ask(ask, "s")
      .attempt
      .map:
        case Left(e: Verdict4sError.Api) =>
          assertEquals(e.failure, ApiFailure.Unauthorized)
          assertEquals(e.requestId, Some("req-123"))
          assertEquals(e.details, "bad key")
        case other => fail(s"expected an Api error, got $other")

  test("a retryable status is retried, then succeeds"):
    for
      calls <- Ref[IO].of(0)
      app = HttpApp[IO](_ =>
        calls
          .getAndUpdate(_ + 1)
          .flatMap(n => if n == 0 then ServiceUnavailable() else Ok(okBody))
      )
      policy = RetryPolicy(
        maxRetries = 1,
        backoffInitial = 1.milli,
        backoffMax = 1.milli
      ).toOption.get
      _ <- client(app, ClientConfig.default.withRetry(policy)).ask(ask, "s")
      n <- calls.get
    yield assertEquals(n, 2)

  test("an attempt slower than requestTimeout fails as a transport error"):
    client(
      respond(IO.never),
      ClientConfig.default.withoutRetries.withRequestTimeout(50.millis)
    ).ask(ask, "s")
      .attempt
      .map:
        case Left(e: Verdict4sError.Transport) =>
          assert(e.details.contains("50 milliseconds"), e.details)
        case other => fail(s"expected a Transport error, got $other")

  test("the model defaults to the vendor's own"):
    assertEquals(
      client(respond(Ok(okBody))).config.model,
      None
    )
