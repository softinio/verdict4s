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

import cats.Eq
import cats.Show
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.syntax.*
import io.github.iltotore.iron.*

/** A complete evaluation request.
  *
  * @param state
  *   the content to evaluate: a string for text, or structured data for chat
  *   logs, records, or application state
  * @param model
  *   the model that handles the request
  * @param questions
  *   the typed questions, keyed by names you choose; answers come back under
  *   the same keys
  *
  * @group Protocol
  */
final case class EvaluationRequest(
    state: Json,
    model: Model,
    questions: EvaluationRequest.Questions
)

/** The wire type of the question map, and the request's encoder. */
object EvaluationRequest:

  /** The question map as it goes on the wire.
    *
    * `SortedMap` for deterministic encoding, refined to reject an empty request
    * — which the service would reject anyway, a round trip later.
    */
  type Questions = SortedMap[QuestionKey, QuestionSpec] :| QuestionsC

  given Encoder[EvaluationRequest] = Encoder.instance: r =>
    Json.obj(
      "state" -> r.state,
      "model" -> r.model.asJson,
      "questions" -> Json.fromFields(
        r.questions.map((key, spec) => (key.value: String) -> spec.asJson)
      )
    )

  given Eq[EvaluationRequest] = Eq.fromUniversalEquals
  given Show[EvaluationRequest] = Show.show(_.asJson.noSpaces)

/** A complete evaluation response.
  *
  * @param model
  *   the model that actually performed the evaluation, which for an alias such
  *   as `jev-latest` resolves to a pinned version
  * @param answers
  *   one answer per question, under the keys you supplied
  * @param usage
  *   tokens consumed by the request
  *
  * @group Protocol
  */
final case class Evaluation(
    model: Model,
    answers: SortedMap[QuestionKey, Answer],
    usage: Usage
):

  /** The answer under one key, if the service returned one. */
  def answer(key: QuestionKey): Option[Answer] = answers.get(key)

/** The response's codecs and instances. */
object Evaluation:

  /** Note `answers` is a plain `SortedMap`, not refined to be non-empty.
    * Non-emptiness is a *request* invariant; being lenient here keeps a
    * degenerate but parseable response from becoming a decoding failure.
    */
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

  given Eq[Evaluation] = Eq.fromUniversalEquals
  given Show[Evaluation] = Show.show(_.asJson.noSpaces)
