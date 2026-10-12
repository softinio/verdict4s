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
import cats.effect.Ref
import com.softinio.verdict4s.algebra.*
import com.softinio.verdict4s.openai.OpenAIVendor
import io.circe.Json
import org.http4s.*
import org.http4s.Uri.Path
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.typelevel.ci.CIString

/** The contract, over a fake speaking Jev. */
class JevContractTest extends VendorContractSuite:

  def clientFor(app: HttpApp[IO], config: ClientConfig): Verdict4sClient[IO] =
    Verdict4sClient.jev[IO](Client.fromHttpApp(app), key, baseUri, config)

  def endpoint: Path = path"/v1/systemone"

  def requestIdHeader: String = "x-typesafe-request-id"

  def okBody: String = """
    {
      "model": "jev-1.13.0",
      "answers": {
        "q00": {"type": "noul", "noul": 0.95},
        "q01": {"type": "choice", "choice": "Billing",
                "probabilities": {"Billing": 0.88, "Technical": 0.12},
                "confidence": 0.81},
        "q02": {"type": "score", "score": 1.05,
                "legend": {"0": "Calm", "1": "Frustrated", "2": "Very angry"},
                "probabilities": {"0": 0.0, "1": 0.95, "2": 0.05},
                "confidence": 0.92}
      },
      "usage": {"input_tokens": 296, "output_tokens": 20}
    }
  """

  def expectedBody: String = """
    {
      "state": "payouts failing",
      "model": "jev-latest",
      "questions": {
        "q00": {"type": "noul", "instructions": "Does this convey urgency?"},
        "q01": {"type": "choice",
                "instructions": "Which team should handle this?",
                "criteria": {"Billing": null, "Technical": null}},
        "q02": {"type": "score", "instructions": "How frustrated?",
                "criteria": ["Calm", "Frustrated", "Very angry"]}
      }
    }
  """

  test("models decodes the list endpoint"):
    val app = HttpRoutes
      .of[IO] { case GET -> Root / "base" / "v1" / "models" =>
        // The envelope the live endpoint actually returns. This fake served a
        // bare array once, which is precisely why the mismatch survived until
        // the integration suite ran.
        Ok(
          """{"models":[{"name":"jev-latest","description":"alias","release_date":"2025-06-01"}]}"""
        )
      }
      .orNotFound
    Verdict4sClient
      .jev[IO](Client.fromHttpApp(app), key, baseUri)
      .models
      .map: cards =>
        assertEquals(cards.map(_.name.name), List("jev-latest"))

  test("http4s redacts the key, so it cannot reach a log by accident"):
    val request =
      com.softinio.verdict4s.internal.Wire
        .post[IO](uri"https://x/y", key, Json.obj())
    assert(!request.toString.contains("sk-test"), request.toString)

/** The contract, over a fake speaking OpenAI Decisions, whose base URL carries
  * `/v1` itself; then what only the OpenAI client does: refusals, the safety
  * identifier, its stricter validation and its millisecond retry hint.
  */
