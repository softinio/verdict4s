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

import cats.data.NonEmptyChain
import cats.data.ValidatedNec
import cats.syntax.all.*
import com.softinio.verdict4s.Verdict4sError
import io.circe.JsonObject
import scala.deriving.Mirror

/** A question, together with the way to read its answer back at the right type.
  *
  * Two things are bundled here, and both matter:
  *
  *   - the [[QuestionSpec]] that goes on the wire, already validated. Every
  *     constructor returns `Question[A]` whether or not it had a rule to check,
  *     so a tuple of questions is uniform in its wrapper.
  *   - a decoder. Carrying it here, where `A` is statically known, is what lets
  *     `Ask` project answers back at exact types without a GADT match — and
  *     what makes [[Question.map]] and [[Question.emap]] one-liners.
  *
  * Validation is accumulated rather than fail-fast, so a request with three
  * malformed questions reports all three:
  *
  * ```scala
  * val q = Question.choiceOfStrings("Which team?", Seq("billing" -> None))
  * ```
  *
  * @group Questions
  */
opaque type Question[A] =
  ValidatedNec[Verdict4sError.Validation, Question.Spec[A]]

/** Constructors for every question type, and the combinators that change how an
  * answer is read.
  *
  * Every constructor returns a validated `Question[A]`: it never throws, and
  * problems accumulate rather than stopping at the first. `A` is what the
  * answer decodes to -- `NoulAnswer`, `ChoiceAnswer[D]` for an option type `D`,
  * or `ScoreAnswer` -- and [[map]] or [[emap]] turn it into a type of your own.
  */
