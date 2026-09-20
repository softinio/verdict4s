package com.softinio.verdict4s

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*

/** Runs identically on the JVM and on Node: `Client.fromHttpApp` serves the
  * routes in memory, so there is no socket and no port to bind.
  */
class Verdict4sClientTest extends CatsEffectSuite:

  private val baseUri = uri"https://example.invalid/api"

  private def clientFor(app: HttpApp[IO]): Verdict4sClient[IO] =
    Verdict4sClient[IO](Client.fromHttpApp(app), baseUri)

  test("health is true when the service returns 200"):
    val routes = HttpRoutes
      .of[IO] { case GET -> Root / "api" / "health" => Ok("healthy") }
      .orNotFound
    clientFor(routes).health.assertEquals(true)

  test("health is false when the service returns 503"):
    val routes = HttpRoutes
      .of[IO] { case GET -> Root / "api" / "health" => ServiceUnavailable() }
      .orNotFound
    clientFor(routes).health.assertEquals(false)
