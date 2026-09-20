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
