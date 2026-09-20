package com.softinio.verdict4s

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.syntax.all.*
import com.softinio.verdict4s.algebra.ApiKey
import com.softinio.verdict4s.algebra.ClientConfig
import com.softinio.verdict4s.algebra.Defaults
import com.softinio.verdict4s.algebra.Model
import org.http4s.Uri

/** Reading configuration from the environment. JVM only.
  *
  * A separate object rather than methods on [[Verdict4s]], and not by
  * preference: a Scala `object` cannot be split across `src/` and `src-jvm/`,
  * so adding these there would force `default` to be duplicated into both
  * platform directories — exactly the drift that keeping one signature on both
  * platforms is meant to prevent.
  *
  * Scala.js has no environment to read, so this simply does not exist there.
  * The JVM artifact having a slightly larger surface than the JS one is normal
  * and does not affect [[Verdict4s.default]].
  *
  * Variable names match the official SDKs, so a project can share them.
  *
  * @group Configuration
  */
object Verdict4sEnv:

  /** The key from `TYPESAFE_API_KEY`, failing if it is absent or blank. */
  def apiKey[F[_]: Sync]: F[ApiKey] =
    read(Defaults.ApiKeyEnv).flatMap:
      case None =>
        Sync[F].raiseError(
          Verdict4sError.Validation(
            Defaults.ApiKeyEnv,
            "environment variable is not set"
          )
        )
      case Some(raw) =>
        Sync[F].fromEither(
          ApiKey
            .fromString(raw)
            .toEither
            .leftMap(Verdict4sError.Invalid(_))
        )

  /** The base URI from `TYPESAFE_BASE_URL`, or the service's own address. */
  def baseUri[F[_]: Sync]: F[Uri] =
    read(Defaults.BaseUrlEnv).flatMap:
      case None      => Sync[F].pure(Verdict4sClient.DefaultBaseUri)
      case Some(raw) =>
        Sync[F].fromEither(
          Uri
            .fromString(raw)
            .leftMap(f =>
              Verdict4sError.Validation(Defaults.BaseUrlEnv, f.sanitized)
            )
        )

  /** The default model from `TYPESAFE_DEFAULT_MODEL`, or `jev-latest`. */
  def model[F[_]: Sync]: F[Model] =
    read(Defaults.DefaultModelEnv).flatMap:
      case None      => Sync[F].pure(Model.JevLatest)
      case Some(raw) =>
        Sync[F].fromEither(
          Model.fromString(raw).toEither.leftMap(Verdict4sError.Invalid(_))
        )

  /** A client configured entirely from the environment. */
  def default[F[_]: Async]: Resource[F, Verdict4sClient[F]] =
    for
      key <- Resource.eval(apiKey[F])
      uri <- Resource.eval(baseUri[F])
      chosen <- Resource.eval(model[F])
      client <- Verdict4s
        .default[F](key, uri, ClientConfig.default.withModel(chosen))
    yield client

  private def read[F[_]: Sync](name: String): F[Option[String]] =
    Sync[F].delay(sys.env.get(name).map(_.trim).filter(_.nonEmpty))
