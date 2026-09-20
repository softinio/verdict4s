package com.softinio.verdict4s.algebra

import cats.Eq
import cats.Show
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json

/** Token usage reported alongside every evaluation.
  *
  * @group Protocol
  */
final case class Usage(inputTokens: Int, outputTokens: Int):
  /** Total tokens billed for the request. */
  def total: Int = inputTokens + outputTokens

object Usage:

  given Decoder[Usage] = Decoder.instance: c =>
    for
      in <- c.downField("input_tokens").as[Int]
      out <- c.downField("output_tokens").as[Int]
    yield Usage(in, out)

  given Encoder[Usage] = Encoder.instance: u =>
    Json.obj(
      "input_tokens" -> Json.fromInt(u.inputTokens),
      "output_tokens" -> Json.fromInt(u.outputTokens)
    )

  given Eq[Usage] = Eq.fromUniversalEquals
  given Show[Usage] =
    Show.show(u => s"Usage(in=${u.inputTokens}, out=${u.outputTokens})")
