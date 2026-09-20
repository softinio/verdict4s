package com.softinio.verdict4s

/** Failures a verdict4s call can produce.
  *
  * Lives in the sans-IO core so that callers can pattern match on it without
  * taking a dependency on cats-effect or http4s.
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

  /** The service answered, but the body was not what we expect. */
  final case class Decoding(details: String)
      extends Verdict4sError(s"Could not decode response: $details")

  /** The service answered with a non-success status. */
  final case class Api(status: Int, details: String)
      extends Verdict4sError(s"Service returned $status: $details")
