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

package com.softinio.verdict4s

import cats.effect.IO
import com.softinio.verdict4s.algebra.*
import org.http4s.*
import org.http4s.Uri.Path
import org.http4s.client.Client
import org.http4s.implicits.*

/** The contract, over a fake speaking Jev. */
class JevContractTest extends VendorContractSuite:

  def clientFor(app: HttpApp[IO], config: ClientConfig): Verdict4sClient[IO] =
    Verdict4sClient.jev[IO](Client.fromHttpApp(app), key, baseUri, config)

  def endpoint: Path = path"/v1/systemone"

  def requestIdHeader: String = "x-typesafe-request-id"

  def okBody: String = """
    {
      "model": "jev-1.13.0",
      "answers": {
        "q00": {"type": "noul", "noul": 0.95},
        "q01": {"type": "choice", "choice": "Billing",
                "probabilities": {"Billing": 0.88, "Technical": 0.12},
                "confidence": 0.81},
        "q02": {"type": "score", "score": 1.05,
                "legend": {"0": "Calm", "1": "Frustrated", "2": "Very angry"},
                "probabilities": {"0": 0.0, "1": 0.95, "2": 0.05},
                "confidence": 0.92}
      },
      "usage": {"input_tokens": 296, "output_tokens": 20}
    }
  """

/** The contract, over a fake speaking OpenAI Decisions, whose base URL carries
  * `/v1` itself.
  */
class OpenAIContractTest extends VendorContractSuite:

  def clientFor(app: HttpApp[IO], config: ClientConfig): Verdict4sClient[IO] =
    Verdict4sClient.openAI[IO](Client.fromHttpApp(app), key, baseUri, config)

  def endpoint: Path = path"/decisions"

  def requestIdHeader: String = "x-request-id"

  def okBody: String = """
    {
      "model": "gpt-6-luna",
      "answers": [
        {"type": "predicate", "name": "q00", "probability": 0.95},
        {"type": "choice", "name": "q01", "choice": "Billing",
         "probabilities": [{"value": "Billing", "probability": 0.88},
                           {"value": "Technical", "probability": 0.12}],
         "confidence": 0.81},
        {"type": "score", "name": "q02", "score": 1.05,
         "probabilities": [{"value": 0, "label": "Calm", "probability": 0.0},
                           {"value": 1, "label": "Frustrated", "probability": 0.95},
                           {"value": 2, "label": "Very angry", "probability": 0.05}],
         "confidence": 0.92}
      ],
      "usage": {"input_tokens": 296, "input_tokens_details": {"cached_tokens": 0, "cache_write_tokens": 0},
                "output_tokens": 0, "output_tokens_details": {"reasoning_tokens": 0},
                "total_tokens": 296}
    }
  """
