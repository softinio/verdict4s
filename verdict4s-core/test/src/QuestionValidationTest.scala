package com.softinio.verdict4s.algebra

import scala.collection.immutable.SortedMap

import com.softinio.verdict4s.Verdict4sError

class QuestionValidationTest extends munit.FunSuite:

  private def p(d: Double): Probability = Probability.either(d).toOption.get
  private def c(d: Double): Confidence = Confidence.either(d).toOption.get
  private def n(s: String): OptionName = OptionName.either(s).toOption.get

  test("a valid noul question carries the right wire shape"):
    val q = Question.noul("Does this convey urgency?")
    assertEquals(
      q.spec,
      Some(
        QuestionSpec.Noul(
          Instructions.text("Does this convey urgency?").toOption.get,
          None
        )
      )
    )

  test("noul with criteria describes both sides"):
    val q = Question.noul("urgent?", "time-sensitive", "no urgency")
    q.spec match
      case Some(QuestionSpec.Noul(_, Some(crit))) =>
        assert(crit.ifTrue.isDefined && crit.ifFalse.isDefined)
      case other => fail(s"unexpected spec: $other")

  test("blank instructions are rejected"):
    assert(Question.noul("").validated.isInvalid)
    assert(Question.noul("   ").validated.isInvalid)
    assert(Question.score("  ", Seq("a", "b")).validated.isInvalid)

  test("validation accumulates across every field of one question"):
    // Three blanks must yield three errors, not one: fail-fast here would make
    // the caller fix them one network round trip at a time.
    val q = Question.noul("", "", "")
    assertEquals(q.validated.swap.toOption.get.length, 3L)

  test("choice cardinality is enforced at construction"):
    def choice(n: Int) =
      Question.choiceOfStrings("pick", (1 to n).map(i => s"o$i" -> None))
    assert(choice(1).validated.isValid)
    assert(choice(255).validated.isValid)
    assert(choice(0).validated.isInvalid)
    assert(choice(256).validated.isInvalid)

  test("score level bounds are enforced at construction"):
    def score(n: Int) = Question.score("rate", (1 to n).map(i => s"l$i"))
    assert(score(1).validated.isInvalid)
    assert(score(2).validated.isValid)
    assert(score(10).validated.isValid)
    assert(score(11).validated.isInvalid)

  test("toEither collapses accumulated failures into one Invalid"):
    Question.noul("", "", "").toEither match
      case Left(Verdict4sError.Invalid(errors)) =>
        assertEquals(errors.length, 3L)
      case other => fail(s"expected Invalid, got $other")

  test("a noul answer decodes to NoulAnswer"):
    val decode = Question.noul("urgent?").toEither.toOption.get.decode
    assertEquals(decode(Answer.Noul(p(0.95))), Right(NoulAnswer(p(0.95))))

  test("the wrong answer shape is a decoding failure naming both types"):
    val decode = Question.noul("urgent?").toEither.toOption.get.decode
    val err = decode(
      Answer.Score(1.0, SortedMap(0 -> "a"), SortedMap(0 -> p(1.0)), c(1.0))
    ).swap.toOption.get
    assert(err.getMessage.contains("noul"), err.getMessage)
    assert(err.getMessage.contains("score"), err.getMessage)

  test("a choice answer decodes into the option type"):
    val q =
      Question.choiceOfStrings("team?", Seq("billing" -> None, "sales" -> None))
    val decode = q.toEither.toOption.get.decode
    val answer = Answer.Choice(
      n("billing"),
      SortedMap(n("billing") -> p(0.9), n("sales") -> p(0.1)),
      c(0.8)
    )
    assertEquals(decode(answer).map(_.choice), Right("billing"))

  test(
    "an option the service invented is a decoding failure listing the real ones"
  ):
    val q =
      Question.choiceOfStrings("team?", Seq("billing" -> None, "sales" -> None))
    val decode = q.toEither.toOption.get.decode
    val answer =
      Answer.Choice(n("legal"), SortedMap(n("legal") -> p(1.0)), c(1.0))
    val err = decode(answer).swap.toOption.get
    assert(err.getMessage.contains("legal"), err.getMessage)
    assert(err.getMessage.contains("billing"), err.getMessage)

  test("a score answer decodes to ScoreAnswer"):
    val q =
      Question.score("frustration?", Seq("Calm", "Frustrated", "Very angry"))
    val decode = q.toEither.toOption.get.decode
    val answer = Answer.Score(
      1.05,
      SortedMap(0 -> "Calm", 1 -> "Frustrated", 2 -> "Very angry"),
      SortedMap(0 -> p(0.0), 1 -> p(0.95), 2 -> p(0.05)),
      c(0.92)
    )
    assertEquals(decode(answer).map(_.nearestLevel), Right(1))

  test("an invalid question never produces a spec"):
    assertEquals(Question.invalid[NoulAnswer]("q", "nope").spec, None)
