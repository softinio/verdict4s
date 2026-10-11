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
import com.softinio.verdict4s.openai.OpenAIVendor
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.typelevel.ci.CIString

/** What only the OpenAI client does: refusals, the safety identifier, its
  * stricter validation and its millisecond retry hint.
  */
class OpenAIClientTest extends CatsEffectSuite:

  private val key = ApiKey.fromString("sk-test").toOption.get

  private def answersBody(answers: String): String = s"""
    {"model": "gpt-6-luna", "answers": [$answers],
     "usage": {"input_tokens": 1, "input_tokens_details": {"cached_tokens": 0, "cache_write_tokens": 0},
               "output_tokens": 0, "output_tokens_details": {"reasoning_tokens": 0},
               "total_tokens": 1}}
  """

  private def clientFor(app: HttpApp[IO]): OpenAIClient[IO] =
    Verdict4sClient
      .openAI[IO](Client.fromHttpApp(app), key, uri"https://example.invalid/v1")
      .withJitter(Jitter.constant[IO](0.5))

  private def recording(
      body: String
  ): IO[(OpenAIClient[IO], Ref[IO, List[Json]])] =
    Ref[IO]
      .of(List.empty[Json])
      .map: seen =>
        clientFor(
          HttpApp[IO](req =>
            req.as[Json].flatMap(j => seen.update(_ :+ j)) *> Ok(body)
          )
        ) -> seen

  private val urgency = Question.noul("Is this urgent?")
  private val abusive = Question.noul("Is this abusive?")

  test("a refusal fails the ask with Refused, keeping the other answers"):
    val body = answersBody(
      """{"type": "predicate", "name": "q00", "probability": 0.9},
         {"type": "refusal", "name": "q01"}"""
    )
    clientFor(HttpApp[IO](_ => Ok(body)))
      .ask(Ask(urgency, abusive), "s")
      .attempt
      .map:
        case Left(Verdict4sError.Refused(keys, evaluation)) =>
          assertEquals(keys.toList.map(k => k.value: String), List("q01"))
          assertEquals(evaluation.answers.size, 2)
        case other => fail(s"expected Refused, got $other")

  test("orRefused turns the refusal into None"):
    val body = answersBody(
      """{"type": "predicate", "name": "q00", "probability": 0.9},
         {"type": "refusal", "name": "q01"}"""
    )
    clientFor(HttpApp[IO](_ => Ok(body)))
      .ask(Ask(urgency, abusive.orRefused), "s")
      .map:
        case (u, a) =>
          assert(u.isTrue())
          assertEquals(a, None)

  test("the safety identifier is sent with every request"):
    for
      rec <- recording(
        answersBody(
          """{"type": "predicate", "name": "q00", "probability": 0.9}"""
        )
      )
      (client, seen) = rec
      tagged = client.withSafetyIdentifier("user-42")
      _ <- tagged.ask(Ask(urgency), "s")
      _ <- tagged.withConfig(_.withoutRetries).ask(Ask(urgency), "s")
      bodies <- seen.get
    yield
      assertEquals(tagged.safetyIdentifier, Some("user-42"))
      assertEquals(
        bodies.map(
          _.hcursor.downField("safety_identifier").as[String].toOption
        ),
        List(Some("user-42"), Some("user-42"))
      )

  test("a request OpenAI would reject is never sent"):
    for
      rec <- recording(answersBody(""))
      (client, seen) = rec
      result <- client
        .ask(Ask(Question.noul("Urgent?", "on fire", "can wait")), "s")
        .attempt
      bodies <- seen.get
    yield
      result match
        case Left(Verdict4sError.Invalid(errors)) =>
          assertEquals(errors.head.field, "questions.q00.criteria")
        case other => fail(s"expected Invalid, got $other")
      assertEquals(bodies, Nil)

  test("the request names every question and carries gpt-6-luna"):
    for
      rec <- recording(
        answersBody(
          """{"type": "predicate", "name": "q00", "probability": 0.9},
             {"type": "predicate", "name": "q01", "probability": 0.1}"""
        )
      )
      (client, seen) = rec
      _ <- client.ask(Ask(urgency, abusive), "ticket text")
      body <- seen.get.map(_.head)
    yield
      assertEquals(
        body.hcursor.downField("model").as[String],
        Right("gpt-6-luna")
      )
      assertEquals(
        body.hcursor.downField("input").as[String],
        Right("ticket text")
      )
      assertEquals(
        body.hcursor
          .downField("questions")
          .values
          .map(
            _.toList.map(q => q.hcursor.downField("name").as[String].toOption)
          ),
        Some(List(Some("q00"), Some("q01")))
      )

  test("retry-after-ms is honoured ahead of Retry-After"):
    val body = answersBody(
      """{"type": "predicate", "name": "q00", "probability": 0.9}"""
    )
    val policy = RetryPolicy(
      maxRetries = 1,
      backoffInitial = 1.milli,
      backoffMax = 1.milli
    ).toOption.get
    for
      calls <- Ref[IO].of(0)
      app = HttpApp[IO](_ =>
        calls
          .getAndUpdate(_ + 1)
          .flatMap: n =>
            if n == 0 then
              IO.pure(
                Response[IO](Status.TooManyRequests).putHeaders(
                  Header.Raw(CIString("retry-after-ms"), "5"),
                  Header.Raw(CIString("Retry-After"), "600")
                )
              )
            else Ok(body)
      )
      start <- IO.monotonic
      _ <- clientFor(app).withConfig(_.withRetry(policy)).ask(Ask(urgency), "s")
      elapsed <- IO.monotonic.map(_ - start)
      n <- calls.get
    yield
      assertEquals(n, 2)
      assert(elapsed < 30.seconds, s"waited $elapsed, so Retry-After won")

  test("an exhausted credit balance is not retried, though it is a 429"):
    // The body is verbatim what the live service returned for an account with
    // no credits.
    val quota =
      """{"error":{"message":"You have no credits remaining.","type":"insufficient_quota","param":null,"code":"credit_balance_exhausted"}}"""
    val policy = RetryPolicy(
      maxRetries = 2,
      backoffInitial = 1.milli,
      backoffMax = 1.milli
    ).toOption.get
    for
      calls <- Ref[IO].of(0)
      app = HttpApp[IO](_ =>
        calls.update(_ + 1) *> IO.pure(
          Response[IO](Status.TooManyRequests).withEntity(quota)
        )
      )
      result <- clientFor(app)
        .withConfig(_.withRetry(policy))
        .ask(Ask(urgency), "s")
        .attempt
      n <- calls.get
    yield
      assertEquals(n, 1)
      result match
        case Left(e: Verdict4sError.Api) =>
          assertEquals(e.failure, ApiFailure.RateLimited)
          assertEquals(e.body.flatMap(_.kind), Some("insufficient_quota"))
        case other => fail(s"expected an Api error, got $other")

  test("an ordinary 429 is still retried"):
    val body = answersBody(
      """{"type": "predicate", "name": "q00", "probability": 0.9}"""
    )
    val limited =
      """{"error":{"message":"Rate limit reached","type":"requests","param":null,"code":"rate_limit_exceeded"}}"""
    val policy = RetryPolicy(
      maxRetries = 1,
      backoffInitial = 1.milli,
      backoffMax = 1.milli
    ).toOption.get
    for
      calls <- Ref[IO].of(0)
      app = HttpApp[IO](_ =>
        calls
          .getAndUpdate(_ + 1)
          .flatMap(n =>
            if n == 0 then
              IO.pure(Response[IO](Status.TooManyRequests).withEntity(limited))
            else Ok(body)
          )
      )
      _ <- clientFor(app).withConfig(_.withRetry(policy)).ask(Ask(urgency), "s")
      n <- calls.get
    yield assertEquals(n, 2)

  test("Verdict4sClient.of picks the vendor's own client"):
    val http = Client.fromHttpApp(HttpApp.notFound[IO])
    assert(
      Verdict4sClient
        .of[IO](http, Vendor.OpenAI, key)
        .isInstanceOf[OpenAIClient[IO]]
    )
    assert(
      Verdict4sClient.of[IO](http, Vendor.Jev, key).isInstanceOf[JevClient[IO]]
    )
    val custom = new Vendor:
      def name = "Custom"
      def defaultBaseUrl = "https://decisions.example.com"
      def defaultModel = Model.JevLatest
      def evaluatePath = List("decide")
      def requestIdHeader = "x-request-id"
      def encode(request: EvaluationRequest) = Vendor.Jev.encode(request)
      def decode(body: Json) = Vendor.Jev.decode(body)
    val generic = Verdict4sClient.of[IO](http, custom, key)
    assert(!generic.isInstanceOf[JevClient[IO]])
    assertEquals(generic.vendor.name, "Custom")

  test("capabilities compose with intersection types"):
    def catalogued(c: Verdict4sClient[IO] & ModelCatalog[IO]) = c.vendor.name
    def tagged(c: Verdict4sClient[IO] & SafetyIdentity[IO]) = c.safetyIdentifier
    val http = Client.fromHttpApp(HttpApp.notFound[IO])
    assertEquals(catalogued(Verdict4sClient.jev[IO](http, key)), "Jev")
    assertEquals(tagged(Verdict4sClient.openAI[IO](http, key)), None)
    assertEquals(
      compileErrors(
        "catalogued(Verdict4sClient.openAI[IO](http, key))"
      ).nonEmpty,
      true
    )

  test("the default vendor instance carries no safety identifier"):
    assertEquals(Vendor.OpenAI, OpenAIVendor())
