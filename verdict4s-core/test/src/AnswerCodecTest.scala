package com.softinio.verdict4s.algebra

import scala.collection.immutable.SortedMap

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*

class AnswerCodecTest extends munit.FunSuite:

  private def golden(raw: String): Json = parse(raw).toOption.get
  private def p(d: Double): Probability = Probability.either(d).toOption.get
  private def c(d: Double): Confidence = Confidence.either(d).toOption.get
  private def n(s: String): OptionName = OptionName.either(s).toOption.get

  test("decodes the documented noul answer"):
    assertEquals(
      golden("""{"type":"noul","noul":0.95}""").as[Answer],
      Right(Answer.Noul(p(0.95)))
    )

  test("a noul answer carries no confidence and must not require one"):
    // The service reports confidence for choice and score only; demanding it
    // here would reject every valid noul answer.
    assert(golden("""{"type":"noul","noul":0.5}""").as[Answer].isRight)

  test("decodes the documented choice answer"):
    assertEquals(
      golden("""
        {
          "type": "choice",
          "choice": "billing",
          "probabilities": {"billing": 0.88, "technical": 0.12, "sales": 0.0},
          "confidence": 0.81
        }
      """).as[Answer],
      Right(
        Answer.Choice(
          n("billing"),
          SortedMap(
            n("billing") -> p(0.88),
            n("sales") -> p(0.0),
            n("technical") -> p(0.12)
          ),
          c(0.81)
        )
      )
    )

  test("decodes the documented score answer"):
    assertEquals(
      golden("""
        {
          "type": "score",
          "score": 1.05,
          "legend": {"0": "Calm", "1": "Frustrated", "2": "Very angry"},
          "probabilities": {"0": 0.0, "1": 0.95, "2": 0.05},
          "confidence": 0.92
        }
      """).as[Answer],
      Right(
        Answer.Score(
          1.05,
          SortedMap(0 -> "Calm", 1 -> "Frustrated", 2 -> "Very angry"),
          SortedMap(0 -> p(0.0), 1 -> p(0.95), 2 -> p(0.05)),
          c(0.92)
        )
      )
    )

  test("score level keys decode to Int, so they order numerically"):
    // As strings, "10" would sort before "2". Level indices are numbers, and
    // decoding them as numbers is faithful to the documented meaning.
    val legend = (0 to 10).map(i => s""""$i": "L$i"""").mkString(",")
    val probs = (0 to 10).map(i => s""""$i": 0.0""").mkString(",")
    val json = golden(
      s"""{"type":"score","score":0.0,"legend":{$legend},"probabilities":{$probs},"confidence":1.0}"""
    )
    val decoded = json.as[Answer].toOption.get.asInstanceOf[Answer.Score]
    assertEquals(decoded.legend.keys.toList, (0 to 10).toList)

  test("a non-numeric level key is a decoding failure"):
    val json = golden(
      """{"type":"score","score":0.0,"legend":{"low":"Calm"},"probabilities":{"low":1.0},"confidence":1.0}"""
    )
    assert(json.as[Answer].isLeft)

  test("an unknown answer type is a decoding failure naming the type"):
    val result = golden("""{"type":"ranking","order":[1,2]}""").as[Answer]
    assert(result.isLeft)
    assert(
      result.left.toOption.get.getMessage.contains("ranking"),
      result.left.toOption.get.getMessage
    )

  test("a probability outside the unit interval is rejected"):
    // Deliberately strict: a value the service should never send indicates a
    // protocol change, and silently accepting it would hide that.
    assert(golden("""{"type":"noul","noul":1.5}""").as[Answer].isLeft)
    assert(golden("""{"type":"noul","noul":-0.1}""").as[Answer].isLeft)

  test("a missing required field is a decoding failure"):
    assert(golden("""{"type":"choice","choice":"a"}""").as[Answer].isLeft)
    assert(golden("""{"type":"noul"}""").as[Answer].isLeft)

  test("every answer round trips through its own codec"):
    val answers = List(
      Answer.Noul(p(0.95)),
      Answer.Choice(n("billing"), SortedMap(n("billing") -> p(1.0)), c(0.81)),
      Answer.Score(
        1.05,
        SortedMap(0 -> "Calm"),
        SortedMap(0 -> p(1.0)),
        c(0.92)
      )
    )
    answers.foreach(a => assertEquals(a.asJson.as[Answer], Right(a)))

  test("isTrue thresholds where the caller says"):
    assert(NoulAnswer(p(0.95)).isTrue())
    assert(!NoulAnswer(p(0.4)).isTrue())
    assert(NoulAnswer(p(0.4)).isTrue(threshold = 0.3))
    assert(!NoulAnswer(p(0.4)).isTrue(threshold = 0.9))

  test("ScoreAnswer maps the nearest level back to its description"):
    val a = ScoreAnswer(
      1.05,
      SortedMap(0 -> "Calm", 1 -> "Frustrated", 2 -> "Very angry"),
      SortedMap(0 -> p(0.0), 1 -> p(0.95), 2 -> p(0.05)),
      c(0.92)
    )
    assertEquals(a.nearestLevel, 1)
    assertEquals(a.levelDescription, Some("Frustrated"))

  test("ChoiceAnswer reads the probability of a given option"):
    given Options[String] = Options.of("billing", "sales").toOption.get
    val a = ChoiceAnswer(
      "billing",
      SortedMap(n("billing") -> p(0.88), n("sales") -> p(0.12)),
      c(0.81)
    )
    assertEquals(a.probabilityFor("billing"), Some(p(0.88)))
    assertEquals(a.probabilityFor("sales"), Some(p(0.12)))
