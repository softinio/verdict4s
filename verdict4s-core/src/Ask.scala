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

import scala.collection.immutable.SortedMap

import cats.data.NonEmptyChain
import cats.data.NonEmptyList
import cats.data.ValidatedNec
import cats.syntax.all.*
import com.softinio.verdict4s.algebra.*
import io.circe.Encoder
import io.github.iltotore.iron.*

/** A set of questions asked together, answered at exactly the right types.
  *
  * One request carrying several questions is both cheaper and faster than one
  * request each, and it is the shape the service is designed around. `Ask`
  * keeps that batching while preserving each question's own answer type:
  *
  * ```scala
  * val (urgent, dept, mood) = client.ask(
  *   Ask(
  *     Question.noul("Does this convey urgency?"),
  *     Question.choice[Dept]("Which team should handle this?"),
  *     Question.score("How frustrated?", Seq("Calm", "Cross", "Livid"))
  *   ),
  *   state
  * )
  * ```
  *
  * `urgent` is a `NoulAnswer`, `dept` a `ChoiceAnswer[Dept]` and `mood` a
  * `ScoreAnswer` -- destructured positionally, with no casts and no lookups by
  * string. The map-based [[algebra.Evaluation]] remains available for cases
  * where the questions are not known statically.
  *
  * Question keys are generated positionally, so callers never name them. Use
  * [[withKeys]] if you need them to match something else.
  *
  * @group Questions
  */
final class Ask[Q <: Tuple] private (
    private val questions: List[Question[Any]],
    private val model: Option[Model],
    private val keyFor: Int => String
):

  /** Ask these questions of a specific model rather than the client default. */
  def withModel(m: Model): Ask[Q] = new Ask(questions, Some(m), keyFor)

  /** Choose the question keys yourself, by position.
    *
    * Keys must be non-blank and distinct: a repeated key would merge two
    * questions into one, so [[request]] rejects it before anything is sent.
    */
  def withKeys(f: Int => String): Ask[Q] = new Ask(questions, model, f)

  /** The model override, if one was set. */
  def modelOverride: Option[Model] = model

  /** Every question validated once, with failures accumulated across the whole
    * tuple rather than stopping at the first bad one.
    */
  def specs: ValidatedNec[Verdict4sError.Validation, List[Question.Spec[?]]] =
    questions.traverse(_.validated)

  /** Each question's key and validated spec, checked once per `Ask`: every key
    * usable and no two the same, since one map entry per key would silently
    * merge two questions into one.
    */
  private lazy val entries: ValidatedNec[
    Verdict4sError.Validation,
    List[(QuestionKey, Question.Spec[?])]
  ] =
    questions.zipWithIndex
      .traverse((q, i) => (q.validated, key(i)).mapN((s, k) => k -> s))
      .andThen: es =>
        val keys = es.map(_._1)
        val repeated = keys.diff(keys.distinct).distinct
        if repeated.isEmpty then es.validNec
        else
          Verdict4sError
            .Validation(
              "questions",
              "question keys must be distinct, but " +
                repeated.map(k => s"'${k.value: String}'").mkString(", ") +
                (if repeated.sizeIs > 1 then " are" else " is") +
                " used more than once"
            )
            .invalidNec

  /** Build the request, or report every problem with it at once. */
  def request[S: Encoder](
      state: S,
      defaultModel: Model
  ): ValidatedNec[Verdict4sError.Validation, EvaluationRequest] =
    entries
      .andThen: es =>
        SortedMap
          .from(es.map((k, spec) => k -> spec.wire))
          .refineEither[QuestionsC]
          .leftMap(_ =>
            NonEmptyChain.one(
              Verdict4sError
                .Validation("questions", "ask at least one question")
            )
          )
          .toValidated
      .map(qs =>
        EvaluationRequest(
          Encoder[S].apply(state),
          model.getOrElse(defaultModel),
          qs
        )
      )

  /** Project a response back into the positional answer tuple.
    *
    * A refused question fails the whole projection with
    * [[Verdict4sError.Refused]], naming every refused question at once and
    * carrying the full response, unless the question was built with
    * `orRefused`, in which case it reads as `None`. Any other problem is
    * reported as the first one found.
    *
    * Its cast, and the one in [[Ask.apply]], are justified by the same fact:
    * the list is built by walking the very tuple that [[Ask.Answers]] is
    * computed from, in the same order, so element `i` has the type the match
    * type assigns to position `i`.
    */
  def answers(evaluation: Evaluation): Either[Verdict4sError, Ask.Answers[Q]] =
    entries.toEither
      .leftMap(Verdict4sError.Invalid(_))
      .flatMap: es =>
        val results = es.map((k, spec) =>
          k -> evaluation
            .answer(k)
            .toRight(
              Verdict4sError.Decoding(
                s"the service sent no answer for '${k.value: String}'"
              )
            )
            .flatMap(spec.decode)
        )
        val refused = results.collect:
          case (k, Left(_)) if evaluation.answer(k).contains(Answer.Refused) =>
            k
        NonEmptyList.fromList(refused) match
          case Some(keys) => Left(Verdict4sError.Refused(keys, evaluation))
          case None       =>
            results
              .traverse(_._2)
              .map(vs =>
                Tuple.fromArray(vs.toArray).asInstanceOf[Ask.Answers[Q]]
              )

  private def key(
      index: Int
  ): ValidatedNec[Verdict4sError.Validation, QuestionKey] =
    val raw = keyFor(index)
    QuestionKey
      .either(raw)
      .leftMap(_ =>
        NonEmptyChain.one(
          Verdict4sError
            .Validation("questions", s"'$raw' is not a usable question key")
        )
      )
      .toValidated

