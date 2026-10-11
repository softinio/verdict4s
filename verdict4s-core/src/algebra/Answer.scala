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

import scala.collection.immutable.SortedMap

import cats.Eq
import cats.Show

/** One answer, as the service reported it.
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

  /** The service declined to answer this one question.
    *
    * Other questions in the same request can still be answered. Jev never
    * refuses; OpenAI Decisions may, without saying why. `Ask` turns a refusal
    * into `Verdict4sError.Refused` unless the question opted in with
    * `Question#orRefused`.
    */
  case Refused

  /** The name of this answer's shape, for error messages. */
  def wireType: String = this match
    case _: Noul   => "noul"
    case _: Choice => "choice"
    case _: Score  => "score"
    case Refused   => "refusal"

/** Instances for [[Answer]]. The wire codecs live with each vendor's protocol:
  * see `com.softinio.verdict4s.jev.JevCodecs` and
  * `com.softinio.verdict4s.openai.OpenAICodecs`.
  */
object Answer:
  given Eq[Answer] = Eq.fromUniversalEquals
  given Show[Answer] = Show.fromToString

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
