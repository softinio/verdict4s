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
import cats.effect.Ref
import cats.syntax.all.*
import com.softinio.verdict4s.algebra.*
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client

/** Choosing a vendor's client and composing their capabilities, which no single
  * vendor's contract covers.
  */
class ClientConstructionTest extends CatsEffectSuite:

  private val key = ApiKey.fromString("sk-test").toOption.get

  private val custom = new Vendor:
    def name = "Custom"
    def defaultBaseUrl = "https://decisions.example.com"
    def defaultModel = Model.JevLatest
    def evaluatePath = List("decide")
    def requestIdHeader = "x-request-id"
    def encode(request: EvaluationRequest) = Vendor.Jev.encode(request)
    def decode(body: Json) = Vendor.Jev.decode(body)

  test("Verdict4sClient.of picks the vendor's own client"):
    val http = Client.fromHttpApp(HttpApp.notFound[IO])
    assert(
      Verdict4sClient
        .of[IO](http, Vendor.OpenAI, key)
        .isInstanceOf[OpenAIClient[IO]]
    )
    assert(
      Verdict4sClient.of[IO](http, Vendor.Jev, key).isInstanceOf[JevClient[IO]]
    )
    val generic = Verdict4sClient.of[IO](http, custom, key)
    assert(!generic.isInstanceOf[JevClient[IO]])
    assertEquals(generic.vendor.name, "Custom")

  test("of keeps the vendor value it is given, and its default base URL"):
    val tagged = Vendor.OpenAI.withSafetyIdentifier("user-42")
    val vendors = List[Vendor](Vendor.Jev, tagged, custom)
    for
      seen <- Ref[IO].of(List.empty[(String, Option[String])])
      http = Client.fromHttpApp(
        HttpApp[IO](req =>
          req
            .as[Json]
            .flatMap(body =>
              seen.update(
                _ :+ (req.uri.renderString ->
                  body.hcursor.get[String]("safety_identifier").toOption)
              )
            )
            .as(Response[IO](Status.NotFound))
        )
      )
      _ <- vendors.traverse_(v =>
        Verdict4sClient
          .of[IO](http, v, key)
          .ask(Ask(Question.noul("u?")), "s")
          .attempt
      )
      sent <- seen.get
    yield assertEquals(
      sent,
      List(
        Vendor.Jev.evaluateUrl -> None,
        tagged.evaluateUrl -> Some("user-42"),
        custom.evaluateUrl -> None
      )
    )

  test("capabilities compose with intersection types"):
    def catalogued(c: Verdict4sClient[IO] & ModelCatalog[IO]) = c.vendor.name
    def tagged(c: Verdict4sClient[IO] & SafetyIdentity[IO]) = c.safetyIdentifier
    val http = Client.fromHttpApp(HttpApp.notFound[IO])
    assertEquals(catalogued(Verdict4sClient.jev[IO](http, key)), "Jev")
    assertEquals(tagged(Verdict4sClient.openAI[IO](http, key)), None)
    assertEquals(
      compileErrors(
        "catalogued(Verdict4sClient.openAI[IO](http, key))"
      ).nonEmpty,
      true
    )
