package com.softinio.verdict4s

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.implicits.*

/** Smoke test for the platform transport wiring. Allocating the resource sets
  * up the client but makes no request, so this stays offline on both platforms.
  */
class Verdict4sTest extends CatsEffectSuite:

  test("default transport allocates on this platform"):
    Verdict4s.default[IO](uri"https://example.invalid/api").use(IO.pure).map {
      client =>
        assert(client ne null)
    }
