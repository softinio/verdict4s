package com.softinio.verdict4s.examples

import com.softinio.verdict4s.*
import com.softinio.verdict4s.algebra.*
import com.twitter.util.Future

/** **Strategy C — sans-IO, no effect system at all.**
  *
  * `verdict4s-core` carries only cats-core, circe and Iron: no effect type, no
  * HTTP client. [[SystemOne]] renders a request to JSON and parses a reply back
  * into typed answers, so you can send the bytes with whatever you already have
  * — here a Twitter `Future`.
  *
  * This is the payoff of keeping the core effect-free. It is also the only
  * route for Twitter `Future`: `catbird-effect` has no Scala 3 build, so there
  * is no cats-effect instance to bridge through.
  *
  * The same shape works for Akka and Pekko HTTP, for sttp, and for a plain
  * blocking client.
  */
object TwitterFutureExample:

  enum Dept derives Options:
    case Billing, Technical, Sales

  private val questions = Ask(
    Question.noul("Does this convey urgency?"),
    Question.choice[Dept]("Which team should handle this?")
  )

  /** Whatever HTTP client you already have. */
  type Post = (String, List[(String, String)], String) => Future[(Int, String)]

  /** Build the request, send it however you like, read the answers back. */
  def triage(
      apiKey: ApiKey,
      ticket: String,
      post: Post
  ): Future[(Boolean, Dept)] =
    SystemOne.renderAsk(questions, ticket) match
      case Left(invalid) => Future.exception(invalid)
      case Right(body)   =>
        post(SystemOne.evaluateUrl, SystemOne.headers(apiKey), body).flatMap:
          (status, responseBody) =>
            if status >= 400 then
              Future.exception(SystemOne.parseError(status, responseBody))
            else
              SystemOne.parseAsk(questions, responseBody) match
                case Left(error)           => Future.exception(error)
                case Right((urgent, dept)) =>
                  Future.value((urgent.isTrue(), dept.choice))
