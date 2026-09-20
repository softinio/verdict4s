package com.softinio.verdict4s.algebra

import scala.collection.immutable.SortedMap

import com.softinio.verdict4s.Verdict4sError
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*

/** `Question[A]` is a functor over its answer, which is how a caller defines
  * their own answer types without the wire protocol growing a case.
  */
class QuestionMapTest extends munit.FunSuite:

  private def p(d: Double): Probability = Probability.either(d).toOption.get
  private def c(d: Double): Confidence = Confidence.either(d).toOption.get
  private def n(s: String): OptionName = OptionName.either(s).toOption.get

  final case class Route(team: String, escalate: Boolean)

  private def choiceAnswer(pick: String, conf: Double) =
    Answer.Choice(
      n(pick),
      SortedMap(n("billing") -> p(0.9), n("sales") -> p(0.1)),
      c(conf)
    )

  private val team =
    Question.choiceOfStrings("team?", Seq("billing" -> None, "sales" -> None))

  test("map produces the caller's own answer type"):
    val routed: Question[Route] =
      team.map(a =>
        Route(a.choice, escalate = (a.confidence.value: Double) < 0.7)
      )
    val decode = routed.toEither.toOption.get.decode
    assertEquals(
      decode(choiceAnswer("billing", 0.9)),
      Right(Route("billing", escalate = false))
    )
    assertEquals(
      decode(choiceAnswer("billing", 0.5)),
      Right(Route("billing", escalate = true))
    )

  test("map leaves the wire request untouched"):
    // Only the way the answer is read changes; the service sees the same thing.
    assertEquals(team.map(_.choice).spec, team.spec)

  test("map composes"):
    val q = team.map(_.choice).map(_.toUpperCase).map(_.take(3))
    val decode = q.toEither.toOption.get.decode
    assertEquals(decode(choiceAnswer("billing", 0.9)), Right("BIL"))

  test("emap can reject an answer the caller's type cannot represent"):
    val strict: Question[String] = team.emap: a =>
      if (a.confidence.value: Double) >= 0.8 then Right(a.choice)
      else Left(Verdict4sError.Decoding("not confident enough"))
    val decode = strict.toEither.toOption.get.decode
    assertEquals(decode(choiceAnswer("billing", 0.9)), Right("billing"))
    assert(decode(choiceAnswer("billing", 0.5)).isLeft)

  test("mapping an invalid question keeps every original error"):
    val q = Question.noul("", "", "").map(_ => 42)
    assertEquals(q.validated.swap.toOption.get.length, 3L)

  test("map on a noul question reaches the probability"):
    val q = Question.noul("urgent?").map(_.isTrue(threshold = 0.9))
    val decode = q.toEither.toOption.get.decode
    assertEquals(decode(Answer.Noul(p(0.95))), Right(true))
    assertEquals(decode(Answer.Noul(p(0.5))), Right(false))

  test("raw sends an unmodelled shape and decodes it however the caller says"):
    val q = Question.raw[String](
      "ranking",
      JsonObject("instructions" -> Json.fromString("order these"))
    ) {
      case Answer.Noul(value) => Right(s"noul:${value.value: Double}")
      case other              =>
        Left(Verdict4sError.Decoding(s"unexpected ${other.wireType}"))
    }
    assertEquals(
      q.spec.map(_.asJson),
      Some(
        Json.obj(
          "instructions" -> "order these".asJson,
          "type" -> "ranking".asJson
        )
      )
    )
    val decode = q.toEither.toOption.get.decode
    assertEquals(decode(Answer.Noul(p(0.5))), Right("noul:0.5"))

  test("a mapped question still reports a decoding mismatch from underneath"):
    val q = Question.noul("urgent?").map(_.value)
    val decode = q.toEither.toOption.get.decode
    assert(decode(choiceAnswer("billing", 0.9)).isLeft)
