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

import scala.concurrent.duration.FiniteDuration

import cats.effect.kernel.Temporal
import cats.syntax.all.*
import com.softinio.verdict4s.Ask
import com.softinio.verdict4s.Jitter
import com.softinio.verdict4s.Vendor
import com.softinio.verdict4s.Verdict4sError
import com.softinio.verdict4s.algebra.*
import io.circe.Encoder
import io.circe.Json
import org.http4s.Request
import org.http4s.Uri
import org.http4s.client.Client

/** The one implementation behind every vendor's client.
  *
  * Everything vendor-specific comes from the [[Vendor]]; everything
  * HTTP-specific from [[Wire]] and the retry loop in `run`. The public client
  * classes are thin wrappers that decide which capabilities to expose.
  */
private[verdict4s] final case class Engine[F[_], V <: Vendor](
    client: Client[F],
    vendor: V,
    baseUri: Uri,
    apiKey: ApiKey,
    config: ClientConfig,
    jitter: Jitter[F]
)(using F: Temporal[F]):

  /** Copies that reuse the captured `Temporal`, which `copy` cannot. */
  def withConfig(c: ClientConfig): Engine[F, V] = copy(config = c)
  def withJitter(j: Jitter[F]): Engine[F, V] = copy(jitter = j)
  def withVendor(v: V): Engine[F, V] = copy(vendor = v)

  /** The model a request uses when it names none. */
  def defaultModel: Model = config.model.getOrElse(vendor.defaultModel)

  def evaluate(request: EvaluationRequest): F[Evaluation] =
    F.fromEither(vendor.prepare(request)).flatMap(send)

  def ask[Q <: Tuple, S: Encoder](
      questions: Ask[Q],
      state: S
  ): F[Ask.Answers[Q]] =
    F.fromEither(vendor.prepareAsk(questions, state, defaultModel))
      .flatMap(send)
      .flatMap(evaluation => F.fromEither(questions.answers(evaluation)))

  private def send(body: Json): F[Evaluation] =
    run(Wire.post[F](Wire.uri(baseUri, vendor.evaluatePath), apiKey, body))(
      vendor.decode
    )

  def get[A](path: List[String])(
      decode: Json => Either[Verdict4sError, A]
  ): F[A] =
    run(Wire.get[F](Wire.uri(baseUri, path), apiKey))(decode)

  /** Run `request`, reading the response inside the resource scope and retrying
    * it when the policy says to.
    *
    * A loop over the caller's `Client[F]` rather than http4s' own `Retry`
    * middleware, for two reasons. The body has to be consumed inside `use`
    * anyway to build a `Verdict4sError.Api` carrying the parsed payload and the
    * request id, and the middleware would force either a second read or losing
    * that payload. And `Retry` depends on `cats.effect.std.Hotswap`, which is
    * only transitively on the classpath here.
    *
    * Crucially this does not *build* a client, which would break the invariant
    * that the caller owns the transport. One attempt is capped at
    * `config.requestTimeout`, from sending to having read the body; exceeding
    * it is a [[Verdict4sError.Transport]], and is not retried. The whole call,
    * retries and their waits included, is capped at `totalTimeout`; exceeding
    * that is a [[Verdict4sError.Transport]] too, with its own message.
    */
  private def run[A](request: Request[F])(
      decode: Json => Either[Verdict4sError, A]
  ): F[A] =
    def once: F[A] =
      client
        .run(request)
        .use: response =>
          if response.status.isSuccess then Wire.decode(response)(decode)
          else
            F.realTime.flatMap(now =>
              Wire.error(response, vendor, now.toSeconds).flatMap(F.raiseError)
            )

    // `start` is the effect's monotonic clock at the start of the call, read
    // once, so that a retry can tell how much of `totalTimeout` is left.
    def attempt(number: Int, start: FiniteDuration): F[A] =
      F.timeoutTo(
        once,
        config.requestTimeout,
        F.raiseError(
          Verdict4sError.Transport(
            s"no response from ${vendor.name} within ${config.requestTimeout}"
          )
        )
      ).recoverWith { case apiError: Verdict4sError.Api =>
        retryOrRaise(apiError, number, start)
      }

    def retryOrRaise(
        apiError: Verdict4sError.Api,
        number: Int,
        start: FiniteDuration
    ): F[A] =
      val retries: Boolean =
        config.retry.retriesStatus(apiError.status) && vendor.retryable(
          apiError
        )
      if !retries then F.raiseError(apiError)
      else
        jitter.sample.flatMap: sample =>
          config.retry.delayFor(number, apiError.retryAfter, sample) match
            case None        => F.raiseError(apiError)
            case Some(delay) =>
              // `delayFor` is pure and only knows the whole budget. A wait of
              // at least what is left of it could only end in a timeout, so
              // give up now with the server's own error instead.
              F.monotonic.flatMap: now =>
                if delay >= config.retry.totalTimeout - (now - start) then
                  F.raiseError(apiError)
                else F.sleep(delay) *> attempt(number + 1, start)

    // The outer guard, for an attempt still in flight when the budget runs
    // out. A `Transport` like the per-attempt timeout's, so that every failure
    // is a `Verdict4sError`, but worded so the two can be told apart.
    F.timeoutTo(
      F.monotonic.flatMap(start => attempt(1, start)),
      config.retry.totalTimeout,
      F.raiseError(
        Verdict4sError.Transport(
          s"gave up on ${vendor.name} after totalTimeout " +
            s"${config.retry.totalTimeout}, retries included"
        )
      )
    )
