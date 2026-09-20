package com.softinio.verdict4s.examples

import com.softinio.verdict4s.*
import com.softinio.verdict4s.algebra.*
import com.twitter.finagle.Http
import com.twitter.finagle.Service
import com.twitter.finagle.http.Method
import com.twitter.finagle.http.Request
import com.twitter.finagle.http.Response
import com.twitter.io.Buf
import com.twitter.util.Future

/** **Strategy C — sans-IO, driven by Finagle.**
  *
  * Finagle publishes for Scala 2.13 only, but Scala 3 can consume Scala 2.13
  * artifacts, so a Scala 3 service can depend on `finagle-http_2.13` and use it
  * normally — this file compiles as part of the build and proves it. Add the
  * dependency with its suffix spelled out, since the usual `::` would look for
  * a `_3` build that does not exist:
  *
  * ```scala
  * mvn"com.twitter:finagle-http_2.13:24.2.0"
  * ```
  *
  * A **Finatra** service is a different matter: Finatra is also 2.13-only, and
  * a 2.13 application cannot depend on verdict4s, which is Scala 3-only. The
  * route there is a Scala 3 module that owns the verdict4s calls.
  *
  * Nothing here touches cats-effect. [[SystemOne]] renders the request and
  * parses the reply; Finagle just moves the bytes.
  */
object FinagleExample:

  enum Dept derives Options:
    case Billing, Technical, Sales

  private val questions = Ask(
    Question.noul("Does this convey urgency?"),
    Question.choice[Dept]("Which team should handle this?")
  )

  /** A Finagle client for the service. Build one and keep it. */
  def newService: Service[Request, Response] =
    Http.client
      .withTls("api.typesafe.ai")
      .newService("api.typesafe.ai:443")

  /** Ask the questions over a Finagle service. */
  def triage(
      apiKey: ApiKey,
      ticket: String,
      service: Service[Request, Response]
  ): Future[(Boolean, Dept)] =
    SystemOne.renderAsk(questions, ticket) match
      case Left(invalid) => Future.exception(invalid)
      case Right(body)   => service(request(apiKey, body)).flatMap(read)

  private def request(apiKey: ApiKey, body: String): Request =
    val req = Request(Method.Post, "/v1/systemone")
    req.host = "api.typesafe.ai"
    SystemOne
      .headers(apiKey)
      .foreach((name, value) => req.headerMap.set(name, value))
    req.content = Buf.Utf8(body)
    req.contentLength = body.getBytes("UTF-8").length.toLong
    req

  private def read(response: Response): Future[(Boolean, Dept)] =
    val body = response.contentString
    if response.statusCode >= 400 then
      Future.exception(
        SystemOne.parseError(
          response.statusCode,
          body,
          response.headerMap.toMap
        )
      )
    else
      SystemOne.parseAsk(questions, body) match
        case Left(error)           => Future.exception(error)
        case Right((urgent, dept)) =>
          Future.value((urgent.isTrue(), dept.choice))
