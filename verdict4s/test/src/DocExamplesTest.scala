package com.softinio.verdict4s

import cats.effect.IO
import com.softinio.verdict4s.algebra.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*

/** Guards the narrative documentation.
  *
  * Laika cannot compile-check Scala snippets against the classpath the way
  * scaladoc's `-snippet-compiler` does for docstrings, so the examples in the
  * Markdown pages under `docs` would otherwise be free to drift out of step
  * with the API. Every non-trivial example there is mirrored here as real code:
  * if a signature changes, this fails even though the Markdown cannot.
  *
  * Keep these short. The duplication is the cost of the guard.
  */
class DocExamplesTest extends CatsEffectSuite:

  // docs/typed-questions.md
  enum Dept derives Options:
    case Billing, Technical, Sales

  enum Frustration derives Levels:
    case Calm, Frustrated, VeryAngry

  final case class Route(team: Dept, escalate: Boolean)

  private val key = ApiKey.fromString("sk-test").toOption.get

  private val cannedResponse = """
    {"model":"jev-1.13.0",
     "answers":{
       "q00":{"type":"noul","noul":0.95},
       "q01":{"type":"choice","choice":"Billing",
              "probabilities":{"Billing":0.9,"Technical":0.1,"Sales":0.0},
              "confidence":0.81}},
     "usage":{"input_tokens":10,"output_tokens":2}}
  """

  private val questions = Ask(
    Question.noul("Does this convey urgency?"),
    Question.choice[Dept]("Which team should handle this?")
  )

  // docs/getting-started.md, docs/client-module.md
  test("the getting-started example compiles and runs against a fake"):
    val routes = HttpRoutes
      .of[IO] { case POST -> Root / "v1" / "systemone" => Ok(cannedResponse) }
      .orNotFound
    val client =
      Verdict4sClient[IO](
        Client.fromHttpApp(routes),
        key,
        uri"https://example.invalid"
      )
    client
      .ask(questions, "Help! My payouts have been failing.")
      .map: (urgent, team) =>
        assert(urgent.isTrue())
        assertEquals(team.choice, Dept.Billing)

  // docs/getting-started.md -- reading confidence
  test("the confidence-gating example compiles"):
    val routes = HttpRoutes
      .of[IO] { case POST -> Root / "v1" / "systemone" => Ok(cannedResponse) }
      .orNotFound
    val client =
      Verdict4sClient[IO](
        Client.fromHttpApp(routes),
        key,
        uri"https://example.invalid"
      )
    client
      .ask(questions, "s")
      .map: (_, team) =>
        val confident = (team.confidence.value: Double) >= 0.8
        assert(confident)

  // docs/typed-questions.md -- your own answer types
  test("the Route mapping example compiles and produces the caller's type"):
    val route: Question[Route] =
      Question
        .choice[Dept]("Which team should handle this?")
        .map(a =>
          Route(a.choice, escalate = (a.confidence.value: Double) < 0.7)
        )
    assert(route.spec.isDefined)

  // docs/typed-questions.md -- derived levels
  test("the derived Levels example compiles"):
    val q = Question.scoreOf[Frustration]("How frustrated is the customer?")
    assert(q.spec.isDefined)

  // docs/core-module.md -- no effect system
  test("the no-effect-system example compiles and round trips"):
    val body = SystemOne.renderAsk(questions, "Help! My payouts are failing.")
    assert(body.isRight)
    SystemOne.parseAsk(questions, cannedResponse) match
      case Right((urgent, dept)) =>
        assert(urgent.isTrue())
        assertEquals(dept.choice, Dept.Billing)
      case Left(e) => fail(s"parseAsk failed: $e")

  // docs/core-module.md -- validation without sending
  test("the pure-validation example behaves as documented"):
    val tooFew = Ask(Question.score("How frustrated?", Seq("Calm")))
      .request("state", Model.JevLatest)
    assert(tooFew.isInvalid)
    val ok = Ask(Question.score("How frustrated?", Seq("Calm", "Cross")))
      .request("state", Model.JevLatest)
    assert(ok.isValid)

  // docs/errors-and-retries.md
  test("the error-matching example is exhaustive over the ADT"):
    val describe: Verdict4sError => String =
      case e: Verdict4sError.Api => s"service said ${e.status}: ${e.details}"
      case e: Verdict4sError.Decoding => s"unexpected response: ${e.getMessage}"
      case e: Verdict4sError.Transport =>
        s"could not reach service: ${e.details}"
      case e: Verdict4sError.Validation => s"bad question: ${e.getMessage}"
      case e: Verdict4sError.Invalid    => s"${e.errors.length} problems"
    assert(describe(Verdict4sError.Api(503, "down")).contains("503"))

  // docs/errors-and-retries.md -- tuning retries
  test("the retry configuration example compiles"):
    import scala.concurrent.duration.*
    val config = ClientConfig.default.withRetry(
      RetryPolicy(maxRetries = 5, backoffMax = 30.seconds).toOption.get
    )
    assertEquals(config.retry.maxRetries, 5)
    assertEquals(ClientConfig.default.withoutRetries.retry.maxRetries, 0)
