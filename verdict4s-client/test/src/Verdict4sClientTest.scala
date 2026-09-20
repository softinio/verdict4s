package com.softinio.verdict4s

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.Ref
import com.softinio.verdict4s.algebra.*
import io.circe.Json
import io.circe.parser
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*

/** Runs identically on the JVM and on Node: `Client.fromHttpApp` serves the
  * routes in memory, so there is no socket and no port to bind. That is the
  * payoff of the client taking a `Client[F]` rather than building one — every
  * double below is a handful of ordinary lines, with no mocking library.
  */
class Verdict4sClientTest extends CatsEffectSuite:

  private val baseUri = uri"https://example.invalid"
  private val key = ApiKey.fromString("sk-test").toOption.get

  enum Dept derives Options:
    case Billing, Technical, Sales

  private val ask = Ask(
    Question.noul("Does this convey urgency?"),
    Question.choice[Dept]("Which team should handle this?")
  )

  private val okBody = """
    {
      "model": "jev-1.13.0",
      "answers": {
        "q00": {"type": "noul", "noul": 0.95},
        "q01": {
          "type": "choice",
          "choice": "Billing",
          "probabilities": {"Billing": 0.88, "Technical": 0.12, "Sales": 0.0},
          "confidence": 0.81
        }
      },
      "usage": {"input_tokens": 296, "output_tokens": 20}
    }
  """

  private def clientFor(
      app: HttpApp[IO],
      config: ClientConfig = ClientConfig.default
  ): Verdict4sClient[IO] =
    Verdict4sClient[IO](Client.fromHttpApp(app), key, baseUri, config)
      .withJitter(Jitter.constant[IO](0.5))

  private def respond(body: String): HttpApp[IO] =
    HttpRoutes
      .of[IO] { case POST -> Root / "v1" / "systemone" => Ok(body) }
      .orNotFound

  test("ask posts to the documented endpoint and returns typed answers"):
    clientFor(respond(okBody))
      .ask(ask, "Help! My payouts are failing.")
      .map:
        case (urgency, dept) =>
          val u: NoulAnswer = urgency
          val d: ChoiceAnswer[Dept] = dept
          assert(u.isTrue())
          assertEquals(d.choice, Dept.Billing)

  test("the request actually sent carries the documented shape"):
    // The route captures the body, so this asserts the bytes on the wire
    // rather than only what came back.
    for
      seen <- Ref[IO].of(Option.empty[Json])
      app = HttpRoutes
        .of[IO] { case req @ POST -> Root / "v1" / "systemone" =>
          req.as[Json].flatMap(j => seen.set(Some(j))) *> Ok(okBody)
        }
        .orNotFound
      _ <- clientFor(app).ask(ask, "payouts failing")
      body <- seen.get
    yield
      val json = body.get
      assertEquals(
        json.hcursor.downField("state").as[String],
        Right("payouts failing")
      )
      assertEquals(
        json.hcursor.downField("model").as[String],
        Right("jev-latest")
      )
      assertEquals(
        json.hcursor.downField("questions").keys.map(_.toList),
        Some(List("q00", "q01"))
      )
      assertEquals(
        json.hcursor
          .downField("questions")
          .downField("q00")
          .downField("type")
          .as[String],
        Right("noul")
      )

  test("the API key is sent as a bearer token"):
    for
      seen <- Ref[IO].of(Option.empty[String])
      app = HttpRoutes
        .of[IO] { case req @ POST -> Root / "v1" / "systemone" =>
          seen.set(
            req.headers
              .get[headers.Authorization]
              .map(_.credentials.renderString)
          ) *>
            Ok(okBody)
        }
        .orNotFound
      _ <- clientFor(app).ask(ask, "s")
      header <- seen.get
    yield assertEquals(header, Some("Bearer sk-test"))

  test("http4s redacts the key, so it cannot reach a log by accident"):
    val request =
      com.softinio.verdict4s.internal.Wire
        .post[IO](uri"https://x/y", key, Json.obj())
    assert(!request.toString.contains("sk-test"), request.toString)

  test("a content type is set so the service parses the body"):
    for
      seen <- Ref[IO].of(Option.empty[MediaType])
      app = HttpRoutes
        .of[IO] { case req @ POST -> Root / "v1" / "systemone" =>
          seen.set(req.contentType.map(_.mediaType)) *> Ok(okBody)
        }
        .orNotFound
      _ <- clientFor(app).ask(ask, "s")
      ct <- seen.get
    yield assertEquals(ct, Some(MediaType.application.json))

  test("evaluate exposes the map-based escape hatch"):
    val request = ask.request("s", Model.JevLatest).toOption.get
    clientFor(respond(okBody)).evaluate(request).map { evaluation =>
      assertEquals(evaluation.usage, Usage(296, 20))
      assertEquals(evaluation.answers.size, 2)
    }

  test("a question invalid at construction never reaches the network"):
    val app = HttpRoutes
      .of[IO] { case POST -> Root / "v1" / "systemone" =>
        IO.raiseError(new AssertionError("must not be called"))
      }
      .orNotFound
    clientFor(app)
      .ask(Ask(Question.noul("", "", "")), "s")
      .attempt
      .map:
        case Left(Verdict4sError.Invalid(errors)) =>
          assertEquals(errors.length, 3L)
        case other => fail(s"expected Invalid, got $other")

  test("models decodes the list endpoint"):
    val app = HttpRoutes
      .of[IO] { case GET -> Root / "v1" / "models" =>
        Ok(
          """[{"name":"jev-latest","description":"alias","release_date":"2025-06-01"}]"""
        )
      }
      .orNotFound
    clientFor(app).models.map: cards =>
      assertEquals(cards.map(_.name.name), List("jev-latest"))

  test("a body that is not JSON is a decoding error, not an exception"):
    clientFor(respond("<html>502</html>"))
      .ask(ask, "s")
      .attempt
      .map:
        case Left(e: Verdict4sError.Decoding) =>
          assert(e.getMessage.contains("not JSON"))
        case other => fail(s"expected Decoding, got $other")

  test("an answer the questions did not ask for is a decoding error"):
    val mismatched = """
      {"model":"jev-1.13.0",
       "answers":{"q00":{"type":"noul","noul":0.9}},
       "usage":{"input_tokens":1,"output_tokens":1}}
    """
    clientFor(respond(mismatched))
      .ask(ask, "s")
      .attempt
      .map:
        case Left(e: Verdict4sError.Decoding) =>
          assert(e.getMessage.contains("q01"))
        case other => fail(s"expected Decoding, got $other")
