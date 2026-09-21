package com.softinio.verdict4s.algebra

import cats.Eq
import cats.Show
import io.circe.Decoder
import io.circe.Json

/** A best-effort reading of an error response body.
  *
  * The TypeSafe docs describe error *statuses* precisely but do not pin the
  * shape of the JSON body, so this parse is deliberately **total**: every field
  * is optional and [[raw]] always holds what actually arrived. A 422 carrying
  * an envelope this version does not recognise must still surface as a
  * `Verdict4sError.Api` with its body intact — turning it into a decoding
  * failure would hide the very detail the caller needs.
  *
  * @param kind
  *   a machine-readable error type, when the body names one
  * @param message
  *   a human-readable description, when the body carries one
  * @param field
  *   the offending field, for validation failures
  * @param raw
  *   the body exactly as received
  *
  * @group Errors
  */
final case class ApiErrorBody(
    kind: Option[String],
    message: Option[String],
    field: Option[String],
    raw: Json
)

/** Reading an error body, and its decoder, which never fails. */
object ApiErrorBody:

  /** Read an error body. Never fails. */
  def fromJson(json: Json): ApiErrorBody =
    val c = json.hcursor

    // Some services nest the payload under `error`; try both.
    val bases = List(c, c.downField("error"))

    def firstString(names: String*): Option[String] =
      bases.iterator
        .flatMap(b =>
          names.iterator.map(n => b.downField(n).as[String].toOption)
        )
        .collectFirst { case Some(v) => v }

    // FastAPI-style validation payload: detail = [{ loc, msg, type }, ...]
    val detail = c.downField("detail")
    val firstDetail = detail.downArray

    val message = firstString("message", "detail", "error_description")
      .orElse(firstDetail.downField("msg").as[String].toOption)

    val kind = firstString("type", "code", "error_type")
      .orElse(firstDetail.downField("type").as[String].toOption)

    val field = firstString("field", "param", "loc")
      .orElse(
        firstDetail
          .downField("loc")
          .as[List[Json]]
          .toOption
          .map(_.flatMap(_.asString).mkString("."))
          .filter(_.nonEmpty)
      )

    ApiErrorBody(kind, message, field, json)

  given Decoder[ApiErrorBody] = Decoder.decodeJson.map(fromJson)
  given Eq[ApiErrorBody] = Eq.fromUniversalEquals
  given Show[ApiErrorBody] = Show.show(_.raw.noSpaces)
