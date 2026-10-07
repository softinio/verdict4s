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

package com.softinio.verdict4s

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.syntax.all.*
import com.softinio.verdict4s.algebra.ApiKey
import com.softinio.verdict4s.algebra.ClientConfig
import com.softinio.verdict4s.algebra.Model
import com.softinio.verdict4s.jev.JevVendor
import com.softinio.verdict4s.openai.OpenAIVendor
import org.http4s.Uri

/** Reading configuration from the environment. JVM only.
  *
  * A separate object rather than methods on [[Verdict4s]], and not by
  * preference: a Scala `object` cannot be split across `src/` and `src-jvm/`,
  * so adding these there would force `jev` and `openAI` to be duplicated into
  * both platform directories — exactly the drift that keeping one signature on
  * both platforms is meant to prevent.
  *
  * Scala.js has no environment to read, so this simply does not exist there.
  * The JVM artifact having a slightly larger surface than the JS one is normal
  * and does not affect [[Verdict4s]].
  *
  * Variable names match each vendor's official SDKs, so a project can share
  * them:
  *
  * | Variable                 | Meaning                                          |
  * |:-------------------------|:-------------------------------------------------|
  * | `VERDICT4S_VENDOR`       | `jev` (the default) or `openai`, for [[default]] |
  * | `TYPESAFE_API_KEY`       | Jev's API key                                    |
  * | `TYPESAFE_BASE_URL`      | Jev's address                                    |
  * | `TYPESAFE_DEFAULT_MODEL` | Jev's default model                              |
  * | `OPENAI_API_KEY`         | OpenAI's API key                                 |
  * | `OPENAI_BASE_URL`        | OpenAI's address, including `/v1`                |
  *
  * @group Configuration
  */
object Verdict4sEnv:

  /** Environment variable choosing the vendor for [[default]]. */
  val VendorEnv: String = "VERDICT4S_VENDOR"

  /** The vendor named by `VERDICT4S_VENDOR`: `jev` (the default when it is
    * unset) or `openai`, in any case.
    */
  def vendor[F[_]: Sync]: F[Vendor] =
    read(VendorEnv).flatMap:
      case None      => Sync[F].pure(Vendor.Jev)
      case Some(raw) =>
        raw.toLowerCase match
          case "jev" | "typesafe" => Sync[F].pure(Vendor.Jev)
          case "openai"           => Sync[F].pure(Vendor.OpenAI)
          case other              =>
            Sync[F].raiseError(
              Verdict4sError.Validation(
                VendorEnv,
                s"'$other' is not a vendor; use 'jev' or 'openai'"
              )
            )

  /** Jev's key from `TYPESAFE_API_KEY`, failing if it is absent or blank. */
  def apiKey[F[_]: Sync]: F[ApiKey] = key(JevVendor.ApiKeyEnv)

  /** Jev's base URI from `TYPESAFE_BASE_URL`, or the service's own address. */
  def baseUri[F[_]: Sync]: F[Uri] =
    uri(JevVendor.BaseUrlEnv, Verdict4sClient.JevBaseUri)

  /** Jev's default model from `TYPESAFE_DEFAULT_MODEL`, or `jev-latest`. */
  def model[F[_]: Sync]: F[Model] =
    read(JevVendor.DefaultModelEnv).flatMap:
      case None      => Sync[F].pure(Model.JevLatest)
      case Some(raw) =>
        Sync[F].fromEither(
          Model.fromString(raw).toEither.leftMap(Verdict4sError.Invalid(_))
        )

  /** OpenAI's key from `OPENAI_API_KEY`, failing if it is absent or blank. */
  def openAIApiKey[F[_]: Sync]: F[ApiKey] = key(OpenAIVendor.ApiKeyEnv)

  /** OpenAI's base URI from `OPENAI_BASE_URL`, or the service's own address.
    * Includes `/v1`, as it does for OpenAI's SDKs.
    */
  def openAIBaseUri[F[_]: Sync]: F[Uri] =
    uri(OpenAIVendor.BaseUrlEnv, Verdict4sClient.OpenAIBaseUri)

  /** A Jev client configured entirely from the environment. */
  def jev[F[_]: Async]: Resource[F, JevClient[F]] =
    for
      key <- Resource.eval(apiKey[F])
      uri <- Resource.eval(baseUri[F])
      chosen <- Resource.eval(model[F])
      client <- Verdict4s
        .jev[F](key, uri, ClientConfig.default.withModel(chosen))
    yield client

  /** An OpenAI Decisions client configured entirely from the environment. */
  def openAI[F[_]: Async]: Resource[F, OpenAIClient[F]] =
    for
      key <- Resource.eval(openAIApiKey[F])
      uri <- Resource.eval(openAIBaseUri[F])
      client <- Verdict4s.openAI[F](key, uri)
    yield client

  /** A client for the vendor named by `VERDICT4S_VENDOR`, configured entirely
    * from the environment. Jev when the variable is unset.
    */
  def default[F[_]: Async]: Resource[F, Verdict4sClient[F]] =
    Resource
      .eval(vendor[F])
      .flatMap:
        case _: OpenAIVendor => openAI[F]
        case _               => jev[F]

  private def key[F[_]: Sync](name: String): F[ApiKey] =
    read(name).flatMap:
      case None =>
        Sync[F].raiseError(
          Verdict4sError.Validation(name, "environment variable is not set")
        )
      case Some(raw) =>
        Sync[F].fromEither(
          ApiKey
            .fromString(raw)
            .toEither
            .leftMap(Verdict4sError.Invalid(_))
        )

  private def uri[F[_]: Sync](name: String, fallback: Uri): F[Uri] =
    read(name).flatMap:
      case None      => Sync[F].pure(fallback)
      case Some(raw) =>
        Sync[F].fromEither(
          Uri
            .fromString(raw)
            .leftMap(f => Verdict4sError.Validation(name, f.sanitized))
        )

  private def read[F[_]: Sync](name: String): F[Option[String]] =
    Sync[F].delay(sys.env.get(name).map(_.trim).filter(_.nonEmpty))
