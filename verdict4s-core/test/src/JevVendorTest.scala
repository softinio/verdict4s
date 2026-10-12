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

import com.softinio.verdict4s.algebra.*
import io.circe.parser

/** The effect-free surface: the protocol driven with no effect system present.
  *
  * These tests are the proof of the effect-free-core invariant. Nothing here
  * touches cats-effect, http4s or a network, and none of it could, because the
  * module does not depend on them.
  */
class JevVendorTest extends munit.FunSuite:

  enum Dept derives Options:
    case Billing, Technical, Sales

  private val ask = Ask(
    Question.noul("Does this convey urgency?"),
    Question.choice[Dept]("Which team should handle this?")
  )

  private val responseBody = """
    {
      "model": "jev-1.13.0",
      "answers": {
        "q00": {"type": "noul", "noul": 0.95},
        "q01": {
          "type": "choice",
          "choice": "Billing",
          "probabilities": {"Billing": 0.88, "Technical": 0.12, "Sales": 0.0},
          "confidence": 0.81
        }
      },
      "usage": {"input_tokens": 296, "output_tokens": 20}
    }
  """

  test("endpoints are built from the documented base URL"):
    assertEquals(Vendor.Jev.evaluateUrl, "https://api.typesafe.ai/v1/systemone")
    assertEquals(Vendor.Jev.modelsUrl, "https://api.typesafe.ai/v1/models")

  test("headers carry a bearer token and a JSON content type"):
    val key = ApiKey.fromString("sk-test").toOption.get
    assertEquals(
      Vendor.Jev.headers(key).toMap,
      Map(
        "Authorization" -> "Bearer sk-test",
        "Content-Type" -> "application/json"
      )
    )

  test("render produces the JSON the service expects"):
    val body =
      Vendor.Jev.renderAsk(ask, "Help! My payouts are failing.").toOption.get
    val json = parser.parse(body).toOption.get
    assertEquals(
      json.hcursor.downField("state").as[String],
      Right("Help! My payouts are failing.")
    )
    assertEquals(
      json.hcursor.downField("model").as[String],
      Right("jev-latest")
    )
    assertEquals(
      json.hcursor.downField("questions").keys.map(_.toList),
      Some(List("q00", "q01"))
    )

  test(
    "an invalid question stops a request being rendered, reporting all of it"
  ):
    val bad = Ask(Question.noul("", "", ""))
    Vendor.Jev.renderAsk(bad, "s") match
      case Left(Verdict4sError.Invalid(errors)) =>
        assertEquals(errors.length, 3L)
      case other => fail(s"expected Invalid, got $other")

  test("parse turns a response body into typed answers"):
    val evaluation = Vendor.Jev.parse(responseBody).toOption.get
    assertEquals(evaluation.usage, Usage(296, 20))
    assertEquals(evaluation.model.name, "jev-1.13.0")

  test("parseAsk projects the response through the questions that produced it"):
    Vendor.Jev.parseAsk(ask, responseBody) match
      case Right((urgency, dept)) =>
        val u: NoulAnswer = urgency
        val d: ChoiceAnswer[Dept] = dept
        assert(u.isTrue())
        assertEquals(d.choice, Dept.Billing)
      case Left(e) => fail(s"parseAsk failed: $e")

  test("a body that is not JSON fails with a decoding error, not an exception"):
    val err = Vendor.Jev.parse("<html>502 Bad Gateway</html>").swap.toOption.get
    assert(err.getMessage.contains("not JSON"), err.getMessage)

  test("a structurally wrong body reports where it went wrong"):
    val err = Vendor.Jev
      .parse(
        """{"model":"jev-1.13.0","usage":{"input_tokens":1,"output_tokens":1}}"""
      )
      .swap
      .toOption
      .get
    assert(err.isInstanceOf[Verdict4sError.Decoding], err.toString)

  test("parseError builds a typed API error from status, body and headers"):
    val err = Vendor.Jev.parseError(
      429,
      """{"message":"slow down"}""",
      Map("Retry-After" -> "2", "X-TypeSafe-Request-Id" -> "req-123")
    )
    assertEquals(err.status, 429)
    assertEquals(err.failure, ApiFailure.RateLimited)
    assertEquals(err.details, "slow down")
    assertEquals(err.requestId, Some("req-123"))
    assertEquals(err.retryAfter.map(_.toSeconds), Some(2L))

  test("parseError clamps Retry-After to zero and to the longest duration"):
    def after(value: String) =
      Vendor.Jev.parseError(503, "", Map("Retry-After" -> value)).retryAfter
    assertEquals(after("-5"), Some(scala.concurrent.duration.Duration.Zero))
    assert(after("99999999999999").exists(_.toDays > 100000L))

  test("parseError takes a Retry-After of 0, and ignores one it cannot read"):
    def after(value: String) =
      Vendor.Jev.parseError(503, "", Map("Retry-After" -> value)).retryAfter
    assertEquals(after(" 0 "), Some(scala.concurrent.duration.Duration.Zero))
    assertEquals(after("soon"), None)

  test("parseError copes with a non-JSON error body"):
    val err = Vendor.Jev.parseError(502, "<html>Bad Gateway</html>")
    assertEquals(err.status, 502)
    assertEquals(err.failure, ApiFailure.Server)
    assert(err.body.isDefined)

  test("Jev adds no validation of its own"):
    val one = Ask(Question.choiceOfStrings("Only one?", Seq("only" -> None)))
    assert(Vendor.Jev.renderAsk(one, "s").isRight)
