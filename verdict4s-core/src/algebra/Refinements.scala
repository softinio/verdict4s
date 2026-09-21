package com.softinio.verdict4s.algebra

import cats.Order
import cats.Show
import io.circe.Decoder
import io.circe.Encoder
import io.circe.KeyDecoder
import io.circe.KeyEncoder
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

/** Refined primitives shared across the protocol.
  *
  * Each of these is an Iron [[io.github.iltotore.iron.RefinedType]], so the
  * bound the TypeSafe API documents is carried by the type itself rather than
  * re-checked at each use site. Literals are folded at compile time:
  *
  * ```scala
  * val p = Probability(0.95) // ok
  * val q = Probability(1.5) // rejected at compile time
  * ```
  *
  * Values arriving from JSON are not literals, so decoders use
  * `Probability.either`, turning an out-of-range number from the service into a
  * typed decoding failure instead of letting it propagate.
  *
  * `RefinedType` produces a *nominal* type: unwrap it with `.value`. That is
  * deliberate — the alternative, a bare `Double :| C` alias, is a true `Double`
  * subtype but prints its whole constraint in signatures and would let
  * [[Probability]] and [[Confidence]] be passed interchangeably.
  *
  * @group Refinements
  */
object Refinements

/** Constraint shared by [[Probability]] and [[Confidence]]: the closed unit
  * interval.
  */
type UnitIntervalC = GreaterEqual[0.0] & LessEqual[1.0]

/** A probability, between 0 and 1 inclusive. */
object Probability extends RefinedType[Double, UnitIntervalC]:
  given Ordering[Probability] = Ordering.by(p => p.value: Double)
  given Order[Probability] = Order.fromOrdering
  given Show[Probability] = Show.show(p => (p.value: Double).toString)
  given Encoder[Probability] =
    Encoder.encodeDouble.contramap(p => p.value: Double)
  given Decoder[Probability] =
    Decoder.decodeDouble.emap(either(_).map(identity))

/** A probability between 0 and 1 inclusive. Build one with `Probability`. */
type Probability = Probability.T

/** How certain the model is, between 0 and 1 inclusive.
  *
  * Distinct from [[Probability]] on purpose: confidence is derived from a whole
  * distribution, and conflating the two is an easy mistake to make.
  */
object Confidence extends RefinedType[Double, UnitIntervalC]:
  given Ordering[Confidence] = Ordering.by(c => c.value: Double)
  given Order[Confidence] = Order.fromOrdering
  given Show[Confidence] = Show.show(c => (c.value: Double).toString)
  given Encoder[Confidence] =
    Encoder.encodeDouble.contramap(c => c.value: Double)
  given Decoder[Confidence] = Decoder.decodeDouble.emap(either(_).map(identity))

/** How certain the model is, between 0 and 1. Distinct from [[Probability]]. */
type Confidence = Confidence.T

/** Constraint for identifiers that must carry actual content. */
type NonBlankC = Not[Blank]

/** A Choice option name, as sent in `criteria` and returned in `choice`. */
object OptionName extends RefinedType[String, NonBlankC]:
  given Ordering[OptionName] = Ordering.by(n => n.value: String)
  given Order[OptionName] = Order.fromOrdering
  given Show[OptionName] = Show.show(n => n.value: String)
  given Encoder[OptionName] =
    Encoder.encodeString.contramap(n => n.value: String)
  given Decoder[OptionName] = Decoder.decodeString.emap(either(_).map(identity))
  given KeyEncoder[OptionName] =
    KeyEncoder.encodeKeyString.contramap(n => n.value: String)
  given KeyDecoder[OptionName] = KeyDecoder.instance(either(_).toOption)

/** A non-blank Choice option name. */
type OptionName = OptionName.T

/** A caller-chosen key in the `questions` map; answers return under the same
  * key.
  */
object QuestionKey extends RefinedType[String, NonBlankC]:
  given Ordering[QuestionKey] = Ordering.by(n => n.value: String)
  given Order[QuestionKey] = Order.fromOrdering
  given Show[QuestionKey] = Show.show(n => n.value: String)
  given Encoder[QuestionKey] =
    Encoder.encodeString.contramap(n => n.value: String)
  given Decoder[QuestionKey] =
    Decoder.decodeString.emap(either(_).map(identity))
  given KeyEncoder[QuestionKey] =
    KeyEncoder.encodeKeyString.contramap(n => n.value: String)
  given KeyDecoder[QuestionKey] = KeyDecoder.instance(either(_).toOption)

/** A non-blank key in the `questions` map. */
type QuestionKey = QuestionKey.T

/** A model identifier such as `jev-latest` or `jev-1.13.0`. */
object ModelId extends RefinedType[String, NonBlankC]

/** A non-blank model identifier. */
type ModelId = ModelId.T

/** Choice accepts between 1 and 255 options.
  *
  * Iron has no `MinLength`/`MaxLength`; cardinality is expressed by applying a
  * numeric constraint to the collection's `Length`.
  */
type ChoiceOptionsC = Length[GreaterEqual[1] & LessEqual[255]]

/** Score accepts between 2 and 10 ordered levels. */
type ScoreLevelsC = Length[GreaterEqual[2] & LessEqual[10]]

/** A request must carry at least one question. */
type QuestionsC = Length[GreaterEqual[1]]