class OpenAIContractTest extends VendorContractSuite:

  def clientFor(app: HttpApp[IO], config: ClientConfig): Verdict4sClient[IO] =
    Verdict4sClient.openAI[IO](Client.fromHttpApp(app), key, baseUri, config)

  def endpoint: Path = path"/decisions"

  def requestIdHeader: String = "x-request-id"

  def okBody: String = """
    {
      "model": "gpt-6-luna",
      "answers": [
        {"type": "predicate", "name": "q00", "probability": 0.95},
        {"type": "choice", "name": "q01", "choice": "Billing",
         "probabilities": [{"value": "Billing", "probability": 0.88},
                           {"value": "Technical", "probability": 0.12}],
         "confidence": 0.81},
        {"type": "score", "name": "q02", "score": 1.05,
         "probabilities": [{"value": 0, "label": "Calm", "probability": 0.0},
                           {"value": 1, "label": "Frustrated", "probability": 0.95},
                           {"value": 2, "label": "Very angry", "probability": 0.05}],
         "confidence": 0.92}
      ],
      "usage": {"input_tokens": 296, "input_tokens_details": {"cached_tokens": 0, "cache_write_tokens": 0},
                "output_tokens": 20, "output_tokens_details": {"reasoning_tokens": 0},
                "total_tokens": 316}
    }
  """

  def expectedBody: String = """
    {
      "model": "gpt-6-luna",
      "input": "payouts failing",
      "questions": [
        {"type": "predicate", "name": "q00",
         "instructions": "Does this convey urgency?"},
        {"type": "choice", "name": "q01",
         "instructions": "Which team should handle this?",
         "choices": [{"value": "Billing"}, {"value": "Technical"}]},
        {"type": "score", "name": "q02", "instructions": "How frustrated?",
         "levels": [{"label": "Calm"}, {"label": "Frustrated"},
                    {"label": "Very angry"}]}
      ]
    }
  """

  private def answersBody(answers: String): String = s"""
    {"model": "gpt-6-luna", "answers": [$answers],
     "usage": {"input_tokens": 1, "input_tokens_details": {"cached_tokens": 0, "cache_write_tokens": 0},
               "output_tokens": 0, "output_tokens_details": {"reasoning_tokens": 0},
               "total_tokens": 1}}
  """

  private def openAIClient(app: HttpApp[IO]): OpenAIClient[IO] =
    Verdict4sClient.openAI[IO](Client.fromHttpApp(app), key, baseUri)

  private def recording(
      body: String
  ): IO[(OpenAIClient[IO], Ref[IO, List[Json]])] =
    Ref[IO]
      .of(List.empty[Json])
      .map: seen =>
        openAIClient(
          HttpApp[IO](req =>
            req.as[Json].flatMap(j => seen.update(_ :+ j)) *> Ok(body)
          )
        ) -> seen

  private val urgency = Question.noul("Is this urgent?")
  private val abusive = Question.noul("Is this abusive?")

  private val refusing = respond(
    Ok(
      answersBody(
        """{"type": "predicate", "name": "q00", "probability": 0.9},
           {"type": "refusal", "name": "q01"}"""
      )
    )
  )

  test("a refusal fails the ask with Refused, keeping the other answers"):
    client(refusing)
      .ask(Ask(urgency, abusive), "s")
      .attempt
      .map:
        case Left(Verdict4sError.Refused(keys, evaluation)) =>
          assertEquals(keys.toList.map(k => k.value: String), List("q01"))
          assertEquals(evaluation.answers.size, 2)
        case other => fail(s"expected Refused, got $other")

  test("orRefused turns the refusal into None"):
    client(refusing)
      .ask(Ask(urgency, abusive.orRefused), "s")
      .map:
        case (u, a) =>
          assert(u.isTrue())
          assertEquals(a, None)

  test("the safety identifier is sent with every request"):
    for
      rec <- recording(okBody)
      (client, seen) = rec
      tagged = client.withSafetyIdentifier("user-42")
      _ <- tagged.ask(ask, "s")
      _ <- tagged.withConfig(_.withoutRetries).ask(ask, "s")
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
      rec <- recording(okBody)
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

  test("retry-after-ms is honoured ahead of Retry-After"):
    val limited = Response[IO](Status.TooManyRequests).putHeaders(
      Header.Raw(CIString("retry-after-ms"), "5"),
      Header.Raw(CIString("Retry-After"), "600")
    )
    for
      pair <- flaky(List(limited))
      (client, calls) = pair
      _ <- client.ask(ask, "s")
      n <- calls.get
    // A winning Retry-After of 600s is past totalTimeout, so the client would
    // give up after one attempt; a second attempt means retry-after-ms won.
    yield assertEquals(n, 2, "Retry-After won")

  test("an exhausted credit balance is not retried, though it is a 429"):
    // The body is verbatim what the live service returned for an account with
    // no credits.
    val quota =
      """{"error":{"message":"You have no credits remaining.","type":"insufficient_quota","param":null,"code":"credit_balance_exhausted"}}"""
    for
      pair <- flaky(
        List.fill(5)(Response[IO](Status.TooManyRequests).withEntity(quota))
      )
      (client, calls) = pair
      result <- client.ask(ask, "s").attempt
      n <- calls.get
    yield
      assertEquals(n, 1)
      result match
        case Left(e: Verdict4sError.Api) =>
          assertEquals(e.failure, ApiFailure.RateLimited)
          assertEquals(e.body.flatMap(_.kind), Some("insufficient_quota"))
        case other => fail(s"expected an Api error, got $other")

  test("an ordinary 429 is still retried"):
    val limited =
      """{"error":{"message":"Rate limit reached","type":"requests","param":null,"code":"rate_limit_exceeded"}}"""
    for
      pair <- flaky(
        List(Response[IO](Status.TooManyRequests).withEntity(limited))
      )
      (client, calls) = pair
      _ <- client.ask(ask, "s")
      n <- calls.get
    yield assertEquals(n, 2)

  test("the default vendor instance carries no safety identifier"):
    assertEquals(Vendor.OpenAI, OpenAIVendor())
