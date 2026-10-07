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

import cats.syntax.all.*
import com.softinio.verdict4s.Vendor
import com.softinio.verdict4s.Verdict4sError
import com.softinio.verdict4s.algebra.*
import com.softinio.verdict4s.jev.JevCodecs.given
import io.circe.Json
import io.circe.parser
import io.circe.syntax.*

/** TypeSafe AI's Jev.
  *
  * ```
  * POST https://api.typesafe.ai/v1/systemone
  * {"state": ..., "model": "jev-latest", "questions": {"q00": {"type": "noul", ...}}}
  * ```
  *
  * Jev accepts everything the shared question model can express, so it adds no
  * validation of its own. It is also the only vendor with a models endpoint,
  * which is why [[modelsUrl]] and [[parseModels]] live here rather than on
  * [[com.softinio.verdict4s.Vendor]].
  *
  * @group Jev
  */
object JevVendor extends Vendor:

  val name: String = "Jev"

  /** Root of the TypeSafe API. */
  val defaultBaseUrl: String = "https://api.typesafe.ai"

  /** `jev-latest`. */
  val defaultModel: Model = Model.JevLatest

  val evaluatePath: List[String] = List("v1", "systemone")

  /** Path segments of the models endpoint, relative to the base URL. */
  val modelsPath: List[String] = List("v1", "models")

  val requestIdHeader: String = "x-typesafe-request-id"

  /** Environment variable holding the API key, matching the official SDKs. */
  val ApiKeyEnv: String = "TYPESAFE_API_KEY"

  /** Environment variable overriding the base URL. */
  val BaseUrlEnv: String = "TYPESAFE_BASE_URL"

  /** Environment variable overriding the default model. */
  val DefaultModelEnv: String = "TYPESAFE_DEFAULT_MODEL"

  def encode(request: EvaluationRequest): Json = request.asJson

  def decode(body: Json): Either[Verdict4sError, Evaluation] =
    body.as[Evaluation].leftMap(Verdict4sError.fromDecodingFailure)

  /** Where to GET the list of available models. */
  val modelsUrl: String = s"$defaultBaseUrl/${modelsPath.mkString("/")}"

  /** Parse the models endpoint's response, unwrapping its `models` envelope. */
  def parseModels(body: String): Either[Verdict4sError, List[ModelCard]] =
    parser
      .parse(body)
      .leftMap(f =>
        Verdict4sError.Decoding(s"the body was not JSON: ${f.message}")
      )
      .flatMap(decodeModels)

  /** Read the models endpoint's response, unwrapping its `models` envelope. */
  def decodeModels(body: Json): Either[Verdict4sError, List[ModelCard]] =
    JevCodecs.modelListDecoder
      .decodeJson(body)
      .leftMap(Verdict4sError.fromDecodingFailure)
