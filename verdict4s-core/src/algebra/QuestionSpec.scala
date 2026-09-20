package com.softinio.verdict4s.algebra

import cats.Eq
import cats.Show
import io.circe.Encoder
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*

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

object NoulCriteria:
  val empty: NoulCriteria = NoulCriteria(None, None)

  given Encoder[NoulCriteria] = Encoder.instance: c =>
    Json.fromFields(
      List(
        c.ifTrue.map(i => "true" -> i.toJson),
        c.ifFalse.map(i => "false" -> i.toJson)
      ).flatten
    )

  given Eq[NoulCriteria] = Eq.fromUniversalEquals
  given Show[NoulCriteria] = Show.fromToString

/** A question exactly as it goes on the wire.
  *
  * Closed and non-generic on purpose: the service defines these three shapes,
  * so this is the protocol rather than an extension point. Callers who want a
  * question that answers in their own type use `Question[A]`, which pairs one
  * of these with a decoder — see the typed question builders.
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

  /** The value of the wire `type` field. */
  def wireType: String = this match
    case _: Noul   => "noul"
    case _: Choice => "choice"
    case _: Score  => "score"
    case r: Raw    => r.questionType

object QuestionSpec:

  /** Hand-written rather than derived, because three details matter and a
    * generic derivation gets all three wrong:
    *
    *   - an absent Noul `criteria` means the key is absent, never `null`
    *   - a Choice rubric of `null` is meaningful and must be emitted as an
    *     explicit null *value* under its key, so `dropNullValues` is wrong
    *   - key order is deterministic, which is what makes the golden tests
    *     stable and identical on the JVM and on Node
    */
  given Encoder[QuestionSpec] = Encoder.instance:
    case Noul(instructions, criteria) =>
      val base =
        List("type" -> "noul".asJson, "instructions" -> instructions.toJson)
      val crit = criteria.filterNot(_.isEmpty).map(c => "criteria" -> c.asJson)
      Json.fromFields(base ++ crit)

    case Choice(instructions, options) =>
      Json.obj(
        "type" -> "choice".asJson,
        "instructions" -> instructions.toJson,
        "criteria" -> Json.fromFields(
          options.map((name, rubric) =>
            (name.value: String) -> rubric.fold(Json.Null)(_.toJson)
          )
        )
      )

    case Score(instructions, levels) =>
      Json.obj(
        "type" -> "score".asJson,
        "instructions" -> instructions.toJson,
        "criteria" -> Json.fromValues(levels.map(_.toJson))
      )

    case Raw(questionType, body) =>
      Json.fromJsonObject(body.add("type", questionType.asJson))

  given Eq[QuestionSpec] = Eq.fromUniversalEquals
  given Show[QuestionSpec] = Show.show(_.asJson.noSpaces)
