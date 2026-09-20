package com.softinio.verdict4s.algebra

/** Wire-level constants, kept in the effect-free core so that the client, the
  * environment readers and the documentation all agree on one source of truth.
  *
  * The base URL is a `String` rather than an `org.http4s.Uri` because core must
  * not depend on http4s. The client turns it into a compile-time-checked
  * literal, so a malformed default can never reach runtime.
  *
  * @group Configuration
  */
object Defaults:

  /** Root of the TypeSafe API. */
  val BaseUrl: String = "https://api.typesafe.ai"

  /** Path segments of the evaluation endpoint, relative to [[BaseUrl]]. */
  val EvaluatePath: List[String] = List("v1", "systemone")

  /** Path segments of the models endpoint, relative to [[BaseUrl]]. */
  val ModelsPath: List[String] = List("v1", "models")

  /** Response header carrying the per-request correlation id. */
  val RequestIdHeader: String = "x-typesafe-request-id"

  /** Environment variable holding the API key. */
  val ApiKeyEnv: String = "TYPESAFE_API_KEY"

  /** Environment variable overriding [[BaseUrl]]. */
  val BaseUrlEnv: String = "TYPESAFE_BASE_URL"

  /** Environment variable overriding the default model. */
  val DefaultModelEnv: String = "TYPESAFE_DEFAULT_MODEL"
