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

import scala.concurrent.duration.*

import cats.effect.kernel.Concurrent
import cats.syntax.all.*
import com.softinio.verdict4s.Vendor
import com.softinio.verdict4s.Verdict4sError
import com.softinio.verdict4s.algebra.*
import io.circe.Json
import org.http4s.*
import org.http4s.circe.*
import org.http4s.headers.Authorization
import org.typelevel.ci.CIString

/** Translating between the protocol and http4s.
  *
  * Everything HTTP-shaped lives here so that the client reads as protocol logic
  * and the core never sees an http4s type. Nothing here knows a vendor: the
  * header names and the body formats all come from the [[Vendor]].
  */
private[verdict4s] object Wire:

  /** An endpoint under a given base. */
  def uri(base: Uri, path: List[String]): Uri =
    path.foldLeft(base)(_ / _)

  /** A POST carrying the request body and the caller's key. */
  def post[F[_]](
      uri: Uri,
      apiKey: ApiKey,
      body: Json
  ): Request[F] =
    Request[F](Method.POST, uri)
      .withEntity(body)
      .putHeaders(authorization(apiKey))

  /** A GET carrying the caller's key. */
  def get[F[_]](uri: Uri, apiKey: ApiKey): Request[F] =
    Request[F](Method.GET, uri).putHeaders(authorization(apiKey))

  /** http4s renders this header as `<REDACTED>`, so the key cannot reach a log
    * through `Request#toString` or the `Logger` middleware.
    */
  private def authorization(apiKey: ApiKey): Authorization =
    Authorization(Credentials.Token(AuthScheme.Bearer, apiKey.bearer))

  /** The vendor's request id header, for support and for error reports. */
  def requestId(response: Response[?], vendor: Vendor): Option[String] =
    response.headers.get(CIString(vendor.requestIdHeader)).map(_.head.value)

  /** How long the server asked us to wait.
    *
    * The vendor's millisecond header wins when it sends one; otherwise
    * `Retry-After`, whether the server sent seconds or an HTTP date. The date
    * form needs the current time to become a delay, which is why the caller
    * passes it in rather than this reaching for a clock.
    */
  def retryAfter(
      response: Response[?],
      vendor: Vendor,
      nowEpochSecond: Long
  ): Option[FiniteDuration] =
    val millis = vendor.retryAfterMsHeader.flatMap(name =>
      response.headers
        .get(CIString(name))
        .map(h => Map(name.toLowerCase -> h.head.value))
        .flatMap(vendor.retryAfterMillis)
    )
    millis.orElse(
      response.headers.get[headers.`Retry-After`].map { header =>
        header.retry match
          case Left(date) =>
            RetryPolicy.retryAfterDelay(nowEpochSecond, date.epochSecond)
          case Right(delta) => math.max(0L, delta).seconds
      }
    )

  /** Read a successful body as JSON, then hand it to the vendor. */
  def decode[F[_]: Concurrent, A](response: Response[F])(
      f: Json => Either[Verdict4sError, A]
  ): F[Either[Verdict4sError, A]] =
    response
      .attemptAs[Json]
      .value
      .map:
        case Left(failure) =>
          Left(
            Verdict4sError
              .Decoding(s"the body was not JSON: ${failure.message}")
          )
        case Right(json) => f(json)

  /** Turn an unsuccessful response into a typed error.
    *
    * The body is read here, inside the caller's `use` block, because it is
    * needed to build the error; reading it later would be a use-after-release.
    */
  def error[F[_]: Concurrent](
      response: Response[F],
      vendor: Vendor,
      nowEpochSecond: Long
  ): F[Verdict4sError.Api] =
    response.bodyText.compile.string.map: raw =>
      val json = io.circe.parser.parse(raw).getOrElse(Json.fromString(raw))
      Verdict4sError.fromResponse(
        response.status.code,
        json,
        requestId(response, vendor),
        retryAfter(response, vendor, nowEpochSecond)
      )
