package com.softinio.verdict4s.algebra

import cats.Eq
import cats.Show
import cats.data.ValidatedNec
import cats.syntax.all.*
import com.softinio.verdict4s.Verdict4sError
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json

/** The `instructions` and `criteria` payload: a string, object, or array.
  *
  * No JSON ADT is invented for this — `io.circe.Json` already is that type, and
  * circe is already a core dependency. What this adds is the protocol's
  * validity rule: the API accepts a string, an object or an array, so numbers,
  * booleans and `null` are rejected at construction, and a string must carry
  * actual content.
  *
  * Structure is useful when a question needs to refer to data. Put the question
  * in one field and the data in others, then reference them by name in
  * backticks:
  *
  * ```scala
  * Instructions.encoded(
  *   Map(
  *     "potential_duplicate" -> "John Smith, Oakland",
  *     "question" -> "Is the resume for the same person as `potential_duplicate`?"
  *   )
  * )
  * ```
  *
  * @group Protocol
  */
opaque type Instructions = Json

/** Constructors for [[Instructions]]: plain text, structured JSON, or any
  * encodable value. Each validates, rejecting what the API would reject.
  */
object Instructions:

  /** Plain text instructions. Rejects blank strings. */
  def text(
      value: String
  ): ValidatedNec[Verdict4sError.Validation, Instructions] =
    if value == null || value.trim.isEmpty then
      Verdict4sError
        .Validation("instructions", "must not be blank")
        .invalidNec
    else (Json.fromString(value): Instructions).validNec

  /** Structured instructions. Rejects anything but a string, object or array.
    */
  def json(
      value: Json
  ): ValidatedNec[Verdict4sError.Validation, Instructions] =
    value.fold(
      jsonNull = reject("must not be null"),
      jsonBoolean = _ => reject("must not be a boolean"),
      jsonNumber = _ => reject("must not be a number"),
      jsonString = text,
      jsonArray = _ => (value: Instructions).validNec,
      jsonObject = _ => (value: Instructions).validNec
    )

  /** Structured instructions from any encodable value. */
  def encoded[A: Encoder](
      value: A
  ): ValidatedNec[Verdict4sError.Validation, Instructions] =
    json(Encoder[A].apply(value))

  /** Wrap a value already known to be well formed. */
  private[verdict4s] def unsafe(value: Json): Instructions = value

  private def reject(
      why: String
  ): ValidatedNec[Verdict4sError.Validation, Instructions] =
    Verdict4sError.Validation("instructions", why).invalidNec

  extension (i: Instructions)
    /** The underlying JSON, as it goes on the wire. */
    def toJson: Json = i

  given Encoder[Instructions] = Encoder.encodeJson
  given Decoder[Instructions] = Decoder.decodeJson.emap: j =>
    json(j).toEither.leftMap(_.head.getMessage)
  given Eq[Instructions] = Eq.fromUniversalEquals
  given Show[Instructions] = Show.show(_.noSpaces)
