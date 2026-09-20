package com.softinio.verdict4s.algebra

import scala.collection.immutable.SortedMap

import cats.Eq
import cats.Show
import cats.data.NonEmptyChain
import cats.data.ValidatedNec
import cats.syntax.all.*
import com.softinio.verdict4s.Verdict4sError
import io.github.iltotore.iron.*

/** The option set of a Choice question, plus how to read the answer back.
  *
  * Holds three things: the rubric map that goes on the wire, and the pair of
  * functions that turn the service's `choice` string into a value of `A` and
  * back. Keeping the mapping next to the keys is what lets a Choice question
  * answer in the caller's own type rather than in raw strings.
  *
  * Build one with [[Options.strings]] for options only known at runtime, or
  * derive it from an `enum` (see `derives Options`, added alongside the typed
  * question builders).
  *
  * @group Protocol
  */
final class Options[A] private (
    val keys: Options.Keys,
    private val decodeKey: String => Option[A],
    private val encodeKey: A => OptionName
):

  /** Read one of the service's `choice` strings back into `A`. */
  def parse(choice: String): Option[A] = decodeKey(choice)

  /** Render a value as the option name that goes on the wire. */
  def render(value: A): OptionName = encodeKey(value)

  /** Every option name, in the order they were declared. */
  def names: List[OptionName] = keys.keys.toList

  /** How many options this question offers. */
  def size: Int = keys.size

  override def toString: String =
    s"Options(${names.map(n => n.value: String).mkString(", ")})"

object Options:

  /** The rubric map as it goes on the wire.
    *
    * `SortedMap` so the encoded JSON is deterministic, which keeps the golden
    * tests stable and identical on the JVM and on Node. The refinement carries
    * the API's own limit of 1 to 255 options.
    */
  type Keys = SortedMap[OptionName, Option[Instructions]] :| ChoiceOptionsC

  /** An option set whose names are only known at runtime.
    *
    * @param entries
    *   option name to rubric; a `None` rubric is meaningful and is sent as an
    *   explicit `null`
    */
  def strings(
      entries: Seq[(String, Option[Instructions])]
  ): ValidatedNec[Verdict4sError.Validation, Options[String]] =
    entries.toList
      .traverse((name, rubric) => validName(name).map(_ -> rubric))
      .andThen: pairs =>
        val byName =
          pairs.map(_._1).map(n => (n.value: String) -> n).toMap
        fromPairs[String](
          pairs,
          s => byName.get(s).map(n => n.value: String),
          s => byName.getOrElse(s, OptionName.applyUnsafe(s))
        )

  /** An option set with no rubric text, for when the names speak for
    * themselves.
    */
  def of(
      names: String*
  ): ValidatedNec[Verdict4sError.Validation, Options[String]] =
    strings(names.map(_ -> None))

  /** Build from names already known to be well formed, with a mapping to `A`.
    *
    * Used by the `enum` derivation, where the labels come from a `Mirror` and
    * cannot be blank.
    */
  private[verdict4s] def build[A](
      labels: List[OptionName],
      values: List[A],
      rubrics: Map[String, Instructions] = Map.empty
  ): ValidatedNec[Verdict4sError.Validation, Options[A]] =
    val byName = labels.map(l => (l.value: String)).zip(values).toMap
    val byValue = values.zip(labels).toMap
    fromPairs(
      labels.map(l => l -> rubrics.get(l.value: String)),
      byName.get,
      byValue.apply
    )

  private def fromPairs[A](
      pairs: List[(OptionName, Option[Instructions])],
      decodeKey: String => Option[A],
      encodeKey: A => OptionName
  ): ValidatedNec[Verdict4sError.Validation, Options[A]] =
    val duplicates =
      pairs.groupBy(_._1).collect { case (k, vs) if vs.sizeIs > 1 => k }.toList
    val distinct: ValidatedNec[Verdict4sError.Validation, Unit] =
      if duplicates.isEmpty then ().validNec
      else
        Verdict4sError
          .Validation(
            "criteria",
            s"duplicate options: ${duplicates.map(d => d.value: String).sorted.mkString(", ")}"
          )
          .invalidNec

    val sorted = SortedMap.from(pairs)
    val bounded: ValidatedNec[Verdict4sError.Validation, Keys] =
      sorted
        .refineEither[ChoiceOptionsC]
        .leftMap(_ => NonEmptyChain.one(cardinality(sorted.size)))
        .toValidated

    (distinct, bounded).mapN((_, keys) =>
      new Options(keys, decodeKey, encodeKey)
    )

  private def cardinality(n: Int): Verdict4sError.Validation =
    Verdict4sError.Validation(
      "criteria",
      s"a choice question accepts 1 to 255 options, got $n"
    )

  private def validName(
      raw: String
  ): ValidatedNec[Verdict4sError.Validation, OptionName] =
    OptionName
      .either(raw)
      .leftMap(_ =>
        NonEmptyChain.one(
          Verdict4sError.Validation("criteria", "option name must not be blank")
        )
      )
      .toValidated

  given [A]: Eq[Options[A]] = Eq.by(_.names)
  given [A]: Show[Options[A]] = Show.fromToString
