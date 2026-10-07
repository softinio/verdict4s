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
import com.softinio.verdict4s.algebra.*
import com.softinio.verdict4s.openai.OpenAIVendor
import munit.CatsEffectSuite

/** Integration tests against the real OpenAI Decisions service.
  *
  * Gated on `OPENAI_API_KEY`, exactly as [[LiveApiIT]] is on
  * `TYPESAFE_API_KEY`: with no key the suite reports as ignored and makes no
  * network call.
  *
  * The OpenAI codecs were written from the published OpenAPI spec, and the
  * in-memory fakes agree with that spec by construction. Only this suite shows
  * that the service agrees too, so run it before every release.
  *
  * Assertions are on *shape*, never on model output.
  */
class LiveOpenAIIT extends CatsEffectSuite:

  private val key: Option[ApiKey] =
    sys.env
      .get(OpenAIVendor.ApiKeyEnv)
      .filterNot(_.isBlank)
      .flatMap(ApiKey.fromString(_).toOption)

  override def munitIgnore: Boolean = key.isEmpty
  override def munitIOTimeout: Duration = 60.seconds

  /** Runs `f`, failing with a clear message when the account has no credits.
    *
    * This used to skip instead, but Mill reports a skipped munit test as a
    * pass, so a run without credits looked green while checking no answers. The
    * suite exists to be run deliberately with a funded key; a run that cannot
    * do that should say so.
    */
  private def withClient[A](f: OpenAIClient[IO] => IO[A]): IO[A] =
    Verdict4s
      .openAI[IO](key.get)
      .use(f)
      .recoverWith:
        case e: Verdict4sError.Api
            if e.body.flatMap(_.kind).contains("insufficient_quota") =>
          IO(
            fail(
              s"the account has no credits, so answers could not be checked: ${e.details}",
              e
            )
          )

  enum Dept derives Options:
    case Billing, Technical, Sales

  private val ticket = "Help! My payouts have been failing for 3 days."

  test(
    "the service accepts every question shape exactly as verdict4s renders it"
  ):
    // OpenAI checks a body against its schema before it checks the account's
    // credit, so this holds even for an account with none: anything but a 400
    // means the request was well formed. Covers a rubric and a bare option,
    // levels, structured state and the safety identifier.
    val ask = Ask(
      Question.noul("Does this convey urgency?"),
      Question.choiceOfStrings(
        "Which team should handle this?",
        Seq(
          "Billing" -> Instructions
            .text("Payments, invoices and refunds")
            .toOption,
          "Technical" -> None
        )
      ),
      Question.score("How frustrated?", Seq("Calm", "Frustrated", "Very angry"))
    )
    val request = ask
      .request(
        io.circe.Json.obj("ticket" -> io.circe.Json.fromString(ticket)),
        Model.GptLuna
      )
      .toOption
      .get
    Verdict4s
      .openAI[IO](key.get)
      .use(_.withSafetyIdentifier("verdict4s-live-test").evaluate(request))
      .attempt
      .map:
        case Right(evaluation) => assertEquals(evaluation.answers.size, 3)
        case Left(e: Verdict4sError.Api) =>
          assert(
            e.failure != ApiFailure.BadRequest && e.failure != ApiFailure.Unprocessable,
            s"the service rejected the request: ${e.details}"
          )
        case Left(other) => fail(s"unexpected failure: $other")

  test("the service rejects what validate rejects, so the rule is real"):
    // Sent raw, past verdict4s' own validation, to check the rule against the
    // service rather than against the spec it was read from.
    val one = QuestionSpec.Choice(
      Instructions.text("Pick").toOption.get,
      Options.strings(Seq("only" -> None)).toOption.get.keys
    )
    import io.github.iltotore.iron.*
    val request = EvaluationRequest(
      io.circe.Json.fromString(ticket),
      Model.GptLuna,
      scala.collection.immutable
        .SortedMap(QuestionKey.either("q00").toOption.get -> one)
        .refineUnsafe[QuestionsC]
    )
    val body = Vendor.OpenAI.render(request)
    IO.blocking:
      val http = java.net.http.HttpClient.newHttpClient()
      val req = java.net.http.HttpRequest
        .newBuilder(java.net.URI.create(Vendor.OpenAI.evaluateUrl))
        .header("Authorization", Vendor.OpenAI.authorization(key.get))
        .header("Content-Type", "application/json")
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
        .build()
      http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
    .map: response =>
        assertEquals(response.statusCode, 400, response.body)
        val err = Vendor.OpenAI.parseError(response.statusCode, response.body)
        assertEquals(err.body.flatMap(_.field), Some("questions[0].choices"))

  // `orRefused` throughout: a refusal is the service's right, and these tests
  // are about the wire format, not about whether this ticket gets answered.

  test("a predicate comes back in range"):
    withClient(
      _.ask(Ask(Question.noul("Does this convey urgency?").orRefused), ticket)
    )
      .map: answers =>
        answers.head.foreach: a =>
          val value: Double = a.value.value
          assert(
            value >= 0.0 && value <= 1.0,
            s"probability out of range: $value"
          )

  test("a choice answer is one of the options we sent"):
    withClient(
      _.ask(
        Ask(Question.choice[Dept]("Which team should handle this?").orRefused),
        ticket
      )
    ).map: answers =>
      answers.head.foreach: answer =>
        assert(Dept.values.contains(answer.choice))
        assertEquals(
          answer.probabilities.keySet.map(k => k.value: String),
          Dept.values.map(_.toString).toSet
        )

  test("a score answer lands within the levels we defined, with a legend"):
    val levels = Seq("Calm", "Frustrated", "Very angry")
    withClient(
      _.ask(
        Ask(
          Question.score("How frustrated is the customer?", levels).orRefused
        ),
        ticket
      )
    ).map: answers =>
      answers.head.foreach: answer =>
        assert(
          answer.score >= 0.0 && answer.score <= 2.0,
          s"score was ${answer.score}"
        )
        assertEquals(answer.legend.values.toList, levels.toList)

  test("several questions in one request all come back, exactly typed"):
    val ask = Ask(
      Question.noul("Does this convey urgency?").orRefused,
      Question.choice[Dept]("Which team should handle this?").orRefused,
      Question
        .score("How frustrated?", Seq("Calm", "Frustrated", "Very angry"))
        .orRefused
    )
    withClient(_.ask(ask, ticket)).map:
      case (urgency, dept, mood) =>
        val u: Option[NoulAnswer] = urgency
        val d: Option[ChoiceAnswer[Dept]] = dept
        val m: Option[ScoreAnswer] = mood
        assert(d.forall(a => Dept.values.contains(a.choice)))
        assert(m.forall(_.legend.nonEmpty))
        assert(u.forall(a => (a.value.value: Double) >= 0.0))
        // Refusals are allowed, but three of three would mean this test
        // checked no decoding at all.
        assert(
          u.isDefined || d.isDefined || m.isDefined,
          "every question was refused"
        )

  test("model and usage are reported for a request"):
    val request = Ask(Question.noul("urgent?"))
      .request(ticket, Model.GptLuna)
      .toOption
      .get
    withClient(_.evaluate(request)).map: evaluation =>
      assert(evaluation.model.name.nonEmpty)
      assert(evaluation.usage.inputTokens > 0, "input tokens should be counted")

  test("a safety identifier is accepted"):
    withClient(
      _.withSafetyIdentifier("verdict4s-live-test")
        .ask(Ask(Question.noul("urgent?").orRefused), ticket)
    ).map(answers => assert(answers.head.forall(_.value.value <= 1.0)))

  test("a bad key is reported as Unauthorized, with a request id"):
    val bogus = ApiKey.fromString("sk-definitely-not-a-real-key").toOption.get
    Verdict4s
      .openAI[IO](bogus)
      .use(_.ask(Ask(Question.noul("urgent?")), ticket))
      .attempt
      .map:
        case Left(e: Verdict4sError.Api) =>
          assertEquals(e.failure, ApiFailure.Unauthorized)
          assert(e.requestId.isDefined, "x-request-id should be read")
        case other => fail(s"expected an Unauthorized Api error, got $other")
