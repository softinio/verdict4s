package com.softinio.verdict4s.examples

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

import scala.jdk.CollectionConverters.*

import com.softinio.verdict4s.*
import com.softinio.verdict4s.algebra.*

/** **Strategy C — no effect system.**
  *
  * `verdict4s-core` carries only cats-core, circe and Iron: no effect type, no
  * HTTP client. [[SystemOne]] renders a request to JSON and parses a reply back
  * into typed answers, so the transport can be anything. Here it is the JDK's
  * own `java.net.http.HttpClient`, which needs no dependency at all.
  *
  * This is the shape for any library without a cats-effect instance — Twitter
  * `Future`, Akka or Pekko HTTP, sttp — only the three lines that send the
  * bytes change. For a non-blocking call, `HttpClient.sendAsync` returns a
  * `CompletableFuture`, and the render and parse steps stay exactly the same.
  */
object JdkHttpClientExample:

  enum Dept derives Options:
    case Billing, Technical, Sales

  private val questions = Ask(
    Question.noul("Does this convey urgency?"),
    Question.choice[Dept]("Which team should handle this?")
  )

  /** Render, send, parse. Every failure comes back as a `Verdict4sError`.
    *
    * @param http
    *   build one and reuse it; it holds a connection pool
    */
  def triage(
      apiKey: ApiKey,
      ticket: String,
      http: HttpClient
  ): Either[Verdict4sError, (Boolean, Dept)] =
    for
      body <- SystemOne.renderAsk(questions, ticket)
      response <- send(http, apiKey, body)
      answers <- read(response)
    yield
      val (urgent, dept) = answers
      (urgent.isTrue(), dept.choice)

  private def send(
      http: HttpClient,
      apiKey: ApiKey,
      body: String
  ): Either[Verdict4sError, HttpResponse[String]] =
    val builder = HttpRequest
      .newBuilder(URI.create(SystemOne.evaluateUrl))
      .POST(HttpRequest.BodyPublishers.ofString(body))
    val request = SystemOne
      .headers(apiKey)
      .foldLeft(builder)((b, header) => b.header(header._1, header._2))
      .build()
    try Right(http.send(request, HttpResponse.BodyHandlers.ofString()))
    catch
      case e: IOException =>
        Left(Verdict4sError.Transport(e.getMessage, Some(e)))
      case e: InterruptedException =>
        Thread.currentThread().interrupt()
        Left(Verdict4sError.Transport("interrupted", Some(e)))

  private def read(response: HttpResponse[String]) =
    if response.statusCode >= 400 then
      val headers = response.headers.map.asScala.map((name, values) =>
        name -> values.asScala.headOption.getOrElse("")
      )
      Left(
        SystemOne.parseError(response.statusCode, response.body, headers.toMap)
      )
    else SystemOne.parseAsk(questions, response.body)
