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

import cats.data.ValidatedNec
import cats.syntax.all.*
import com.softinio.verdict4s.Vendor
import com.softinio.verdict4s.Verdict4sError
import com.softinio.verdict4s.algebra.*
import com.softinio.verdict4s.openai.OpenAICodecs.given
import io.circe.Json

/** OpenAI Decisions.
  *
  * ```
  * POST https://api.openai.com/v1/decisions
  * {"model": "gpt-6-luna", "input": ..., "questions": [{"type": "predicate", "name": "q00", ...}]}
  * ```
  *
  * The base URL includes `/v1`, as it does in OpenAI's own SDKs, so that an
  * `OPENAI_BASE_URL` set for them works here unchanged.
  *
  * Stricter than Jev in three ways, each checked by [[validate]] before a
  * request is sent: a Choice needs at least two options, a request carries at
  * most 200 questions, and a Noul cannot describe what yes and no mean. And it
  * may refuse a question, which `Ask` reports as `Verdict4sError.Refused`.
  *
  * @param safetyIdentifier
  *   an opaque identifier for your end user, which OpenAI uses to detect abuse;
  *   at most 128 characters. Never your own account identity.
  *
  * @group OpenAI
  */
final case class OpenAIVendor(safetyIdentifier: Option[String] = None)
    extends Vendor:

  def name: String = "OpenAI Decisions"

  def defaultBaseUrl: String = OpenAIVendor.BaseUrl

  /** `gpt-6-luna`, the only model OpenAI Decisions supports. */
  def defaultModel: Model = Model.GptLuna

  def evaluatePath: List[String] = List("decisions")

  def requestIdHeader: String = "x-request-id"

  override def retryAfterMsHeader: Option[String] = Some("retry-after-ms")

  /** An exhausted credit balance comes back as a 429, like a rate limit, but
    * waiting does not fix it. Seen live: `{"error": {"type":
    * "insufficient_quota", "code": "credit_balance_exhausted", ...}}`, with no
    * `x-should-retry` header to say so.
    */
  override def retryable(error: Verdict4sError.Api): Boolean =
    !error.body.flatMap(_.kind).contains("insufficient_quota")

  /** The same vendor, sending `safety_identifier` with every request. */
  def withSafetyIdentifier(id: String): OpenAIVendor =
    copy(safetyIdentifier = Some(id))

  override def validate(
      request: EvaluationRequest
  ): ValidatedNec[Verdict4sError.Validation, Unit] =
    val count =
      if request.questions.size > OpenAIVendor.MaxQuestions then
        Verdict4sError
          .Validation(
            "questions",
            s"OpenAI Decisions accepts at most ${OpenAIVendor.MaxQuestions} " +
              s"questions per request, not ${request.questions.size}"
          )
          .invalidNec
      else ().validNec
    val perQuestion = request.questions.toList.traverse_((key, spec) =>
      question(key.value: String, spec)
    )
    val identifier = safetyIdentifier.traverse_(id =>
      if id.trim.isEmpty then
        Verdict4sError
          .Validation("safety_identifier", "must not be blank")
          .invalidNec
      else if id.length > OpenAIVendor.MaxSafetyIdentifier then
        Verdict4sError
          .Validation(
            "safety_identifier",
            s"must be at most ${OpenAIVendor.MaxSafetyIdentifier} characters"
          )
          .invalidNec
      else ().validNec
    )
    count *> perQuestion *> identifier

  def encode(request: EvaluationRequest): Json =
    OpenAICodecs.encodeRequest(request, safetyIdentifier)

  def decode(body: Json): Either[Verdict4sError, Evaluation] =
    body.as[Evaluation].leftMap(Verdict4sError.fromDecodingFailure)

  private def question(
      key: String,
      spec: QuestionSpec
  ): ValidatedNec[Verdict4sError.Validation, Unit] =
    spec match
      case QuestionSpec.Noul(_, Some(criteria)) if !criteria.isEmpty =>
        Verdict4sError
          .Validation(
            s"questions.$key.criteria",
            "OpenAI Decisions has no yes/no criteria; describe what yes and " +
              "no mean in the instructions instead"
          )
          .invalidNec
      case QuestionSpec.Choice(_, options) if options.size < 2 =>
        Verdict4sError
          .Validation(
            s"questions.$key.options",
            "OpenAI Decisions needs at least two options"
          )
          .invalidNec
      case _ => ().validNec

/** The default vendor, and OpenAI's limits. */
object OpenAIVendor:

  /** No safety identifier. */
  val default: OpenAIVendor = OpenAIVendor()

  /** Root of the OpenAI API, including `/v1` as OpenAI's SDKs do. */
  val BaseUrl: String = "https://api.openai.com/v1"

  /** Environment variable holding the API key, matching OpenAI's SDKs. */
  val ApiKeyEnv: String = "OPENAI_API_KEY"

  /** Environment variable overriding the base URL, matching OpenAI's SDKs. */
  val BaseUrlEnv: String = "OPENAI_BASE_URL"

  /** `maxItems` of `DecisionRequest.questions`. */
  val MaxQuestions: Int = 200

  /** `maxLength` of `DecisionRequest.safety_identifier`. */
  val MaxSafetyIdentifier: Int = 128
