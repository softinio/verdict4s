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

package com.softinio.verdict4s.jev

import scala.collection.immutable.SortedMap

import com.softinio.verdict4s.algebra.*
import io.circe.Decoder
import io.circe.DecodingFailure
import io.circe.Encoder
import io.circe.Json
import io.circe.syntax.*

/** Jev's wire format for the vendor-neutral protocol types.
  *
  * Kept out of the types' companions on purpose, so that no codec is picked up
  * implicitly: which vendor's encoding applies is a choice, made by
  * [[JevVendor]] or by importing these explicitly:
  *
  * ```scala
  * import com.softinio.verdict4s.jev.JevCodecs.given
  * ```
  *
  * @group Jev
  */
object JevCodecs:

  given Encoder[NoulCriteria] = Encoder.instance: c =>
    Json.fromFields(
      List(
        c.ifTrue.map(i => "true" -> i.toJson),
        c.ifFalse.map(i => "false" -> i.toJson)
      ).flatten
    )

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
    case QuestionSpec.Noul(instructions, criteria) =>
      val base =
        List("type" -> "noul".asJson, "instructions" -> instructions.toJson)
      val crit = criteria.filterNot(_.isEmpty).map(c => "criteria" -> c.asJson)
      Json.fromFields(base ++ crit)

    case QuestionSpec.Choice(instructions, options) =>
      Json.obj(
        "type" -> "choice".asJson,
        "instructions" -> instructions.toJson,
        "criteria" -> Json.fromFields(
          options.map((name, rubric) =>
            (name.value: String) -> rubric.fold(Json.Null)(_.toJson)
          )
        )
      )

    case QuestionSpec.Score(instructions, levels) =>
      Json.obj(
        "type" -> "score".asJson,
        "instructions" -> instructions.toJson,
        "criteria" -> Json.fromValues(levels.map(_.toJson))
      )

    case QuestionSpec.Raw(questionType, body) =>
      Json.fromJsonObject(body.add("type", questionType.asJson))

  given Encoder[EvaluationRequest] = Encoder.instance: r =>
    Json.obj(
      "state" -> r.state,
      "model" -> r.model.asJson,
      "questions" -> Json.fromFields(
        r.questions.map((key, spec) => (key.value: String) -> spec.asJson)
      )
    )

  given Decoder[Answer] = Decoder.instance: c =>
    c.downField("type")
      .as[String]
      .flatMap:
        case "noul" =>
          c.downField("noul").as[Probability].map(Answer.Noul.apply)

        case "choice" =>
          for
            choice <- c.downField("choice").as[OptionName]
            probs <- c
              .downField("probabilities")
              .as[SortedMap[OptionName, Probability]]
            conf <- c.downField("confidence").as[Confidence]
          yield Answer.Choice(choice, probs, conf)

        case "score" =>
          for
            score <- c.downField("score").as[Double]
            legend <- c.downField("legend").as[SortedMap[Int, String]]
            probs <- c
              .downField("probabilities")
              .as[SortedMap[Int, Probability]]
            conf <- c.downField("confidence").as[Confidence]
          yield Answer.Score(score, legend, probs, conf)

        case other =>
          Left(
            DecodingFailure(s"unknown answer type '$other'", c.history)
          )

  /** Jev never refuses, so [[Answer.Refused]] has no documented encoding; it is
    * written as a bare `{"type": "refusal"}` so that a fake can still serve
    * one.
    */
  given Encoder[Answer] = Encoder.instance:
    case Answer.Noul(noul) =>
      Json.obj("type" -> "noul".asJson, "noul" -> noul.asJson)

    case Answer.Choice(choice, probabilities, confidence) =>
      Json.obj(
        "type" -> "choice".asJson,
        "choice" -> choice.asJson,
        "probabilities" -> probabilities.asJson,
        "confidence" -> confidence.asJson
      )

    case Answer.Score(score, legend, probabilities, confidence) =>
      Json.obj(
        "type" -> "score".asJson,
        "score" -> score.asJson,
        "legend" -> legend.asJson,
        "probabilities" -> probabilities.asJson,
        "confidence" -> confidence.asJson
      )

    case Answer.Refused =>
      Json.obj("type" -> "refusal".asJson)

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

  given Decoder[Evaluation] = Decoder.instance: c =>
    for
      model <- c.downField("model").as[Model]
      answers <- c.downField("answers").as[SortedMap[QuestionKey, Answer]]
      usage <- c.downField("usage").as[Usage]
    yield Evaluation(model, answers, usage)

  given Encoder[Evaluation] = Encoder.instance: e =>
    Json.obj(
      "model" -> e.model.asJson,
      "answers" -> Json.fromFields(
        e.answers.map((key, answer) => (key.value: String) -> answer.asJson)
      ),
      "usage" -> e.usage.asJson
    )

  given Decoder[ModelCard] = Decoder.instance: c =>
    for
      name <- c.downField("name").as[Model]
      description <- c.downField("description").as[String]
      releaseDate <- c.downField("release_date").as[String]
    yield ModelCard(name, description, releaseDate)

  given Encoder[ModelCard] = Encoder.instance: m =>
    Json.obj(
      "name" -> m.name.asJson,
      "description" -> m.description.asJson,
      "release_date" -> m.releaseDate.asJson
    )

  /** The models endpoint wraps its array in a `models` field:
    *
    * ```json
    * {"models": [{"name": "jev-latest", "description": "...", "release_date": "..."}]}
    * ```
    *
    * That envelope is a wire detail the official SDKs unwrap, and so does the
    * client's `models`, so it gets a decoder rather than a type of its own. Not
    * a `given`: it would shadow circe's list decoder for every caller.
    */
  val modelListDecoder: Decoder[List[ModelCard]] =
    Decoder.instance(_.downField("models").as[List[ModelCard]])
