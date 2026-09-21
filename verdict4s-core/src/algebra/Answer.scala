package com.softinio.verdict4s.algebra

import scala.collection.immutable.SortedMap

import cats.Eq
import cats.Show
import io.circe.Decoder
import io.circe.DecodingFailure
import io.circe.Encoder
import io.circe.Json
import io.circe.syntax.*

/** One answer, exactly as it comes off the wire.
  *
  * Untyped in the sense that a Choice answer carries the option as a `String`:
  * this is the honest shape of the response, and it is what
  * `Evaluation.answers` exposes. The typed views below — [[NoulAnswer]],
  * [[ChoiceAnswer]], [[ScoreAnswer]] — are what the question builders project
  * this into once the question's own type is known.
  *
  * @group Protocol
  */
enum Answer:

  /** The probability that the answer is yes. Carries no confidence: the service
    * does not report one for Noul.
    */
  case Noul(noul: Probability)

  /** The chosen option, the full distribution, and how certain the model is. */
  case Choice(
      choice: OptionName,
      probabilities: SortedMap[OptionName, Probability],
      confidence: Confidence
  )

  /** A probability-weighted value across the levels; it can land between them.
    *
    * `legend` and `probabilities` are keyed by level index. The service sends
    * those keys as strings (`"0"`, `"1"`, …); decoding them to `Int` is
    * faithful to their documented meaning, and means `legend.head` is level 0
    * rather than level 10.
    */
  case Score(
      score: Double,
      legend: SortedMap[Int, String],
      probabilities: SortedMap[Int, Probability],
      confidence: Confidence
  )

  /** The value of the wire `type` field. */
  def wireType: String = this match
    case _: Noul   => "noul"
    case _: Choice => "choice"
    case _: Score  => "score"

/** Codecs and instances for [[Answer]]. */
object Answer:

  given Decoder[Answer] = Decoder.instance: c =>
    c.downField("type")
      .as[String]
      .flatMap:
        case "noul" =>
          c.downField("noul").as[Probability].map(Noul.apply)

        case "choice" =>
          for
            choice <- c.downField("choice").as[OptionName]
            probs <- c
              .downField("probabilities")
              .as[SortedMap[OptionName, Probability]]
            conf <- c.downField("confidence").as[Confidence]
          yield Choice(choice, probs, conf)

        case "score" =>
          for
            score <- c.downField("score").as[Double]
            legend <- c.downField("legend").as[SortedMap[Int, String]]
            probs <- c
              .downField("probabilities")
              .as[SortedMap[Int, Probability]]
            conf <- c.downField("confidence").as[Confidence]
          yield Score(score, legend, probs, conf)

        case other =>
          Left(
            DecodingFailure(s"unknown answer type '$other'", c.history)
          )

  given Encoder[Answer] = Encoder.instance:
    case Noul(noul) =>
      Json.obj("type" -> "noul".asJson, "noul" -> noul.asJson)

    case Choice(choice, probabilities, confidence) =>
      Json.obj(
        "type" -> "choice".asJson,
        "choice" -> choice.asJson,
        "probabilities" -> probabilities.asJson,
        "confidence" -> confidence.asJson
      )

    case Score(score, legend, probabilities, confidence) =>
      Json.obj(
        "type" -> "score".asJson,
        "score" -> score.asJson,
        "legend" -> legend.asJson,
        "probabilities" -> probabilities.asJson,
        "confidence" -> confidence.asJson
      )

  given Eq[Answer] = Eq.fromUniversalEquals
  given Show[Answer] = Show.show(_.asJson.noSpaces)

/** A Noul answer: how likely the answer is yes.
  *
  * @group Protocol
  */
final case class NoulAnswer(value: Probability):

  /** Whether to treat this as a yes.
    *
    * The threshold is yours to pick: the value is a calibrated probability, not
    * a verdict, so where you cut depends on the cost of each mistake.
    */
  def isTrue(threshold: Double = 0.5): Boolean =
    (value.value: Double) >= threshold

/** Instances for [[NoulAnswer]]. */
object NoulAnswer:
  given Eq[NoulAnswer] = Eq.fromUniversalEquals
  given Show[NoulAnswer] =
    Show.show(a => s"NoulAnswer(${a.value.value: Double})")

/** A Choice answer, already mapped into the caller's own option type.
  *
  * @group Protocol
  */
final case class ChoiceAnswer[A](
    choice: A,
    probabilities: SortedMap[OptionName, Probability],
    confidence: Confidence
):

  /** The probability assigned to one particular option. */
  def probabilityFor(value: A)(using options: Options[A]): Option[Probability] =
    probabilities.get(options.render(value))

/** Instances for [[ChoiceAnswer]]. */
object ChoiceAnswer:
  given [A]: Eq[ChoiceAnswer[A]] = Eq.fromUniversalEquals
  given [A]: Show[ChoiceAnswer[A]] = Show.fromToString

/** A Score answer.
  *
  * @group Protocol
  */
final case class ScoreAnswer(
    score: Double,
    legend: SortedMap[Int, String],
    probabilities: SortedMap[Int, Probability],
    confidence: Confidence
):

  /** The level the score is closest to. */
  def nearestLevel: Int = math.round(score).toInt

  /** The description of [[nearestLevel]]. */
  def levelDescription: Option[String] = legend.get(nearestLevel)

  /** [[nearestLevel]] mapped back into a level type. */
  def levelAs[A](using levels: Levels[A]): Option[A] = levels.at(nearestLevel)

/** Instances for [[ScoreAnswer]]. */
object ScoreAnswer:
  given Eq[ScoreAnswer] = Eq.fromUniversalEquals
  given Show[ScoreAnswer] = Show.fromToString
