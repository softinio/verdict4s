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

import scala.collection.immutable.SortedMap

import com.softinio.verdict4s.algebra.*
import io.circe.Json
import io.circe.syntax.*

/** The tuple builder.
  *
  * The real assertion in several of these is the *destructuring*: if
  * `Tuple.InverseMap` stopped reducing, `val Right((a, b, c)) = ...` would not
  * compile, and the type ascriptions inside would fail. The runtime checks are
  * secondary.
  */
class AskTest extends munit.FunSuite:

  private def p(d: Double): Probability = Probability.either(d).toOption.get
  private def c(d: Double): Confidence = Confidence.either(d).toOption.get
  private def n(s: String): OptionName = OptionName.either(s).toOption.get
  private def k(s: String): QuestionKey = QuestionKey.either(s).toOption.get

  enum Dept derives Options:
    case Billing, Technical, Sales

  final case class Route(team: Dept, escalate: Boolean)

  private val urgency = Question.noul("Does this convey urgency?")
  private val team = Question.choice[Dept]("Which team should handle this?")
  private val frustration =
    Question.score("How frustrated?", Seq("Calm", "Frustrated", "Very angry"))

  private def evaluation(answers: (String, Answer)*): Evaluation =
    Evaluation(
      Model.JevLatest,
      SortedMap.from(answers.map((key, a) => k(key) -> a)),
      Usage(10, 2)
    )

  private val noulAnswer = Answer.Noul(p(0.95))
  private val choiceAnswer = Answer.Choice(
    n("Billing"),
    SortedMap(n("Billing") -> p(0.88), n("Technical") -> p(0.12)),
    c(0.81)
  )
  private val scoreAnswer = Answer.Score(
    1.05,
    SortedMap(0 -> "Calm", 1 -> "Frustrated", 2 -> "Very angry"),
    SortedMap(0 -> p(0.0), 1 -> p(0.95), 2 -> p(0.05)),
    c(0.92)
  )

  test("answers project back at exactly the right positional types"):
    val ask = Ask(urgency, team, frustration)
    val result = ask.answers(
      evaluation(
        "q00" -> noulAnswer,
        "q01" -> choiceAnswer,
        "q02" -> scoreAnswer
      )
    )
    result match
      case Right((u, d, f)) =>
        // These ascriptions are the assertion: they only compile if
        // Tuple.InverseMap reduced to (NoulAnswer, ChoiceAnswer[Dept], ScoreAnswer).
        val urgent: NoulAnswer = u
        val dept: ChoiceAnswer[Dept] = d
        val mood: ScoreAnswer = f
        assertEquals(urgent.value, p(0.95))
        assertEquals(dept.choice, Dept.Billing)
        assertEquals(mood.nearestLevel, 1)
      case Left(e) => fail(s"projection failed: $e")

  test("a mapped question puts the caller's own type in the tuple"):
    val routed = team.map(a => Route(a.choice, escalate = false))
    Ask(urgency, routed).answers(
      evaluation("q00" -> noulAnswer, "q01" -> choiceAnswer)
    ) match
      case Right((_, r)) =>
        val route: Route = r
        assertEquals(route, Route(Dept.Billing, escalate = false))
      case Left(e) => fail(s"projection failed: $e")

  test("keys are generated positionally and zero padded"):
    assertEquals(
      (0 to 12).map(Ask.defaultKey).take(3),
      Seq("q00", "q01", "q02")
    )
    val keys = (0 to 12).map(Ask.defaultKey)
    assertEquals(keys.sorted, keys, "lexicographic order must match positional")

  test("the encoded request uses those keys"):
    val req = Ask(urgency, team)
      .request("Help! My payouts have been failing.", Model.JevLatest)
      .toOption
      .get
    assertEquals(
      req.asJson.hcursor.downField("questions").keys.map(_.toList),
      Some(List("q00", "q01"))
    )
    assertEquals(req.model, Model.JevLatest)

  test("withKeys lets the caller name the questions"):
    val ask = Ask(urgency, team).withKeys(i => s"question-$i")
    val req = ask.request("s", Model.JevLatest).toOption.get
    assertEquals(
      req.asJson.hcursor.downField("questions").keys.map(_.toList),
      Some(List("question-0", "question-1"))
    )
    val result = ask.answers(
      evaluation("question-0" -> noulAnswer, "question-1" -> choiceAnswer)
    )
    assert(result.isRight, result.toString)

  test("withModel overrides the client default"):
    val pinned = Model.fromString("jev-1.13.0").toOption.get
    val req =
      Ask(urgency).withModel(pinned).request("s", Model.JevLatest).toOption.get
    assertEquals(req.model, pinned)

  test("a missing answer names the key that was missing"):
    val err = Ask(urgency, team)
      .answers(evaluation("q00" -> noulAnswer))
      .swap
      .toOption
      .get
    assert(err.getMessage.contains("q01"), err.getMessage)

  test("the wrong answer shape for a position is a decoding failure"):
    val err = Ask(urgency)
      .answers(evaluation("q00" -> scoreAnswer))
      .swap
      .toOption
      .get
    assert(err.getMessage.contains("noul"), err.getMessage)
    assert(err.getMessage.contains("score"), err.getMessage)

  test("one invalid question anywhere reports every error, not just the first"):
    val ask = Ask(urgency, Question.noul("", "", ""), team)
    ask.answers(evaluation()) match
      case Left(Verdict4sError.Invalid(errors)) =>
        assertEquals(errors.length, 3L)
      case other => fail(s"expected Invalid, got $other")

  test("an invalid question also blocks the request from being built"):
    assert(Ask(Question.noul("")).request("s", Model.JevLatest).isInvalid)

  test("arity 1 works"):
    Ask(urgency).answers(evaluation("q00" -> noulAnswer)) match
      case Right(one) =>
        val u: NoulAnswer = one.head
        assertEquals(u.value, p(0.95))
      case Left(e) => fail(s"projection failed: $e")

  test("arity 8 works, and keys stay ordered past the padding boundary"):
    val ask = Ask(
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency
    )
    val ev = evaluation((0 until 8).map(i => Ask.defaultKey(i) -> noulAnswer)*)
    ask.answers(ev) match
      case Right(t) =>
        assertEquals(t.size, 8)
        val first: NoulAnswer = t.head
        assertEquals(first.value, p(0.95))
      case Left(e) => fail(s"projection failed: $e")

  test("a tuple built elsewhere can be passed directly"):
    val ask = Ask((urgency, team))
    assert(
      ask
        .answers(evaluation("q00" -> noulAnswer, "q01" -> choiceAnswer))
        .isRight
    )

  test("state may be any encodable value"):
    val req = Ask(urgency)
      .request(
        Map("role" -> "user", "text" -> "payouts failing"),
        Model.JevLatest
      )
      .toOption
      .get
    assertEquals(req.state.hcursor.downField("role").as[String], Right("user"))

  test("arity 13 works, as the service's own batching examples use"):
    val ask = Ask(
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency
    )
    val ev = evaluation((0 until 13).map(i => Ask.defaultKey(i) -> noulAnswer)*)
    assertEquals(ask.answers(ev).map(_.size), Right(13))

  test("arity 22 is supported through apply"):
    val ask = Ask(
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency,
      urgency
    )
    val ev = evaluation((0 until 22).map(i => Ask.defaultKey(i) -> noulAnswer)*)
    ask.answers(ev) match
      case Right(t) =>
        assertEquals(t.size, 22)
        val first: NoulAnswer = t.head
        assertEquals(first.value, p(0.95))
      case Left(e) => fail(s"projection failed: $e")
