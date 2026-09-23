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

import io.circe.Json
import io.circe.JsonObject
import io.circe.parser.parse
import io.circe.syntax.*

/** Golden JSON taken verbatim from the published API reference, so the codecs
  * are checked against the documented contract rather than against themselves.
  *
  * Assertions compare `Json` values structurally, never printed strings:
  * `1.0.toString` is `"1"` on Node and `"1.0"` on the JVM, and these suites are
  * shared across both platforms.
  */
class QuestionCodecTest extends munit.FunSuite:

  private def text(s: String): Instructions =
    Instructions.text(s).toOption.get

  private def golden(raw: String): Json = parse(raw).toOption.get

  test("noul encodes with its criteria"):
    val q = QuestionSpec.Noul(
      text("Does this convey urgency?"),
      Some(
        NoulCriteria(
          Some(text("Explicitly time-sensitive")),
          Some(text("No urgency expressed"))
        )
      )
    )
    assertEquals(
      q.asJson,
      golden("""
        {
          "type": "noul",
          "instructions": "Does this convey urgency?",
          "criteria": {
            "true": "Explicitly time-sensitive",
            "false": "No urgency expressed"
          }
        }
      """)
    )

  test("absent noul criteria omits the key entirely, never emits null"):
    val q = QuestionSpec.Noul(text("Does this convey urgency?"), None)
    assertEquals(
      q.asJson,
      golden("""{"type":"noul","instructions":"Does this convey urgency?"}""")
    )
    assert(!q.asJson.hcursor.keys.get.toList.contains("criteria"))

  test("empty noul criteria is treated as absent"):
    val q = QuestionSpec.Noul(text("urgent?"), Some(NoulCriteria.empty))
    assert(!q.asJson.hcursor.keys.get.toList.contains("criteria"))

  test("noul criteria may describe only one side"):
    val q = QuestionSpec.Noul(
      text("urgent?"),
      Some(NoulCriteria(Some(text("time-sensitive")), None))
    )
    assertEquals(
      q.asJson,
      golden(
        """{"type":"noul","instructions":"urgent?","criteria":{"true":"time-sensitive"}}"""
      )
    )

  test("choice encodes its rubric map"):
    val options = Options
      .strings(
        Seq(
          "billing" -> Some(text("Payments, invoicing, refunds")),
          "technical" -> Some(text("Bugs, outages, integrations")),
          "sales" -> Some(text("Pricing, upgrades, new accounts"))
        )
      )
      .toOption
      .get
    val q =
      QuestionSpec.Choice(text("Which team should handle this?"), options.keys)
    assertEquals(
      q.asJson,
      golden("""
        {
          "type": "choice",
          "instructions": "Which team should handle this?",
          "criteria": {
            "billing": "Payments, invoicing, refunds",
            "technical": "Bugs, outages, integrations",
            "sales": "Pricing, upgrades, new accounts"
          }
        }
      """)
    )

  test("a null choice rubric is meaningful and is emitted as an explicit null"):
    // The API documents null as 'this option needs no extra detail'. Dropping
    // it would silently change the request.
    val options = Options.of("billing", "sales").toOption.get
    val q = QuestionSpec.Choice(text("Which team?"), options.keys)
    val criteria = q.asJson.hcursor.downField("criteria").focus.get
    assertEquals(
      criteria,
      Json.fromJsonObject(
        JsonObject("billing" -> Json.Null, "sales" -> Json.Null)
      )
    )

  test("score encodes its levels as an ordered array"):
    val levels =
      Levels
        .descriptionsOf(Seq("Calm", "Frustrated", "Very angry"))
        .toOption
        .get
    val q = QuestionSpec.Score(text("How frustrated is the customer?"), levels)
    assertEquals(
      q.asJson,
      golden("""
        {
          "type": "score",
          "instructions": "How frustrated is the customer?",
          "criteria": ["Calm", "Frustrated", "Very angry"]
        }
      """)
    )

  test("score levels keep declaration order, not sorted order"):
    val levels =
      Levels.descriptionsOf(Seq("Zebra", "Apple", "Mango")).toOption.get
    val q = QuestionSpec.Score(text("rate"), levels)
    assertEquals(
      q.asJson.hcursor.downField("criteria").as[List[String]],
      Right(List("Zebra", "Apple", "Mango"))
    )

  test("structured instructions survive encoding"):
    val structured = Instructions
      .encoded(
        Map(
          "potential_duplicate" -> "John Smith",
          "question" -> "Is the resume for the same person as `potential_duplicate`?"
        )
      )
      .toOption
      .get
    val q = QuestionSpec.Noul(structured, None)
    assertEquals(
      q.asJson.hcursor
        .downField("instructions")
        .downField("question")
        .as[String],
      Right("Is the resume for the same person as `potential_duplicate`?")
    )

  test("raw carries an unmodelled shape through, with its type applied"):
    val q = QuestionSpec.Raw(
      "ranking",
      JsonObject("instructions" -> Json.fromString("order these"))
    )
    assertEquals(
      q.asJson,
      golden("""{"instructions":"order these","type":"ranking"}""")
    )
    assertEquals(q.wireType, "ranking")

  test("wireType names each shape"):
    assertEquals(QuestionSpec.Noul(text("a"), None).wireType, "noul")
    assertEquals(
      QuestionSpec
        .Choice(text("a"), Options.of("x").toOption.get.keys)
        .wireType,
      "choice"
    )
    assertEquals(
      QuestionSpec
        .Score(text("a"), Levels.descriptionsOf(Seq("a", "b")).toOption.get)
        .wireType,
      "score"
    )
