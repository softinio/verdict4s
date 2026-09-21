package com.softinio.verdict4s.algebra

import cats.Eq
import cats.Show
import cats.data.ValidatedNec
import cats.syntax.all.*
import com.softinio.verdict4s.Verdict4sError

/** A TypeSafe API key.
  *
  * Deliberately a real class rather than an opaque type over `String`. An
  * opaque type erases to its underlying type, so `toString` would be
  * `String.toString` and the secret would surface in every failed assertion,
  * every `Show` of a containing case class, and every stack trace. Here
  * `toString` and `Show` both render `ApiKey(***)`, while `equals` and
  * `hashCode` still behave, so keys remain usable as map keys and in tests.
  *
  * The secret itself is reachable only from within `com.softinio.verdict4s`,
  * which is what lets the client set the `Authorization` header while keeping
  * the value away from callers.
  *
  * @group Configuration
  */
final class ApiKey private (private val secret: String):

  override def toString: String = "ApiKey(***)"

  override def equals(other: Any): Boolean = other match
    case that: ApiKey => that.secret == secret
    case _            => false

  override def hashCode: Int = secret.hashCode

  /** The raw key, for building an `Authorization` header. */
  private[verdict4s] def bearer: String = secret

/** Validating and wrapping a key. */
object ApiKey:

  /** Validate and wrap a key.
    *
    * @param raw
    *   the key as supplied by the caller; surrounding whitespace is trimmed,
    *   because keys are usually pasted or read from an environment variable
    */
  def fromString(raw: String): ValidatedNec[Verdict4sError.Validation, ApiKey] =
    val trimmed = if raw == null then "" else raw.trim
    if trimmed.isEmpty then
      Verdict4sError.Validation("apiKey", "must not be blank").invalidNec
    else ApiKey(trimmed).validNec

  private def apply(secret: String): ApiKey = new ApiKey(secret)

  given Show[ApiKey] = Show.show(_ => "ApiKey(***)")
  given Eq[ApiKey] = Eq.fromUniversalEquals
