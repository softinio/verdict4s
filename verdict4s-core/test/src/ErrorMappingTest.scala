package com.softinio.verdict4s

import scala.concurrent.duration.*

import cats.data.NonEmptyChain
import com.softinio.verdict4s.algebra.ApiErrorBody
import com.softinio.verdict4s.algebra.ApiFailure
import io.circe.Json

class ErrorMappingTest extends munit.FunSuite:

  test("every documented status maps to its named failure"):
    assertEquals(ApiFailure.fromStatus(400), ApiFailure.BadRequest)
    assertEquals(ApiFailure.fromStatus(401), ApiFailure.Unauthorized)
    assertEquals(ApiFailure.fromStatus(403), ApiFailure.Forbidden)
    assertEquals(ApiFailure.fromStatus(404), ApiFailure.NotFound)
    assertEquals(ApiFailure.fromStatus(422), ApiFailure.Unprocessable)
    assertEquals(ApiFailure.fromStatus(429), ApiFailure.RateLimited)
    assertEquals(ApiFailure.fromStatus(529), ApiFailure.Overloaded)

  test("529 is Overloaded, not swept into the generic 5xx branch"):
    assertEquals(ApiFailure.fromStatus(529), ApiFailure.Overloaded)
    assertEquals(ApiFailure.fromStatus(500), ApiFailure.Server)
    assertEquals(ApiFailure.fromStatus(503), ApiFailure.Server)
    assertEquals(ApiFailure.fromStatus(599), ApiFailure.Server)

  test("unclassified statuses fall through to Other"):
    assertEquals(ApiFailure.fromStatus(418), ApiFailure.Other)
    assertEquals(ApiFailure.fromStatus(200), ApiFailure.Other)

  test("isTransient marks exactly the retryable classes"):
    val transient = ApiFailure.values.filter(_.isTransient).toSet
    assertEquals(
      transient,
      Set(ApiFailure.RateLimited, ApiFailure.Overloaded, ApiFailure.Server)
    )

  test("fromResponse carries request id and Retry-After through"):
    val e = Verdict4sError.fromResponse(
      429,
      Json.obj("message" -> Json.fromString("slow down")),
      requestId = Some("req-123"),
      retryAfter = Some(2.seconds)
    )
    assertEquals(e.status, 429)
    assertEquals(e.failure, ApiFailure.RateLimited)
    assertEquals(e.details, "slow down")
    assertEquals(e.requestId, Some("req-123"))
    assertEquals(e.retryAfter, Some(2.seconds))

  test("fromResponse falls back to a description when the body has no message"):
    val e = Verdict4sError.fromResponse(401, Json.obj())
    assertEquals(e.details, "missing or invalid API key")
    assertEquals(e.failure, ApiFailure.Unauthorized)

  test("an unparseable body still yields Api, with the raw payload kept"):
    // The important property: a body shape we do not model must not be
    // downgraded into a Decoding failure that hides the real problem.
    val weird = Json.arr(Json.fromString("totally"), Json.fromInt(42))
    val e = Verdict4sError.fromResponse(422, weird)
    assertEquals(e.failure, ApiFailure.Unprocessable)
    assertEquals(e.body.map(_.raw), Some(weird))

  test("a FastAPI-style validation body yields message and field"):
    val body = Json.obj(
      "detail" -> Json.arr(
        Json.obj(
          "loc" -> Json
            .arr(Json.fromString("body"), Json.fromString("questions")),
          "msg" -> Json.fromString("field required"),
          "type" -> Json.fromString("value_error.missing")
        )
      )
    )
    val parsed = ApiErrorBody.fromJson(body)
    assertEquals(parsed.message, Some("field required"))
    assertEquals(parsed.field, Some("body.questions"))
    assertEquals(parsed.kind, Some("value_error.missing"))

  test("an error nested under `error` is found too"):
    val body = Json.obj(
      "error" -> Json.obj(
        "message" -> Json.fromString("bad key"),
        "type" -> Json.fromString("authentication_error")
      )
    )
    val parsed = ApiErrorBody.fromJson(body)
    assertEquals(parsed.message, Some("bad key"))
    assertEquals(parsed.kind, Some("authentication_error"))

  test("Decoding renders its field path when it has one"):
    val e =
      Verdict4sError.Decoding("not a number", Some("answers.tone.confidence"))
    assert(e.getMessage.contains("answers.tone.confidence"), e.getMessage)

  test("Invalid aggregates every accumulated Validation"):
    val e = Verdict4sError.Invalid(
      NonEmptyChain(
        Verdict4sError.Validation("questions.q00", "too many options"),
        Verdict4sError.Validation("questions.q01", "not enough levels")
      )
    )
    assert(e.getMessage.contains("2 validation error(s)"), e.getMessage)
    assert(e.getMessage.contains("too many options"), e.getMessage)
    assert(e.getMessage.contains("not enough levels"), e.getMessage)
