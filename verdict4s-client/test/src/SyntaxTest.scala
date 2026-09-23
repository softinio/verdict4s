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
import com.softinio.verdict4s.algebra.*
import com.softinio.verdict4s.syntax.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*

class SyntaxTest extends CatsEffectSuite:

  private val key = ApiKey.fromString("sk-test").toOption.get
  private val ask = Ask(Question.noul("urgent?"))

  private val okBody = """
    {"model":"jev-1.13.0",
     "answers":{"q00":{"type":"noul","noul":0.95}},
     "usage":{"input_tokens":1,"output_tokens":1}}
  """

  private val client: Verdict4sClient[IO] =
    Verdict4sClient[IO](
      Client.fromHttpApp(
        HttpRoutes
          .of[IO] { case POST -> Root / "v1" / "systemone" => Ok(okBody) }
          .orNotFound
      ),
      key,
      uri"https://example.invalid"
    )

  test("runWith takes the client explicitly"):
    ask.runWith(client, "s").map(answers => assert(answers.head.isTrue()))

  test("run picks the client up from implicit scope"):
    given Verdict4sClient[IO] = client
    ask.run("s").map(answers => assert(answers.head.isTrue()))
