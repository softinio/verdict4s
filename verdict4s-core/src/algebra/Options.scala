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
import scala.compiletime.constValue
import scala.compiletime.constValueTuple
import scala.compiletime.summonAll
import scala.deriving.Mirror
import scala.compiletime.constValue
import scala.compiletime.constValueTuple
import scala.compiletime.summonAll
import scala.deriving.Mirror

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
    /** The option names and their rubrics, as they go on the wire. */
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

/** Building an [[Options]] set: from strings at runtime, or derived from an
  * `enum` with `derives Options`.
  */
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

  /** Derive an option set from a Scala 3 `enum`.
    *
    * ```scala
    * enum Dept derives Options:
    *   case Billing, Technical, Sales
    * ```
    *
    * The case labels become the option names, in declaration order, and a
    * Choice question built from this answers in `Dept` rather than in strings.
    * Use [[describing]] when the names alone are not enough of a rubric.
    *
    * Only enums whose cases are all singletons can be derived: a case with
    * parameters has no single name to send.
    */
  inline def derived[A](using m: Mirror.SumOf[A]): Options[A] =
    fromMirror[A](Map.empty)

  /** Derive from an `enum`, with rubric text for some or all of its cases. */
  inline def describing[A](
      rubrics: Map[String, Instructions]
  )(using m: Mirror.SumOf[A]): Options[A] =
    fromMirror[A](rubrics)

  private inline def fromMirror[A](
      rubrics: Map[String, Instructions]
  )(using m: Mirror.SumOf[A]): Options[A] =
    // The cardinality is statically known here, so an option set that could
    // never be sent is a compile error rather than a runtime surprise.
    inline if constValue[Tuple.Size[m.MirroredElemTypes]] > 255 then
      compiletime.error("a choice question accepts at most 255 options")
    // `.toList` on a constValueTuple leaks Tuple.Union through the Mirror
    // proxy and fails on 3.3 while compiling fine on 3.9; productIterator
    // sidesteps the union entirely.
    val labels = constValueTuple[m.MirroredElemLabels].productIterator.toList
      .map(_.asInstanceOf[String])
    val values =
      summonAll[Tuple.Map[m.MirroredElemTypes, ValueOf]].productIterator.toList
        .map(_.asInstanceOf[ValueOf[A]].value)
    build(labels.map(OptionName.applyUnsafe), values, rubrics)
      .valueOr(errors => throw errors.head)

  given [A]: Eq[Options[A]] = Eq.by(_.names)
  given [A]: Show[Options[A]] = Show.fromToString
