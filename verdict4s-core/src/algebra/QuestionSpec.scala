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

import cats.Eq
import cats.Show
import io.circe.JsonObject

/** What a yes and a no mean, for a Noul question. Both sides are optional.
  *
  * @group Protocol
  */
final case class NoulCriteria(
    ifTrue: Option[Instructions],
    ifFalse: Option[Instructions]
):
  /** True when neither side was described, in which case the whole `criteria`
    * key is omitted from the request.
    */
  def isEmpty: Boolean = ifTrue.isEmpty && ifFalse.isEmpty

/** The empty criteria, and instances. */
object NoulCriteria:
  /** Neither side described. Encodes as no `criteria` key at all. */
  val empty: NoulCriteria = NoulCriteria(None, None)

  given Eq[NoulCriteria] = Eq.fromUniversalEquals
  given Show[NoulCriteria] = Show.fromToString

/** A question in the shape every supported service shares.
  *
  * Closed and non-generic on purpose: Jev and OpenAI Decisions both define
  * exactly these three shapes, under different names and encodings, so this is
  * the protocol rather than an extension point. Callers who want a question
  * that answers in their own type use `Question[A]`, which pairs one of these
  * with a decoder — see the typed question builders.
  *
  * [[QuestionSpec.Raw]] exists only for forward compatibility: it lets a caller
  * send a question shape a given release does not model yet, rather than wait
  * for a library version.
  *
  * @group Protocol
  */
enum QuestionSpec:

  /** A yes/no question. */
  case Noul(instructions: Instructions, criteria: Option[NoulCriteria])

  /** Pick one option from a defined set. */
  case Choice(instructions: Instructions, options: Options.Keys)

  /** Rate against ordered, described levels. */
  case Score(instructions: Instructions, levels: Levels.Descriptions)

  /** A question shape this release does not model. */
  case Raw(questionType: String, body: JsonObject)

  /** The name of this question's shape, as Jev spells it. */
  def wireType: String = this match
    case _: Noul   => "noul"
    case _: Choice => "choice"
    case _: Score  => "score"
    case r: Raw    => r.questionType

/** Instances for [[QuestionSpec]]. The wire encodings live with each vendor's
  * protocol: see `com.softinio.verdict4s.jev.JevCodecs` and
  * `com.softinio.verdict4s.openai.OpenAICodecs`.
  */
object QuestionSpec:
  given Eq[QuestionSpec] = Eq.fromUniversalEquals
  given Show[QuestionSpec] = Show.fromToString
