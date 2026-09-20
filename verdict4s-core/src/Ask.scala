package com.softinio.verdict4s

import scala.collection.immutable.SortedMap

import cats.data.NonEmptyChain
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
  * ```scala sc:nocompile
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
    private val questions: Q,
    private val model: Option[Model],
    private val keyFor: Int => String
):

  /** Ask these questions of a specific model rather than the client default. */
  def withModel(m: Model): Ask[Q] = new Ask(questions, Some(m), keyFor)

  /** Choose the question keys yourself, by position. */
  def withKeys(f: Int => String): Ask[Q] = new Ask(questions, model, f)

  /** The model override, if one was set. */
  def modelOverride: Option[Model] = model

  /** Every question validated once, with failures accumulated across the whole
    * tuple rather than stopping at the first bad one.
    */
  def specs: ValidatedNec[Verdict4sError.Validation, List[Question.Spec[?]]] =
    questions.productIterator.toList
      .map(_.asInstanceOf[Question[Any]])
      .traverse(_.validated)

  /** Build the request, or report every problem with it at once. */
  def request[S: Encoder](
      state: S,
      defaultModel: Model
  ): ValidatedNec[Verdict4sError.Validation, EvaluationRequest] =
    specs.andThen: ss =>
      ss.zipWithIndex
        .traverse((spec, i) => key(i).map(_ -> spec.wire))
        .andThen: entries =>
          SortedMap
            .from(entries)
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
    * This is the only place in the library that casts. Both are justified by
    * the same fact: the list is built by walking the very tuple that
    * [[Ask.Answers]] is computed from, in the same order, so element `i` has
    * the type the match type assigns to position `i`.
    */
  def answers(evaluation: Evaluation): Either[Verdict4sError, Ask.Answers[Q]] =
    for
      ss <- specs.toEither.leftMap(Verdict4sError.Invalid(_))
      vs <- ss.zipWithIndex.traverse((spec, i) => answerAt(evaluation, spec, i))
    yield Tuple.fromArray(vs.toArray).asInstanceOf[Ask.Answers[Q]]

  private def answerAt(
      evaluation: Evaluation,
      spec: Question.Spec[?],
      index: Int
  ): Either[Verdict4sError, Any] =
    val raw = keyFor(index)
    QuestionKey
      .either(raw)
      .leftMap(_ =>
        Verdict4sError.Decoding(s"'$raw' is not a usable question key")
      )
      .flatMap: k =>
        evaluation
          .answer(k)
          .toRight(
            Verdict4sError.Decoding(s"the service sent no answer for '$raw'")
          )
      .flatMap(spec.decode)

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

object Ask:

  /** There is no arity limit of ours. The service documents none either -- it
    * caps Choice options and Score levels, not how many questions a request
    * carries, and its own examples batch more than a dozen, since one request
    * is markedly cheaper and faster than several.
    *
    * In practice Scala's tuple-literal syntax stops at 22, which is also the
    * largest result a caller can unpack with `val (a, b, ...) = `. Beyond that,
    * build the tuple with `*:` and pass it to [[apply]].
    */
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
    */
  def apply[Q <: Tuple](
      questions: Q
  )(using Tuple.IsMappedBy[Question][Q]): Ask[Q] =
    new Ask(questions, None, defaultKey)

  /** Ask a single question.
    *
    * Needed separately because one argument cannot be tupled into a 1-tuple.
    */
  def apply[A](question: Question[A]): Ask[Question[A] *: EmptyTuple] =
    new Ask(question *: EmptyTuple, None, defaultKey)

  /** Positional question keys.
    *
    * Zero padded so that lexicographic order matches positional order, which
    * keeps the encoded request readable and its golden tests stable. Padded by
    * hand rather than with `f"q$i%02d"`, because Scala.js implements
    * `java.util.Formatter` only partially.
    */
  def defaultKey(index: Int): String =
    if index < 10 then s"q0$index" else s"q$index"
