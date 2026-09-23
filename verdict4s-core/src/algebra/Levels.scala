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

/** The ordered rubric of a Score question, plus how to read a level back.
  *
  * Unlike [[Options]] this is a `List`, not a sorted map: the levels are
  * ordered, and their position *is* their meaning — the service returns level
  * indices as `"0"`, `"1"`, and so on, keyed to this order.
  *
  * @group Protocol
  */
final class Levels[A] private (
    /** The level descriptions as they go on the wire, lowest first. */
    val descriptions: Levels.Descriptions,
    private val values: Vector[A]
):

  /** The value at a level index, if the index is in range. */
  def at(index: Int): Option[A] = values.lift(index)

  /** The level index of a value, or -1 if it is not one of the levels. */
  def indexOf(value: A): Int = values.indexOf(value)

  /** How many levels this rubric has. */
  def size: Int = values.size

  override def toString: String = s"Levels(${size} levels)"

/** Building a [[Levels]] rubric: from strings at runtime, or derived from an
  * `enum` with `derives Levels`.
  */
object Levels:

  /** The ordered level descriptions as they go on the wire.
    *
    * The refinement carries the API's own limit of 2 to 10 levels; a Score with
    * one level cannot discriminate and is rejected at construction.
    */
  type Descriptions = List[Instructions] :| ScoreLevelsC

  /** Levels described by plain text, in order from lowest to highest. */
  def strings(
      levels: Seq[String]
  ): ValidatedNec[Verdict4sError.Validation, Levels[String]] =
    levels.toList
      .traverse(text)
      .andThen(is => bounded(is).map(d => new Levels(d, levels.toVector)))

  /** Just the wire-level descriptions, for a Score whose answer is read as a
    * number rather than mapped back to a level value.
    */
  def descriptionsOf(
      levels: Seq[String]
  ): ValidatedNec[Verdict4sError.Validation, Descriptions] =
    levels.toList.traverse(text).andThen(bounded)

  /** Build from descriptions already known to be well formed.
    *
    * Used by the `enum` derivation, where the labels come from a `Mirror`.
    */
  private[verdict4s] def build[A](
      descriptions: List[Instructions],
      values: List[A]
  ): ValidatedNec[Verdict4sError.Validation, Levels[A]] =
    bounded(descriptions).map(d => new Levels(d, values.toVector))

  private def bounded(
      levels: List[Instructions]
  ): ValidatedNec[Verdict4sError.Validation, Descriptions] =
    levels
      .refineEither[ScoreLevelsC]
      .leftMap(_ =>
        NonEmptyChain.one(
          Verdict4sError.Validation(
            "criteria",
            s"a score question accepts 2 to 10 levels, got ${levels.size}"
          )
        )
      )
      .toValidated

  private def text(
      raw: String
  ): ValidatedNec[Verdict4sError.Validation, Instructions] =
    Instructions
      .text(raw)
      .leftMap(_.map(v => v.copy(field = "criteria")))

  /** Derive an ordered level set from a Scala 3 `enum`.
    *
    * ```scala
    * enum Frustration derives Levels:
    *   case Calm, Frustrated, VeryAngry
    * ```
    *
    * Declaration order *is* rubric order, lowest first, and it is what the
    * service's level indices refer to. Reordering the cases changes the meaning
    * of every score already recorded, so treat that order as part of your data
    * model rather than as cosmetic.
    */
  inline def derived[A](using m: Mirror.SumOf[A]): Levels[A] =
    // Both bounds are statically known, so an enum that could never be a valid
    // score rubric fails at its declaration rather than at runtime.
    inline if constValue[Tuple.Size[m.MirroredElemTypes]] < 2 then
      compiletime.error("a score question needs at least 2 levels")
    inline if constValue[Tuple.Size[m.MirroredElemTypes]] > 10 then
      compiletime.error("a score question accepts at most 10 levels")
    val labels = constValueTuple[m.MirroredElemLabels].productIterator.toList
      .map(_.asInstanceOf[String])
    val values =
      summonAll[Tuple.Map[m.MirroredElemTypes, ValueOf]].productIterator.toList
        .map(_.asInstanceOf[ValueOf[A]].value)
    build(
      labels.map(l => Instructions.unsafe(io.circe.Json.fromString(l))),
      values
    ).valueOr(errors => throw errors.head)

  given [A]: Eq[Levels[A]] = Eq.by(_.descriptions.toList)
  given [A]: Show[Levels[A]] = Show.fromToString
