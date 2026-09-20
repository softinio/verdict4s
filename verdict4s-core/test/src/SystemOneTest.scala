package com.softinio.verdict4s

import com.softinio.verdict4s.algebra.*
import io.circe.parser

/** The sans-IO surface: the protocol driven with no effect system present.
  *
  * These tests are the proof of the effect-free-core invariant. Nothing here
  * touches cats-effect, http4s or a network, and none of it could, because the
  * module does not depend on them.
  */
class SystemOneTest extends munit.FunSuite:

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
    assertEquals(SystemOne.evaluateUrl, "https://api.typesafe.ai/v1/systemone")
    assertEquals(SystemOne.modelsUrl, "https://api.typesafe.ai/v1/models")

  test("headers carry a bearer token and a JSON content type"):
    val key = ApiKey.fromString("sk-test").toOption.get
    assertEquals(
      SystemOne.headers(key).toMap,
      Map(
        "Authorization" -> "Bearer sk-test",
        "Content-Type" -> "application/json"
      )
    )

  test("render produces the JSON the service expects"):
    val body =
      SystemOne.renderAsk(ask, "Help! My payouts are failing.").toOption.get
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
    SystemOne.renderAsk(bad, "s") match
      case Left(Verdict4sError.Invalid(errors)) =>
        assertEquals(errors.length, 3L)
      case other => fail(s"expected Invalid, got $other")

  test("parse turns a response body into typed answers"):
    val evaluation = SystemOne.parse(responseBody).toOption.get
    assertEquals(evaluation.usage, Usage(296, 20))
    assertEquals(evaluation.model.name, "jev-1.13.0")

  test("parseAsk projects the response through the questions that produced it"):
    SystemOne.parseAsk(ask, responseBody) match
      case Right((urgency, dept)) =>
        val u: NoulAnswer = urgency
        val d: ChoiceAnswer[Dept] = dept
        assert(u.isTrue())
        assertEquals(d.choice, Dept.Billing)
      case Left(e) => fail(s"parseAsk failed: $e")

  test("a body that is not JSON fails with a decoding error, not an exception"):
    val err = SystemOne.parse("<html>502 Bad Gateway</html>").swap.toOption.get
    assert(err.getMessage.contains("not JSON"), err.getMessage)

  test("a structurally wrong body reports where it went wrong"):
    val err = SystemOne
      .parse(
        """{"model":"jev-1.13.0","usage":{"input_tokens":1,"output_tokens":1}}"""
      )
      .swap
      .toOption
      .get
    assert(err.isInstanceOf[Verdict4sError.Decoding], err.toString)

  test("parseError builds a typed API error from status, body and headers"):
    val err = SystemOne.parseError(
      429,
      """{"message":"slow down"}""",
      Map("Retry-After" -> "2", "X-TypeSafe-Request-Id" -> "req-123")
    )
    assertEquals(err.status, 429)
    assertEquals(err.failure, ApiFailure.RateLimited)
    assertEquals(err.details, "slow down")
    assertEquals(err.requestId, Some("req-123"))
    assertEquals(err.retryAfter.map(_.toSeconds), Some(2L))

  test("parseError copes with a non-JSON error body"):
    val err = SystemOne.parseError(502, "<html>Bad Gateway</html>")
    assertEquals(err.status, 502)
    assertEquals(err.failure, ApiFailure.Server)
    assert(err.body.isDefined)

  test("render and parse round trip through the wire format"):
    val body = SystemOne.renderAsk(ask, "state").toOption.get
    assert(parser.parse(body).isRight)
    assertEquals(
      SystemOne.parse(responseBody).map(_.answers.size),
      Right(2)
    )