object Question:

  /** A validated question paired with its answer decoder. */
  final case class Spec[A](
      wire: QuestionSpec,
      decode: Answer => Either[Verdict4sError, A]
  )

  // -- Noul ----------------------------------------------------------------

  /** A yes/no question. */
  def noul(instructions: String): Question[NoulAnswer] =
    Instructions.text(instructions).map(i => noulSpec(i, None))

  /** A yes/no question that says what a yes and a no mean. */
  def noul(
      instructions: String,
      ifTrue: String,
      ifFalse: String
  ): Question[NoulAnswer] =
    (
      Instructions.text(instructions),
      Instructions.text(ifTrue),
      Instructions.text(ifFalse)
    ).mapN((i, t, f) => noulSpec(i, Some(NoulCriteria(Some(t), Some(f)))))

  /** A yes/no question built from structured instructions. */
  def noulOf(
      instructions: Instructions,
      criteria: Option[NoulCriteria] = None
  ): Question[NoulAnswer] =
    noulSpec(instructions, criteria).validNec

  // -- Choice --------------------------------------------------------------

  /** Pick one option from a set, answering in the caller's own type. */
  def choice[A](
      instructions: String
  )(using options: Options[A]): Question[ChoiceAnswer[A]] =
    Instructions.text(instructions).map(i => choiceSpec(i, options))

  /** Pick one option, deriving the set from an `enum` on the spot.
    *
    * Equivalent to `choice[A]` with a derived [[Options]], for an enum that
    * does not carry a `derives Options` clause.
    */
  inline def choiceOf[A](
      instructions: String
  )(using Mirror.SumOf[A]): Question[ChoiceAnswer[A]] =
    choice[A](instructions)(using Options.derived[A])

  /** Pick one option from a set whose names are only known at runtime. */
  def choiceOfStrings(
      instructions: String,
      options: Seq[(String, Option[Instructions])]
  ): Question[ChoiceAnswer[String]] =
    (Instructions.text(instructions), Options.strings(options))
      .mapN((i, o) => choiceSpec(i, o))

  // -- Score ---------------------------------------------------------------

  /** Rate against ordered levels, lowest first. */
  def score(instructions: String, levels: Seq[String]): Question[ScoreAnswer] =
    (Instructions.text(instructions), Levels.descriptionsOf(levels))
      .mapN((i, l) => scoreSpec(i, l))

  /** Rate against levels described by a [[Levels]] instance. */
  def scoreOf[A](
      instructions: String
  )(using levels: Levels[A]): Question[ScoreAnswer] =
    Instructions.text(instructions).map(i => scoreSpec(i, levels.descriptions))

  /** Rate against levels derived from an `enum` on the spot. */
  inline def scoreOfEnum[A](
      instructions: String
  )(using Mirror.SumOf[A]): Question[ScoreAnswer] =
    scoreOf[A](instructions)(using Levels.derived[A])

  // -- Escape hatches ------------------------------------------------------

  /** Send a question shape this release does not model.
    *
    * Forward compatibility: if the service grows a fourth question type, this
    * lets you use it immediately rather than wait for a library version.
    */
  def raw[A](questionType: String, body: JsonObject)(
      decode: Answer => Either[Verdict4sError, A]
  ): Question[A] =
    Spec(QuestionSpec.Raw(questionType, body), decode).validNec

  /** A question that is already known to be invalid. Mostly useful in tests. */
  def invalid[A](field: String, details: String): Question[A] =
    Verdict4sError.Validation(field, details).invalidNec

  // -- Syntax --------------------------------------------------------------

  extension [A](q: Question[A])

    /** The accumulated validation result. */
    def validated: ValidatedNec[Verdict4sError.Validation, Spec[A]] = q

    /** Collapse accumulated failures into one raisable error. */
    def toEither: Either[Verdict4sError, Spec[A]] =
      (q: ValidatedNec[Verdict4sError.Validation, Spec[A]]).toEither
        .leftMap(Verdict4sError.Invalid(_))

    /** The question as it will be sent, if it is valid. */
    def spec: Option[QuestionSpec] =
      (q: ValidatedNec[Verdict4sError.Validation, Spec[A]]).toOption.map(_.wire)

    /** Post-process the answer into your own type.
      *
      * The wire request is untouched; only the way the answer is read changes.
      */
    def map[B](f: A => B): Question[B] =
      (q: ValidatedNec[Verdict4sError.Validation, Spec[A]]).map(s =>
        Spec(s.wire, a => s.decode(a).map(f))
      )

    /** Post-process the answer, with the option to reject it.
      *
      * Use this when your type cannot represent every answer the service might
      * give — a failure surfaces as a `Verdict4sError`, exactly as a malformed
      * response would.
      */
    def emap[B](f: A => Either[Verdict4sError, B]): Question[B] =
      (q: ValidatedNec[Verdict4sError.Validation, Spec[A]]).map(s =>
        Spec(s.wire, a => s.decode(a).flatMap(f))
      )

  /** Erase the answer type, for the places that only need the wire shape. */
  private[verdict4s] def widen[A](q: Question[A]): Question[Any] =
    (q: ValidatedNec[Verdict4sError.Validation, Spec[A]])
      .map(s => Spec[Any](s.wire, a => s.decode(a)))

  // -- Internals -----------------------------------------------------------

  private def noulSpec(
      instructions: Instructions,
      criteria: Option[NoulCriteria]
  ): Spec[NoulAnswer] =
    Spec(
      QuestionSpec.Noul(instructions, criteria),
      {
        case Answer.Noul(value) => Right(NoulAnswer(value))
        case other              => Left(mismatch("noul", other))
      }
    )

  private def choiceSpec[A](
      instructions: Instructions,
      options: Options[A]
  ): Spec[ChoiceAnswer[A]] =
    Spec(
      QuestionSpec.Choice(instructions, options.keys),
      {
        case Answer.Choice(choice, probabilities, confidence) =>
          options
            .parse(choice.value: String)
            .toRight(
              Verdict4sError.Decoding(
                s"the service chose '${choice.value: String}', " +
                  s"which is not one of ${options.names.map(n => n.value: String).mkString(", ")}",
                Some("choice")
              )
            )
            .map(ChoiceAnswer(_, probabilities, confidence))
        case other => Left(mismatch("choice", other))
      }
    )

  private def scoreSpec(
      instructions: Instructions,
      levels: Levels.Descriptions
  ): Spec[ScoreAnswer] =
    Spec(
      QuestionSpec.Score(instructions, levels),
      {
        case Answer.Score(score, legend, probabilities, confidence) =>
          Right(ScoreAnswer(score, legend, probabilities, confidence))
        case other => Left(mismatch("score", other))
      }
    )

  private def mismatch(expected: String, got: Answer): Verdict4sError =
    Verdict4sError.Decoding(
      s"expected a $expected answer but the service sent a ${got.wireType} one",
      Some("type")
    )