/** Building an [[Ask]] from one or more questions, and the type that computes
  * its answers.
  */
object Ask:

  /** The positionally exact answer tuple for a tuple of questions.
    *
    * `Tuple.InverseMap` is what does the work: given
    * `(Question[A], Question[B])` it reduces to `(A, B)`. It only reduces
    * because every `Question.*` constructor returns the opaque `Question[X]`
    * itself, uniformly and with an explicit return type.
    */
  type Answers[Q <: Tuple] = Tuple.InverseMap[Q, Question]

  /** Ask several questions together.
    *
    * ```scala
    * Ask(urgency, team, frustration) // or Ask((urgency, team, frustration))
    * ```
    *
    * One method rather than an overload per arity: Scala tuples the arguments
    * for you. It does not clash with the single-question overload below, whose
    * erased signature differs by the evidence parameter.
    *
    * `IsMappedBy` is what rejects a tuple holding something that is not a
    * question.
    *
    * There is no arity limit of ours. Jev documents none either -- it caps
    * Choice options and Score levels, not how many questions a request carries,
    * and its own examples batch more than a dozen, since one request is
    * markedly cheaper and faster than several. OpenAI Decisions caps a request
    * at 200 questions, which its protocol checks before sending.
    *
    * In practice Scala's tuple-literal syntax stops at 22, which is also the
    * largest result a caller can unpack with `val (a, b, ...) = `. Beyond that,
    * build the tuple with `*:` and pass it here.
    */
  def apply[Q <: Tuple](
      questions: Q
  )(using Tuple.IsMappedBy[Question][Q]): Ask[Q] =
    new Ask(
      questions.productIterator.toList.map(_.asInstanceOf[Question[Any]]),
      None,
      defaultKey
    )

  /** Ask a single question.
    *
    * Needed separately because one argument cannot be tupled into a 1-tuple.
    */
  def apply[A](question: Question[A]): Ask[Question[A] *: EmptyTuple] =
    apply(question *: EmptyTuple)

  /** Positional question keys.
    *
    * Zero padded so that lexicographic order matches positional order for the
    * first hundred questions (`q100` sorts before `q11`), which keeps the
    * encoded request readable and its golden tests stable. Padded by hand
    * rather than with `f"q$i%02d"`, because Scala.js implements
    * `java.util.Formatter` only partially.
    */
  def defaultKey(index: Int): String =
    if index < 10 then s"q0$index" else s"q$index"
