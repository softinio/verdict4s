package com.softinio.verdict4s.internal

import scala.concurrent.duration.*

import cats.effect.kernel.Concurrent
import cats.syntax.all.*
import com.softinio.verdict4s.Verdict4sError
import com.softinio.verdict4s.algebra.*
import io.circe.Decoder
import io.circe.Json
import org.http4s.*
import org.http4s.circe.*
import org.http4s.headers.Authorization
import org.typelevel.ci.CIString

/** Translating between the protocol and http4s.
  *
  * Everything HTTP-shaped lives here so that the client reads as protocol logic
  * and the core never sees an http4s type.
  */
private[verdict4s] object Wire:

  private val requestIdHeader = CIString(Defaults.RequestIdHeader)

  /** The evaluation endpoint under a given base. */
  def evaluateUri(base: Uri): Uri =
    Defaults.EvaluatePath.foldLeft(base)(_ / _)

  /** The models endpoint under a given base. */
  def modelsUri(base: Uri): Uri =
    Defaults.ModelsPath.foldLeft(base)(_ / _)

  /** A POST carrying the request body and the caller's key. */
  def post[F[_]: Concurrent](
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

  /** The `x-typesafe-request-id` header, for support and for error reports. */
  def requestId(response: Response[?]): Option[String] =
    response.headers.get(requestIdHeader).map(_.head.value)

  /** `Retry-After`, whether the server sent seconds or an HTTP date.
    *
    * The date form needs the current time to become a delay, which is why the
    * caller passes it in rather than this reaching for a clock.
    */
  def retryAfter(
      response: Response[?],
      nowEpochSecond: Long
  ): Option[FiniteDuration] =
    response.headers.get[headers.`Retry-After`].map { header =>
      header.retry match
        case Left(date) =>
          RetryPolicy.retryAfterDelay(nowEpochSecond, date.epochSecond)
        case Right(delta) => math.max(0L, delta).seconds
    }

  /** Decode a successful body, or say precisely where it went wrong. */
  def decode[F[_]: Concurrent, A: Decoder](
      response: Response[F]
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
        case Right(json) =>
          json
            .as[A]
            .left
            .map(f =>
              Verdict4sError.Decoding(
                f.message,
                Option(io.circe.CursorOp.opsToPath(f.history))
                  .filter(_.nonEmpty)
              )
            )

  /** Turn an unsuccessful response into a typed error.
    *
    * The body is read here, inside the caller's `use` block, because it is
    * needed to build the error; reading it later would be a use-after-release.
    */
  def error[F[_]: Concurrent](
      response: Response[F],
      nowEpochSecond: Long
  ): F[Verdict4sError.Api] =
    response.bodyText.compile.string.map: raw =>
      val json = io.circe.parser.parse(raw).getOrElse(Json.fromString(raw))
      Verdict4sError.fromResponse(
        response.status.code,
        json,
        requestId(response),
        retryAfter(response, nowEpochSecond)
      )
