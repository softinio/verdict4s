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

package com.softinio.verdict4s.openai

import scala.collection.immutable.SortedMap

import cats.syntax.all.*
import com.softinio.verdict4s.algebra.*
import io.circe.Decoder
import io.circe.DecodingFailure
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*

/** OpenAI Decisions' wire format for the vendor-neutral protocol types.
  *
  * Written against the `DecisionRequest` and `DecisionResponse` schemas of
  * OpenAI's published OpenAPI spec. The translation from Jev's vocabulary:
  *
  * | Shared model     | OpenAI Decisions                                   |
  * |:-----------------|:---------------------------------------------------|
  * | `state`          | `input`, as a string                               |
  * | question map     | `questions` array, each carrying its key as `name` |
  * | Noul             | `predicate`, answered with `probability`           |
  * | Choice options   | `choices: [{value, description}]`                  |
  * | Score levels     | `levels: [{label}]`, lowest first                  |
  * | probability maps | arrays of `{value, probability}`                   |
  * | Score `legend`   | the `label` on each score probability              |
  *
  * The request encoding is a method rather than an `Encoder`, unlike in
  * [[com.softinio.verdict4s.jev.JevCodecs]], because it needs the request-level
  * `safety_identifier`, which [[OpenAIVendor]] carries.
  *
  * @group OpenAI
  */
object OpenAICodecs:

  /** The request body. Questions go in the sorted order of their keys, which is
    * the order `Ask` generated them in unless `withKeys` chose keys that sort
    * differently, or the ask has more than a hundred questions.
    */
  def encodeRequest(
      request: EvaluationRequest,
      safetyIdentifier: Option[String]
  ): Json =
    Json.fromFields(
      List(
        "model" -> request.model.asJson,
        "input" -> text(request.state).asJson,
        "questions" -> Json.fromValues(
          request.questions.toList.map(encodeQuestion)
        )
      ) ++ safetyIdentifier.map("safety_identifier" -> _.asJson)
    )

  /** One question, named by its key so the answer can be matched back to it.
    *
    * Noul criteria have no counterpart and are left out here;
    * [[OpenAIVendor.validate]] rejects a request that carries them rather than
    * let them vanish.
    */
  def encodeQuestion(key: QuestionKey, spec: QuestionSpec): Json =
    val name = key.value: String
    def head(tpe: String, instructions: Instructions) = List(
      "type" -> tpe.asJson,
      "name" -> name.asJson,
      "instructions" -> text(instructions.toJson).asJson
    )
    spec match
      case QuestionSpec.Noul(instructions, _) =>
        Json.fromFields(head("predicate", instructions))

      case QuestionSpec.Choice(instructions, options) =>
        Json.fromFields(
          head("choice", instructions) :+ ("choices" -> Json.fromValues(
            options.toList.map((option, rubric) =>
              Json.fromFields(
                List("value" -> (option.value: String).asJson) ++
                  rubric.map(r => "description" -> text(r.toJson).asJson)
              )
            )
          ))
        )

      case QuestionSpec.Score(instructions, levels) =>
        Json.fromFields(
          head("score", instructions) :+ ("levels" -> Json.fromValues(
            levels.map(level => Json.obj("label" -> text(level.toJson).asJson))
          ))
        )

      case QuestionSpec.Raw(questionType, body) =>
        Json.fromJsonObject(
          JsonObject.fromIterable(
            List("type" -> questionType.asJson, "name" -> name.asJson) ++
              body.remove("type").remove("name").toList
          )
        )

  given Decoder[Usage] = Decoder.instance: c =>
    for
      in <- c.downField("input_tokens").as[Int]
      out <- c.downField("output_tokens").as[Int]
    yield Usage(in, out)

  given Decoder[Answer] = Decoder.instance: c =>
    c.downField("type")
      .as[String]
      .flatMap:
        case "predicate" =>
          c.downField("probability").as[Probability].map(Answer.Noul.apply)

        case "choice" =>
          for
            choice <- c.downField("choice").as(using choiceValue)
            probs <- c
              .downField("probabilities")
              .as(using Decoder.decodeList(using choiceProbability))
            conf <- c.downField("confidence").as[Confidence]
          yield Answer.Choice(choice, SortedMap.from(probs), conf)

        case "score" =>
          for
            score <- c.downField("score").as[Double]
            levels <- c
              .downField("probabilities")
              .as(using Decoder.decodeList(using scoreProbability))
            conf <- c.downField("confidence").as[Confidence]
          yield Answer.Score(
            score,
            SortedMap.from(levels.map((i, label, _) => i -> label)),
            SortedMap.from(levels.map((i, _, p) => i -> p)),
            conf
          )

        case "refusal" => Right(Answer.Refused)

        case other =>
          Left(DecodingFailure(s"unknown answer type '$other'", c.history))

  /** One answer and the key it belongs to.
    *
    * The spec allows a `null` name for an unnamed question. verdict4s names
    * every question, so a missing name means the response does not belong to
    * the request, and is reported rather than guessed at.
    */
  val answerEntry: Decoder[(QuestionKey, Answer)] = Decoder.instance: c =>
    for
      raw <- c.downField("name").as[Option[String]]
      key <- raw
        .toRight(
          DecodingFailure(
            "the answer has no name, so it cannot be matched to a question",
            c.downField("name").history
          )
        )
        .flatMap(n =>
          QuestionKey
            .either(n)
            .leftMap(e =>
              DecodingFailure(s"'$n' is not a question key: $e", c.history)
            )
        )
      answer <- c.as[Answer]
    yield key -> answer

  /** A response body. Answers are matched to questions by `name`. */
  given Decoder[Evaluation] = Decoder.instance: c =>
    for
      model <- c.downField("model").as[Model]
      answers <- c
        .downField("answers")
        .as(using Decoder.decodeList(using answerEntry))
      usage <- c.downField("usage").as[Usage]
    yield Evaluation(model, SortedMap.from(answers), usage)

  /** A choice value is a string or a boolean. verdict4s only sends strings, so
    * a boolean is read back as its text, defensively.
    */
  private val choiceValue: Decoder[OptionName] =
    Decoder.decodeString
      .or(Decoder.decodeBoolean.map(_.toString))
      .emap(s =>
        OptionName.either(s).leftMap(e => s"'$s' is not an option name: $e")
      )

  private val choiceProbability: Decoder[(OptionName, Probability)] =
    Decoder.instance: c =>
      for
        value <- c.downField("value").as(using choiceValue)
        p <- c.downField("probability").as[Probability]
      yield value -> p

  private val scoreProbability: Decoder[(Int, String, Probability)] =
    Decoder.instance: c =>
      for
        value <- c.downField("value").as[Int]
        label <- c.downField("label").as[String]
        p <- c.downField("probability").as[Probability]
      yield (value, label, p)

  /** OpenAI takes `input`, instructions and descriptions as strings only. Text
    * goes as it is; structured JSON is sent as its compact rendering, which the
    * model reads as well as any other text.
    */
  private def text(json: Json): String = json.asString.getOrElse(json.noSpaces)
