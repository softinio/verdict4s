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

package com.softinio.verdict4s.internal

import cats.effect.kernel.Temporal
import cats.syntax.all.*
import com.softinio.verdict4s.Ask
import com.softinio.verdict4s.Jitter
import com.softinio.verdict4s.Vendor
import com.softinio.verdict4s.Verdict4sError
import com.softinio.verdict4s.algebra.*
import io.circe.Encoder
import io.circe.Json
import org.http4s.Uri
import org.http4s.client.Client

/** The one implementation behind every vendor's client.
  *
  * Everything vendor-specific comes from the [[Vendor]]; everything
  * HTTP-specific from [[Wire]] and [[RetryLoop]]. The public client classes are
  * thin wrappers that decide which capabilities to expose.
  */
private[verdict4s] final case class Engine[F[_]](
    client: Client[F],
    vendor: Vendor,
    baseUri: Uri,
    apiKey: ApiKey,
    config: ClientConfig,
    jitter: Jitter[F]
)(using F: Temporal[F]):

  /** Copies that reuse the captured `Temporal`, which `copy` cannot. */
  def withConfig(c: ClientConfig): Engine[F] = copy(config = c)
  def withJitter(j: Jitter[F]): Engine[F] = copy(jitter = j)
  def withVendor(p: Vendor): Engine[F] = copy(vendor = p)

  /** The model a request uses when it names none. */
  def defaultModel: Model = config.model.getOrElse(vendor.defaultModel)

  def evaluate(request: EvaluationRequest): F[Evaluation] =
    F.fromEither(vendor.prepare(request))
      .flatMap(body => post(vendor.evaluatePath, body)(vendor.decode))

  def ask[Q <: Tuple, S: Encoder](
      questions: Ask[Q],
      state: S
  ): F[Ask.Answers[Q]] =
    for
      request <- F.fromEither(
        questions
          .request(state, defaultModel)
          .toEither
          .leftMap(Verdict4sError.Invalid(_))
      )
      evaluation <- evaluate(request)
      answers <- F.fromEither(questions.answers(evaluation))
    yield answers

  def post[A](path: List[String], body: Json)(
      decode: Json => Either[Verdict4sError, A]
  ): F[A] =
    run(Wire.post[F](Wire.uri(baseUri, path), apiKey, body))(decode)

  def get[A](path: List[String])(
      decode: Json => Either[Verdict4sError, A]
  ): F[A] =
    run(Wire.get[F](Wire.uri(baseUri, path), apiKey))(decode)

  private def run[A](request: org.http4s.Request[F])(
      decode: Json => Either[Verdict4sError, A]
  ): F[A] =
    RetryLoop.run[F, A](
      client,
      request,
      vendor,
      config.retry,
      config.requestTimeout,
      jitter
    )(Wire.decode[F, A](_)(decode))
