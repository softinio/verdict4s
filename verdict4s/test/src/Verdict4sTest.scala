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

import cats.effect.IO
import com.softinio.verdict4s.algebra.ApiKey
import com.softinio.verdict4s.algebra.ClientConfig
import com.softinio.verdict4s.algebra.Model
import munit.CatsEffectSuite
import org.http4s.implicits.*

/** Allocates the real transport for this platform and makes no request, so it
  * runs offline on both the JVM and Node.
  */
class Verdict4sTest extends CatsEffectSuite:

  private val key = ApiKey.fromString("sk-not-used").toOption.get

  test("default allocates a client on whichever platform this is"):
    Verdict4s
      .default[IO](key)
      .use(IO.pure)
      .map(client => assert(client ne null))

  test("default points at the service unless told otherwise"):
    assertEquals(
      Verdict4sClient.DefaultBaseUri.renderString,
      "https://api.typesafe.ai"
    )

  test("configuration is carried through to the client"):
    val config = ClientConfig.default
      .withModel(Model.JevPreview)
      .withoutRetries
    Verdict4s
      .default[IO](key, uri"https://example.invalid", config)
      .use(IO.pure)
      .map: client =>
        assertEquals(client.config.model, Model.JevPreview)
        assertEquals(client.config.retry.maxRetries, 0)
