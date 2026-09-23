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

package com.softinio.verdict4s.algebra

import scala.collection.immutable.SortedMap

/** Enum derivation, and the LTS-only compiler trap it depends on.
  *
  * `constValueTuple[...].toList` compiles on 3.9 and fails on 3.3, so this
  * suite running green on both legs of the matrix is the real assertion; the
  * bodies below just check the semantics.
  */
class DerivationTest extends munit.FunSuite:

  private def p(d: Double): Probability = Probability.either(d).toOption.get
  private def c(d: Double): Confidence = Confidence.either(d).toOption.get
  private def n(s: String): OptionName = OptionName.either(s).toOption.get

  enum Dept derives Options:
    case Billing, Technical, Sales

  enum Frustration derives Levels:
    case Calm, Frustrated, VeryAngry

  test("Options derivation uses the case labels in declaration order"):
    val o = summon[Options[Dept]]
    assertEquals(
      o.names.map(x => x.value: String),
      List("Billing", "Sales", "Technical")
    )
    assertEquals(o.size, 3)

  test("derived options round trip through render and parse"):
    val o = summon[Options[Dept]]
    Dept.values.foreach: d =>
      assertEquals(o.parse(o.render(d).value: String), Some(d))

  test("an option the enum does not have does not parse"):
    assertEquals(summon[Options[Dept]].parse("Legal"), None)
    assertEquals(summon[Options[Dept]].parse("billing"), None)

  test("a derived choice question answers in the enum type"):
    val q = Question.choice[Dept]("Which team should handle this?")
    val decode = q.toEither.toOption.get.decode
    val answer = Answer.Choice(
      n("Billing"),
      SortedMap(n("Billing") -> p(0.88), n("Technical") -> p(0.12)),
      c(0.81)
    )
    assertEquals(decode(answer).map(_.choice), Right(Dept.Billing))

  test("choiceOf derives on the spot, without a derives clause"):
    enum Tier:
      case Free, Pro
    val q = Question.choiceOf[Tier]("Which tier?")
    val decode = q.toEither.toOption.get.decode
    val answer = Answer.Choice(n("Pro"), SortedMap(n("Pro") -> p(1.0)), c(1.0))
    assertEquals(decode(answer).map(_.choice), Right(Tier.Pro))

  test("rubric text can be attached to derived options"):
    val o = Options.describing[Dept](
      Map(
        "Billing" -> Instructions
          .text("Payments, invoicing, refunds")
          .toOption
          .get
      )
    )
    val rubric = o.keys.get(n("Billing")).flatten
    assertEquals(
      rubric.map(_.toJson.asString.get),
      Some("Payments, invoicing, refunds")
    )
    assertEquals(o.keys.get(n("Sales")).flatten, None)

  test("Levels derivation keeps declaration order, which is rubric order"):
    val l = summon[Levels[Frustration]]
    assertEquals(l.size, 3)
    assertEquals(l.at(0), Some(Frustration.Calm))
    assertEquals(l.at(1), Some(Frustration.Frustrated))
    assertEquals(l.at(2), Some(Frustration.VeryAngry))
    assertEquals(l.at(3), None)
    assertEquals(l.indexOf(Frustration.VeryAngry), 2)

  test("a derived score question sends the labels as its rubric"):
    val q = Question.scoreOf[Frustration]("How frustrated is the customer?")
    q.spec match
      case Some(QuestionSpec.Score(_, levels)) =>
        assertEquals(
          levels.toList.map(_.toJson.asString.get),
          List("Calm", "Frustrated", "VeryAngry")
        )
      case other => fail(s"unexpected spec: $other")

  test("a score answer maps its nearest level back into the enum"):
    val a = ScoreAnswer(
      1.05,
      SortedMap(0 -> "Calm", 1 -> "Frustrated", 2 -> "VeryAngry"),
      SortedMap(0 -> p(0.0), 1 -> p(0.95), 2 -> p(0.05)),
      c(0.92)
    )
    assertEquals(a.levelAs[Frustration], Some(Frustration.Frustrated))

  test("probabilityFor reads a derived option's probability"):
    val a = ChoiceAnswer(
      Dept.Billing,
      SortedMap(n("Billing") -> p(0.88), n("Technical") -> p(0.12)),
      c(0.81)
    )
    assertEquals(a.probabilityFor(Dept.Billing), Some(p(0.88)))
    assertEquals(a.probabilityFor(Dept.Sales), None)
