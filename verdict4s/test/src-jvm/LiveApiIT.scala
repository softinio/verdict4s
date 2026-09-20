package com.softinio.verdict4s

import scala.concurrent.duration.*

import cats.effect.IO
import com.softinio.verdict4s.algebra.*
import munit.CatsEffectSuite

/** Integration tests against the real TypeSafe service.
  *
  * Gated on `TYPESAFE_API_KEY`: with no key the whole suite reports as ignored,
  * so `mill __.test` and CI stay green and make no network call. To run it,
  * export the key and run the bundle's JVM tests.
  *
  * It lives in `test/src-jvm` because `sys.env` does not exist on Scala.js — in
  * shared sources this would not even compile.
  *
  * Assertions are on *shape*, never on model output. Asserting that a
  * particular ticket is judged urgent would make the suite fail whenever the
  * model is retrained, which says nothing about this library.
  */
class LiveApiIT extends CatsEffectSuite:

  private val key: Option[ApiKey] =
    sys.env
      .get(Defaults.ApiKeyEnv)
      .filterNot(_.isBlank)
      .flatMap(ApiKey.fromString(_).toOption)

  override def munitIgnore: Boolean = key.isEmpty
  override def munitTimeout: Duration = 60.seconds

  private def withClient[A](f: Verdict4sClient[IO] => IO[A]): IO[A] =
    Verdict4s.default[IO](key.get).use(f)

  enum Dept derives Options:
    case Billing, Technical, Sales

  private val ticket = "Help! My payouts have been failing for 3 days."

  test("a noul question comes back in range"):
    withClient(_.ask(Ask(Question.noul("Does this convey urgency?")), ticket))
      .map: answers =>
        val value: Double = answers.head.value.value
        assert(
          value >= 0.0 && value <= 1.0,
          s"probability out of range: $value"
        )

  test("a choice answer is one of the options we sent"):
    withClient(
      _.ask(
        Ask(Question.choice[Dept]("Which team should handle this?")),
        ticket
      )
    )
      .map: answers =>
        val answer = answers.head
        assert(Dept.values.contains(answer.choice))
        assertEquals(
          answer.probabilities.keySet.map(k => k.value: String),
          Dept.values.map(_.toString).toSet
        )
        val total = answer.probabilities.values.map(p => p.value: Double).sum
        assert(math.abs(total - 1.0) < 0.01, s"probabilities summed to $total")

  test("a score answer lands within the levels we defined"):
    val levels = Seq("Calm", "Frustrated", "Very angry")
    withClient(
      _.ask(
        Ask(Question.score("How frustrated is the customer?", levels)),
        ticket
      )
    )
      .map: answers =>
        val answer = answers.head
        assert(
          answer.score >= 0.0 && answer.score <= 2.0,
          s"score was ${answer.score}"
        )
        assertEquals(answer.legend.size, 3)
        assert(answer.levelDescription.exists(levels.contains))

  test("several questions in one request all come back, exactly typed"):
    val ask = Ask(
      Question.noul("Does this convey urgency?"),
      Question.choice[Dept]("Which team should handle this?"),
      Question.score("How frustrated?", Seq("Calm", "Frustrated", "Very angry"))
    )
    withClient(_.ask(ask, ticket)).map:
      case (urgency, dept, mood) =>
        val u: NoulAnswer = urgency
        val d: ChoiceAnswer[Dept] = dept
        val m: ScoreAnswer = mood
        assert((u.value.value: Double) >= 0.0)
        assert(Dept.values.contains(d.choice))
        assert(m.legend.nonEmpty)

  test("usage is reported for a request"):
    val request = Ask(Question.noul("urgent?"))
      .request(ticket, Model.JevLatest)
      .toOption
      .get
    withClient(_.evaluate(request)).map: evaluation =>
      assert(evaluation.usage.inputTokens > 0, "input tokens should be counted")
      assert(evaluation.usage.total > 0)

  test("the models endpoint lists something"):
    withClient(_.models).map(cards => assert(cards.nonEmpty))

  test("a bad key is reported as Unauthorized, with a request id"):
    val bogus = ApiKey.fromString("sk-definitely-not-a-real-key").toOption.get
    Verdict4s
      .default[IO](bogus)
      .use(_.ask(Ask(Question.noul("urgent?")), ticket))
      .attempt
      .map:
        case Left(e: Verdict4sError.Api) =>
          assertEquals(e.failure, ApiFailure.Unauthorized)
        case other => fail(s"expected an Unauthorized Api error, got $other")
