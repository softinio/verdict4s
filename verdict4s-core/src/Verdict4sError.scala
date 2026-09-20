package com.softinio.verdict4s

import scala.concurrent.duration.FiniteDuration

import cats.data.NonEmptyChain
import com.softinio.verdict4s.algebra.ApiErrorBody
import com.softinio.verdict4s.algebra.ApiFailure
import io.circe.Json

/** Failures a verdict4s call can produce.
  *
  * Lives in the sans-IO core so that callers can pattern match on it without
  * taking a dependency on cats-effect or http4s.
  *
  * @group Errors
  */
sealed abstract class Verdict4sError(
    message: String,
    cause: Option[Throwable] = None
) extends Exception(message):
  cause.foreach(initCause)

object Verdict4sError:

  /** The request never produced a usable response. */
  final case class Transport(
      details: String,
      underlying: Option[Throwable] = None
  ) extends Verdict4sError(s"Transport failure: $details", underlying)

  /** The service answered, but the body was not what we expect.
    *
    * @param field
    *   dotted path to the offending value, such as `answers.tone.confidence`,
    *   when it can be identified
    */
  final case class Decoding(details: String, field: Option[String] = None)
      extends Verdict4sError(
        field.fold(s"Could not decode response: $details")(f =>
          s"Could not decode response at $f: $details"
        )
      )

  /** A single broken rule, found while building a request.
    *
    * This is the leaf that `ValidatedNec` accumulates, so that a request with
    * several malformed questions reports all of them rather than only the
    * first.
    */
  final case class Validation(field: String, details: String)
      extends Verdict4sError(s"Invalid $field: $details")

  /** The aggregate of every [[Validation]] found in one request.
    *
    * Raised in `F` once accumulation is over and sequencing begins.
    */
  final case class Invalid(errors: NonEmptyChain[Validation])
      extends Verdict4sError(
        s"${errors.length} validation error(s): " +
          errors.toNonEmptyList.toList.map(_.getMessage).mkString("; ")
      )

  /** The service answered with a non-success status.
    *
    * @param status
    *   the HTTP status code
    * @param details
    *   a short human-readable summary
    * @param requestId
    *   the `x-typesafe-request-id` header, for support
    * @param retryAfter
    *   the `Retry-After` header, already parsed
    * @param body
    *   the error payload, parsed leniently
    */
  final case class Api(
      status: Int,
      details: String,
      requestId: Option[String] = None,
      retryAfter: Option[FiniteDuration] = None,
      body: Option[ApiErrorBody] = None
  ) extends Verdict4sError(s"Service returned $status: $details"):

    /** The named classification of [[status]]. */
    def failure: ApiFailure = ApiFailure.fromStatus(status)

  /** Build an [[Api]] from a raw response.
    *
    * Pure and HTTP-library-free, so the whole status-to-error mapping is unit
    * tested in the core with no client involved.
    */
  def fromResponse(
      status: Int,
      rawBody: Json,
      requestId: Option[String] = None,
      retryAfter: Option[FiniteDuration] = None
  ): Api =
    val parsed = ApiErrorBody.fromJson(rawBody)
    val details = parsed.message.filter(_.nonEmpty).getOrElse(describe(status))
    Api(status, details, requestId, retryAfter, Some(parsed))

  /** Fallback wording when the body carries no message of its own. */
  private def describe(status: Int): String =
    ApiFailure.fromStatus(status) match
      case ApiFailure.BadRequest    => "the request was malformed"
      case ApiFailure.Unauthorized  => "missing or invalid API key"
      case ApiFailure.Forbidden     => "not permitted"
      case ApiFailure.NotFound      => "no such resource"
      case ApiFailure.Unprocessable => "the request failed validation"
      case ApiFailure.RateLimited   => "rate limit exceeded"
      case ApiFailure.Overloaded    => "the service is overloaded"
      case ApiFailure.Server => "the service failed to handle the request"
      case ApiFailure.Other  => "unexpected status"
