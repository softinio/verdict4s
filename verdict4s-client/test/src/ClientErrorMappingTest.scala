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

import cats.effect.IO
import com.softinio.verdict4s.algebra.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*

class ClientErrorMappingTest extends CatsEffectSuite:

  private val baseUri = uri"https://example.invalid"
  private val key = ApiKey.fromString("sk-test").toOption.get
  private val ask = Ask(Question.noul("urgent?"))

  private def failWith(
      status: Status,
      body: String = """{"message":"nope"}""",
      extraHeaders: Headers = Headers.empty
  ): Verdict4sClient[IO] =
    val app = HttpRoutes
      .of[IO] { case POST -> Root / "v1" / "systemone" =>
        IO.pure(
          Response[IO](status)
            .withEntity(body)
            .putHeaders(
              Header.Raw(
                org.typelevel.ci.CIString("x-typesafe-request-id"),
                "req-123"
              )
            )
            .putHeaders(extraHeaders)
        )
      }
      .orNotFound
    Verdict4sClient[IO](
      Client.fromHttpApp(app),
      key,
      baseUri,
      ClientConfig.default.withoutRetries
    )

  private def apiErrorFrom(
      client: Verdict4sClient[IO]
  ): IO[Verdict4sError.Api] =
    client
      .ask(ask, "s")
      .attempt
      .map:
        case Left(e: Verdict4sError.Api) => e
        case other => fail(s"expected Api error, got $other")

  test("401 maps to Unauthorized"):
    apiErrorFrom(failWith(Status.Unauthorized)).map: e =>
      assertEquals(e.status, 401)
      assertEquals(e.failure, ApiFailure.Unauthorized)

  test("422 maps to Unprocessable"):
    apiErrorFrom(failWith(Status.UnprocessableEntity)).map: e =>
      assertEquals(e.failure, ApiFailure.Unprocessable)

  test("429 maps to RateLimited"):
    apiErrorFrom(failWith(Status.TooManyRequests)).map: e =>
      assertEquals(e.failure, ApiFailure.RateLimited)

  test("529 maps to Overloaded, not to the generic 5xx"):
    // 529 is not a registered status, so http4s will not hand one over
    // without being asked directly.
    apiErrorFrom(failWith(Status(529))).map: e =>
      assertEquals(e.status, 529)
      assertEquals(e.failure, ApiFailure.Overloaded)

  test("500 maps to Server"):
    apiErrorFrom(failWith(Status.InternalServerError)).map: e =>
      assertEquals(e.failure, ApiFailure.Server)

  test("the request id is carried through for support"):
    apiErrorFrom(failWith(Status.Unauthorized)).map: e =>
      assertEquals(e.requestId, Some("req-123"))

  test("Retry-After given in seconds is parsed"):
    val headers = Headers(
      Header.Raw(org.typelevel.ci.CIString("Retry-After"), "2")
    )
    apiErrorFrom(failWith(Status.TooManyRequests, extraHeaders = headers)).map:
      e => assertEquals(e.retryAfter.map(_.toSeconds), Some(2L))

  test("the error body is parsed for its message"):
    apiErrorFrom(
      failWith(Status.UnprocessableEntity, """{"message":"bad question"}""")
    ).map: e =>
      assertEquals(e.details, "bad question")

  test("an unparseable error body is preserved rather than hidden"):
    // Turning this into a Decoding failure would lose the only diagnostic the
    // caller has.
    apiErrorFrom(failWith(Status.BadGateway, "<html>502 Bad Gateway</html>"))
      .map: e =>
        assertEquals(e.status, 502)
        assert(e.body.isDefined)
        assert(e.body.get.raw.asString.exists(_.contains("Bad Gateway")))

  test("a body with no message falls back to a description of the status"):
    apiErrorFrom(failWith(Status.Unauthorized, "{}")).map: e =>
      assertEquals(e.details, "missing or invalid API key")
