package com.softinio.verdict4s.algebra

/** A named classification of an unsuccessful HTTP status.
  *
  * Matching on this is steadier than matching on raw status codes: the two
  * statuses that callers most often want to treat alike — 429 and 529 — are
  * numerically unrelated, and 5xx is a range rather than a value.
  *
  * @group Errors
  */
enum ApiFailure:

  /** 400 — the request was malformed. */
  case BadRequest

  /** 401 — missing or invalid API key. */
  case Unauthorized

  /** 403 — the key is valid but not permitted to do this. */
  case Forbidden

  /** 404 — no such resource. */
  case NotFound

  /** 422 — the body failed server-side validation. */
  case Unprocessable

  /** 429 — rate limited. Retryable after a delay. */
  case RateLimited

  /** 529 — TypeSafe is temporarily overloaded. Retryable after a delay. */
  case Overloaded

  /** Any other 5xx. */
  case Server

  /** A status this library does not classify. */
  case Other

  /** Whether the service invites a retry for this class of failure.
    *
    * Note this is advisory: the authoritative decision is
    * [[RetryPolicy.retriesStatus]], which the caller can configure.
    */
  def isTransient: Boolean = this match
    case RateLimited | Overloaded | Server => true
    case _                                 => false

object ApiFailure:

  /** Classify an HTTP status code.
    *
    * 529 is checked before the generic 5xx branch because TypeSafe uses it for
    * overload specifically, and callers back off differently for it.
    */
  def fromStatus(code: Int): ApiFailure = code match
    case 400                       => BadRequest
    case 401                       => Unauthorized
    case 403                       => Forbidden
    case 404                       => NotFound
    case 422                       => Unprocessable
    case 429                       => RateLimited
    case 529                       => Overloaded
    case c if c >= 500 && c <= 599 => Server
    case _                         => Other
