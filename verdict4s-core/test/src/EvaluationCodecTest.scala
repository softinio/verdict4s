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

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import io.github.iltotore.iron.*

class EvaluationCodecTest extends munit.FunSuite:

  private def golden(raw: String): Json = parse(raw).toOption.get
  private def p(d: Double): Probability = Probability.either(d).toOption.get
  private def k(s: String): QuestionKey = QuestionKey.either(s).toOption.get
  private def text(s: String): Instructions = Instructions.text(s).toOption.get

  private def questions(
      entries: (String, QuestionSpec)*
  ): EvaluationRequest.Questions =
    SortedMap
      .from(entries.map((key, spec) => k(key) -> spec))
      .refineUnsafe[QuestionsC]

  test("decodes the documented response"):
    assertEquals(
      golden("""
        {
          "model": "jev-1.13.0",
          "answers": {"is_urgent": {"type": "noul", "noul": 0.95}},
          "usage": {"input_tokens": 296, "output_tokens": 20}
        }
      """).as[Evaluation],
      Right(
        Evaluation(
          Model.fromString("jev-1.13.0").toOption.get,
          SortedMap(k("is_urgent") -> Answer.Noul(p(0.95))),
          Usage(296, 20)
        )
      )
    )

  test("an answer is reachable under the key that was sent"):
    val e = golden("""
      {
        "model": "jev-1.13.0",
        "answers": {"is_urgent": {"type": "noul", "noul": 0.95}},
        "usage": {"input_tokens": 1, "output_tokens": 1}
      }
    """).as[Evaluation].toOption.get
    assertEquals(e.answer(k("is_urgent")), Some(Answer.Noul(p(0.95))))
    assertEquals(e.answer(k("absent")), None)

  test("an empty answer map decodes rather than failing"):
    // Non-emptiness is a request invariant; being lenient here keeps a
    // degenerate but parseable reply from becoming a decoding failure.
    assert(
      golden(
        """{"model":"jev-1.13.0","answers":{},"usage":{"input_tokens":0,"output_tokens":0}}"""
      ).as[Evaluation].isRight
    )

  test("a response round trips"):
    val e = Evaluation(
      Model.JevLatest,
      SortedMap(k("a") -> Answer.Noul(p(0.5))),
      Usage(10, 2)
    )
    assertEquals(e.asJson.as[Evaluation], Right(e))

  test("encodes the documented request"):
    val req = EvaluationRequest(
      Json.fromString("Help! My payouts have been failing for 3 days."),
      Model.JevLatest,
      questions(
        "is_urgent" -> QuestionSpec.Noul(
          text("Does this convey urgency?"),
          None
        )
      )
    )
    assertEquals(
      req.asJson,
      golden("""
        {
          "state": "Help! My payouts have been failing for 3 days.",
          "model": "jev-latest",
          "questions": {
            "is_urgent": {
              "type": "noul",
              "instructions": "Does this convey urgency?"
            }
          }
        }
      """)
    )

  test("state may be structured, not just text"):
    val state = Json.arr(
      Json.obj("role" -> "user".asJson, "text" -> "payouts failing".asJson)
    )
    val req = EvaluationRequest(
      state,
      Model.JevLatest,
      questions("q" -> QuestionSpec.Noul(text("urgent?"), None))
    )
    assertEquals(req.asJson.hcursor.downField("state").focus, Some(state))

  test("field and question key order is deterministic"):
    // The one exact-string assertion in the suite, and deliberately free of
    // floating point: `1.0.toString` is "1" on Node and "1.0" on the JVM, so
    // a Double anywhere here would pass on one platform and fail on the other.
    val req = EvaluationRequest(
      Json.fromString("s"),
      Model.JevLatest,
      questions(
        "zebra" -> QuestionSpec.Noul(text("z"), None),
        "apple" -> QuestionSpec.Noul(text("a"), None),
        "mango" -> QuestionSpec.Noul(text("m"), None)
      )
    )
    assertEquals(
      req.asJson.noSpaces,
      """{"state":"s","model":"jev-latest","questions":{""" +
        """"apple":{"type":"noul","instructions":"a"},""" +
        """"mango":{"type":"noul","instructions":"m"},""" +
        """"zebra":{"type":"noul","instructions":"z"}}}"""
    )

  test("ModelCard decodes the snake_case release date"):
    assertEquals(
      golden("""
        {
          "name": "jev-1.13.0",
          "description": "The current production release",
          "release_date": "2025-06-01"
        }
      """).as[ModelCard],
      Right(
        ModelCard(
          Model.fromString("jev-1.13.0").toOption.get,
          "The current production release",
          "2025-06-01"
        )
      )
    )

  test("a list of model cards decodes"):
    val cards = golden("""
      [
        {"name":"jev-latest","description":"alias","release_date":"2025-06-01"},
        {"name":"jev-1.13.0","description":"pinned","release_date":"2025-06-01"}
      ]
    """).as[List[ModelCard]]
    assertEquals(
      cards.map(_.map(_.name.name)),
      Right(List("jev-latest", "jev-1.13.0"))
    )
