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
import io.circe.JsonObject
import io.circe.parser.parse

/** OpenAI Decisions' wire format.
  *
  * The golden JSON comes from the `/decisions` example and the
  * `DecisionRequest` / `DecisionResponse` schemas in OpenAI's published OpenAPI
  * spec, not from the guide, which omits `model`, `usage` and refusals.
  * Comparisons are structural (`Json` against `Json`), because `Double` renders
  * differently on the JVM and on Node.
  */
class OpenAICodecTest extends munit.FunSuite:

  private def golden(raw: String): Json = parse(raw).toOption.get
  private def p(d: Double): Probability = Probability.either(d).toOption.get
  private def c(d: Double): Confidence = Confidence.either(d).toOption.get
  private def n(s: String): OptionName = OptionName.either(s).toOption.get
  private def k(s: String): QuestionKey = QuestionKey.either(s).toOption.get

  enum Dept derives Options:
    case Billing, Technical

  private val openAI = Vendor.OpenAI

  private def render[Q <: Tuple](ask: Ask[Q], state: String): Json =
    golden(openAI.renderAsk(ask, state).toOption.get)

  test("encodes the spec's own example request"):
    val ask =
      Ask(Question.predicate("Does the customer report a damaged item?"))
        .withKeys(_ => "damaged")
    assertEquals(
      render(ask, "The package arrived with a broken screen."),
      golden("""
        {
          "model": "gpt-6-luna",
          "input": "The package arrived with a broken screen.",
          "questions": [
            {
              "type": "predicate",
              "name": "damaged",
              "instructions": "Does the customer report a damaged item?"
            }
          ]
        }
      """)
    )

  test("a choice sends its options as values, with rubrics as descriptions"):
    val ask = Ask(
      Question.choiceOfStrings(
        "Which team?",
        Seq(
          "billing" -> Instructions.text("Payments and refunds").toOption,
          "technical" -> None
        )
      )
    )
    assertEquals(
      render(ask, "s").hcursor.downField("questions").downN(0).focus,
      Some(golden("""
        {
          "type": "choice",
          "name": "q00",
          "instructions": "Which team?",
          "choices": [
            {"value": "billing", "description": "Payments and refunds"},
            {"value": "technical"}
          ]
        }
      """))
    )

  test("a score sends its levels lowest first, as labels"):
    val ask = Ask(Question.score("How severe?", Seq("Cosmetic", "Blocked")))
    assertEquals(
      render(ask, "s").hcursor.downField("questions").downN(0).focus,
      Some(golden("""
        {
          "type": "score",
          "name": "q00",
          "instructions": "How severe?",
          "levels": [{"label": "Cosmetic"}, {"label": "Blocked"}]
        }
      """))
    )

  test("questions go in key order, so positional keys keep tuple order"):
    val ask = Ask(Question.noul("a"), Question.noul("b"), Question.noul("c"))
    assertEquals(
      render(ask, "s").hcursor
        .downField("questions")
        .values
        .map(_.toList.flatMap(_.hcursor.downField("name").as[String].toOption)),
      Some(List("q00", "q01", "q02"))
    )

  test("structured state and instructions are sent as compact JSON text"):
    val instructions =
      Instructions
        .json(Json.obj("ask" -> Json.fromString("urgent?")))
        .toOption
        .get
    val ask = Ask(Question.noulOf(instructions))
    val body = golden(
      openAI.renderAsk(ask, Json.obj("ticket" -> Json.fromInt(7))).toOption.get
    )
    assertEquals(
      body.hcursor.downField("input").as[String],
      Right("""{"ticket":7}""")
    )
    assertEquals(
      body.hcursor
        .downField("questions")
        .downN(0)
        .downField("instructions")
        .as[String],
      Right("""{"ask":"urgent?"}""")
    )

  test("a raw question is named by its key, whatever name its body carries"):
    val body = JsonObject("name" -> Json.fromString("other"))
    assertEquals(
      OpenAICodecs.encodeQuestion(k("q00"), QuestionSpec.Raw("ranking", body)),
      golden("""{"type":"ranking","name":"q00"}""")
    )

  test("a safety identifier is sent when set, and omitted otherwise"):
    val ask = Ask(Question.noul("urgent?"))
    assertEquals(
      render(ask, "s").hcursor.downField("safety_identifier").focus,
      None
    )
    val tagged = golden(
      openAI.withSafetyIdentifier("user-42").renderAsk(ask, "s").toOption.get
    )
    assertEquals(
      tagged.hcursor.downField("safety_identifier").as[String],
      Right("user-42")
    )

  test("decodes the spec's own example response"):
    assertEquals(
      openAI.parse("""
        {
          "model": "gpt-6-luna",
          "answers": [
            {"type": "predicate", "name": "damaged", "probability": 0.95}
          ],
          "usage": {
            "input_tokens": 42,
            "input_tokens_details": {"cached_tokens": 0, "cache_write_tokens": 0},
            "output_tokens": 0,
            "output_tokens_details": {"reasoning_tokens": 0},
            "total_tokens": 42
          }
        }
      """),
      Right(
        Evaluation(
          Model.GptLuna,
          SortedMap(k("damaged") -> Answer.Noul(p(0.95))),
          Usage(42, 0)
        )
      )
    )

  private def answer(raw: String): Either[Verdict4sError, Answer] =
    openAI
      .parse(s"""
        {
          "model": "gpt-6-luna",
          "answers": [$raw],
          "usage": {"input_tokens": 1, "output_tokens": 0, "total_tokens": 1,
                    "input_tokens_details": {"cached_tokens": 0, "cache_write_tokens": 0},
                    "output_tokens_details": {"reasoning_tokens": 0}}
        }
      """)
      .map(_.answers(k("q00")))

  test("a choice answer's probability array becomes the shared map"):
    assertEquals(
      answer("""
        {"type": "choice", "name": "q00", "choice": "billing",
         "probabilities": [{"value": "billing", "probability": 0.95},
                           {"value": "technical", "probability": 0.05}],
         "confidence": 0.93}
      """),
      Right(
        Answer.Choice(
          n("billing"),
          SortedMap(n("billing") -> p(0.95), n("technical") -> p(0.05)),
          c(0.93)
        )
      )
    )

  test("a boolean choice value is read back as its text"):
    assertEquals(
      answer("""
        {"type": "choice", "name": "q00", "choice": true,
         "probabilities": [{"value": true, "probability": 0.75},
                           {"value": false, "probability": 0.25}],
         "confidence": 0.5}
      """),
      Right(
        Answer.Choice(
          n("true"),
          SortedMap(n("true") -> p(0.75), n("false") -> p(0.25)),
          c(0.5)
        )
      )
    )

  test("a score answer's labels become the legend"):
    assertEquals(
      answer("""
        {"type": "score", "name": "q00", "score": 1.1,
         "probabilities": [{"value": 0, "label": "Cosmetic", "probability": 0.1},
                           {"value": 1, "label": "Workaround available", "probability": 0.7},
                           {"value": 2, "label": "Fully blocked", "probability": 0.2}],
         "confidence": 0.55}
      """),
      Right(
        Answer.Score(
          1.1,
          SortedMap(
            0 -> "Cosmetic",
            1 -> "Workaround available",
            2 -> "Fully blocked"
          ),
          SortedMap(0 -> p(0.1), 1 -> p(0.7), 2 -> p(0.2)),
          c(0.55)
        )
      )
    )

  test("a refusal decodes to Answer.Refused"):
    assertEquals(
      answer("""{"type": "refusal", "name": "q00"}"""),
      Right(Answer.Refused)
    )

  test("an unnamed answer is reported rather than guessed at"):
    answer("""{"type": "predicate", "name": null, "probability": 0.5}""") match
      case Left(Verdict4sError.Decoding(details, field)) =>
        assert(details.contains("no name"), details)
        assert(field.exists(_.contains("name")), field.toString)
      case other => fail(s"expected a decoding error, got $other")

  test("a Jev-shaped body is a decoding failure, not a silent success"):
    val jev = """
      {"model": "jev-1.13.0",
       "answers": {"q00": {"type": "noul", "noul": 0.95}},
       "usage": {"input_tokens": 1, "output_tokens": 1}}
    """
    assert(openAI.parse(jev).isLeft)
