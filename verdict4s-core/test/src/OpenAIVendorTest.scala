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

package com.softinio.verdict4s.openai

import scala.collection.immutable.SortedMap

import com.softinio.verdict4s.Ask
import com.softinio.verdict4s.Vendor
import com.softinio.verdict4s.Verdict4sError
import com.softinio.verdict4s.algebra.*
import io.circe.Json
import io.github.iltotore.iron.*

/** What OpenAI Decisions accepts, and how its errors read.
  *
  * Each rule here is a request Jev would take and OpenAI would reject a round
  * trip later; validating them up front reports all of them at once instead.
  */
class OpenAIVendorTest extends munit.FunSuite:

  private val openAI = Vendor.OpenAI

  private def problems[Q <: Tuple](
      ask: Ask[Q],
      vendor: Vendor = openAI
  ): List[String] =
    vendor.renderAsk(ask, "s") match
      case Left(Verdict4sError.Invalid(errors)) =>
        errors.toNonEmptyList.toList.map(_.field)
      case Left(other) => fail(s"expected Invalid, got $other")
      case Right(_)    => Nil

  test("the endpoint includes /v1 in the base URL, as OpenAI's SDKs do"):
    assertEquals(openAI.defaultBaseUrl, "https://api.openai.com/v1")
    assertEquals(openAI.evaluateUrl, "https://api.openai.com/v1/decisions")

  test("the default model is gpt-6-luna"):
    assertEquals(openAI.defaultModel, Model.GptLuna)

  test("a choice needs at least two options"):
    val one = Ask(Question.choiceOfStrings("Only one?", Seq("only" -> None)))
    assertEquals(problems(one), List("questions.q00.options"))
    assertEquals(problems(one, Vendor.Jev), Nil)

  test("noul criteria are rejected rather than silently dropped"):
    val described =
      Ask(Question.noul("Urgent?", "it is on fire", "it can wait"))
    assertEquals(problems(described), List("questions.q00.criteria"))

  test("a noul without criteria is fine"):
    assertEquals(problems(Ask(Question.noul("Urgent?"))), Nil)

  test("a request carries at most 200 questions"):
    def request(count: Int): EvaluationRequest =
      val noul =
        QuestionSpec.Noul(Instructions.text("urgent?").toOption.get, None)
      EvaluationRequest(
        Json.fromString("s"),
        Model.GptLuna,
        SortedMap
          .from(
            (0 until count).map(i =>
              QuestionKey.either(Ask.defaultKey(i)).toOption.get -> noul
            )
          )
          .refineUnsafe[QuestionsC]
      )
    assert(openAI.validate(request(200)).isValid)
    assertEquals(
      openAI
        .validate(request(201))
        .toEither
        .left
        .map(_.toNonEmptyList.toList.map(_.field)),
      Left(List("questions"))
    )
    assert(Vendor.Jev.validate(request(201)).isValid)

  test("every problem is reported at once"):
    val ask = Ask(
      Question.noul("Urgent?", "yes", "no"),
      Question.choiceOfStrings("Only one?", Seq("only" -> None))
    )
    assertEquals(
      problems(ask),
      List("questions.q00.criteria", "questions.q01.options")
    )

  test("a safety identifier must be non-blank and at most 128 characters"):
    val ask = Ask(Question.noul("Urgent?"))
    assertEquals(
      problems(ask, openAI.withSafetyIdentifier(" ")),
      List("safety_identifier")
    )
    assertEquals(
      problems(ask, openAI.withSafetyIdentifier("x" * 129)),
      List("safety_identifier")
    )
    assertEquals(problems(ask, openAI.withSafetyIdentifier("x" * 128)), Nil)

  test("parseError reads OpenAI's error body and x-request-id"):
    val err = openAI.parseError(
      429,
      """{"error":{"message":"Rate limit reached","type":"requests","param":null,"code":"rate_limit_exceeded"}}""",
      Map(
        "X-Request-Id" -> "req_abc",
        "retry-after-ms" -> "1500",
        "Retry-After" -> "9"
      )
    )
    assertEquals(err.failure, ApiFailure.RateLimited)
    assertEquals(err.details, "Rate limit reached")
    assertEquals(err.requestId, Some("req_abc"))
    assertEquals(err.retryAfter.map(_.toMillis), Some(1500L))

  test("without retry-after-ms, Retry-After seconds still apply"):
    val err = openAI.parseError(503, "", Map("Retry-After" -> "3"))
    assertEquals(err.retryAfter.map(_.toSeconds), Some(3L))

  test("Jev ignores retry-after-ms, which it never sends"):
    val err = Vendor.Jev.parseError(429, "", Map("retry-after-ms" -> "1500"))
    assertEquals(err.retryAfter, None)
