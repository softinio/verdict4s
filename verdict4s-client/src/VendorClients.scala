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

import com.softinio.verdict4s.algebra.*
import com.softinio.verdict4s.internal.Engine
import com.softinio.verdict4s.jev.JevVendor
import com.softinio.verdict4s.openai.OpenAIVendor
import io.circe.Encoder

/** A client for TypeSafe AI's Jev: the shared surface, plus [[ModelCatalog]].
  *
  * Built with `Verdict4sClient.jev`. Its copy methods return a `JevClient`, so
  * `withConfig` never loses the model catalog.
  *
  * @group Client
  */
final class JevClient[F[_]] private[verdict4s] (
    engine: Engine[F, JevVendor.type]
) extends Verdict4sClient[F],
      ModelCatalog[F]:

  def vendor: Vendor = engine.vendor

  def config: ClientConfig = engine.config

  def evaluate(request: EvaluationRequest): F[Evaluation] =
    engine.evaluate(request)

  def ask[Q <: Tuple, S: Encoder](
      questions: Ask[Q],
      state: S
  ): F[Ask.Answers[Q]] =
    engine.ask(questions, state)

  def models: F[List[ModelCard]] =
    engine.get(JevVendor.modelsPath)(JevVendor.decodeModels)

  def withConfig(f: ClientConfig => ClientConfig): JevClient[F] =
    new JevClient(engine.withConfig(f(engine.config)))

  def withJitter(j: Jitter[F]): JevClient[F] =
    new JevClient(engine.withJitter(j))

/** A client for OpenAI Decisions: the shared surface, plus [[SafetyIdentity]].
  *
  * Built with `Verdict4sClient.openAI`. Its copy methods return an
  * `OpenAIClient`, so `withConfig` never loses the safety identifier.
  *
  * @group Client
  */
final class OpenAIClient[F[_]] private[verdict4s] (
    engine: Engine[F, OpenAIVendor]
) extends Verdict4sClient[F],
      SafetyIdentity[F]:

  def vendor: OpenAIVendor = engine.vendor

  def config: ClientConfig = engine.config

  def evaluate(request: EvaluationRequest): F[Evaluation] =
    engine.evaluate(request)

  def ask[Q <: Tuple, S: Encoder](
      questions: Ask[Q],
      state: S
  ): F[Ask.Answers[Q]] =
    engine.ask(questions, state)

  def safetyIdentifier: Option[String] = vendor.safetyIdentifier

  def withSafetyIdentifier(id: String): OpenAIClient[F] =
    new OpenAIClient(engine.withVendor(vendor.withSafetyIdentifier(id)))

  def withConfig(f: ClientConfig => ClientConfig): OpenAIClient[F] =
    new OpenAIClient(engine.withConfig(f(engine.config)))

  def withJitter(j: Jitter[F]): OpenAIClient[F] =
    new OpenAIClient(engine.withJitter(j))
