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
import com.softinio.verdict4s.Jitter
import com.softinio.verdict4s.Vendor
import com.softinio.verdict4s.Verdict4sError
import com.softinio.verdict4s.algebra.RetryPolicy
import org.http4s.Request
import org.http4s.Response
import org.http4s.client.Client

/** Runs a request, retrying it when the policy says to.
  *
  * A loop over the caller's `Client[F]` rather than http4s' own `Retry`
  * middleware, for two reasons. The body has to be consumed inside `use` anyway
  * to build a `Verdict4sError.Api` carrying the parsed payload and the request
  * id, and the middleware would force either a second read or losing that
  * payload. And `Retry` depends on `cats.effect.std.Hotswap`, which is only
  * transitively on the classpath here.
  *
  * Crucially this does not *build* a client, which would break the invariant
  * that the caller owns the transport.
  */
private[verdict4s] object RetryLoop:

  /** Run `request`, handling the response inside the resource scope.
    *
    * @param vendor
    *   where to find the request id and the retry delay in an error response
    * @param requestTimeout
    *   ceiling on one attempt, from sending to having read the body. Exceeding
    *   it is a [[Verdict4sError.Transport]], and is not retried
    * @param onSuccess
    *   how to read a 2xx response; runs before the connection is released
    */
  def run[F[_]: Temporal, A](
      client: Client[F],
      request: Request[F],
      vendor: Vendor,
      policy: RetryPolicy,
      requestTimeout: FiniteDuration,
      jitter: Jitter[F]
  )(onSuccess: Response[F] => F[Either[Verdict4sError, A]]): F[A] =

    def once: F[Either[Verdict4sError.Api, Either[Verdict4sError, A]]] =
      client
        .run(request)
        .use: response =>
          if response.status.isSuccess then
            onSuccess(response).map(
              Right(_): Either[Verdict4sError.Api, Either[Verdict4sError, A]]
            )
          else
            nowSeconds.flatMap(now =>
              Wire.error(response, vendor, now).map(Left(_))
            )

    def attempt(number: Int): F[A] =
      Temporal[F]
        .timeoutTo(
          once,
          requestTimeout,
          Temporal[F].raiseError(
            Verdict4sError.Transport(
              s"no response from ${vendor.name} within $requestTimeout"
            )
          )
        )
        .flatMap:
          case Right(decoded) => Temporal[F].fromEither(decoded)
          case Left(apiError) => retryOrRaise(apiError, number)

    def retryOrRaise(apiError: Verdict4sError.Api, number: Int): F[A] =
      if !policy.retriesStatus(apiError.status) || !vendor.retryable(apiError)
      then Temporal[F].raiseError(apiError)
      else
        jitter.sample.flatMap: sample =>
          policy.delayFor(number, apiError.retryAfter, sample) match
            case None        => Temporal[F].raiseError(apiError)
            case Some(delay) => Temporal[F].sleep(delay) *> attempt(number + 1)

    Temporal[F].timeout(attempt(1), policy.totalTimeout)

  private def nowSeconds[F[_]: Temporal]: F[Long] =
    Temporal[F].realTime.map(_.toSeconds)
