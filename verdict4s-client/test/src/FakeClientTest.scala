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

import scala.collection.immutable.SortedMap

import cats.effect.IO
import cats.effect.Ref
import com.softinio.verdict4s.algebra.*
import munit.CatsEffectSuite

/** Testing code that *uses* verdict4s, with no HTTP at all.
  *
  * `Client.fromHttpApp` is the right seam when you care about the wire. When
  * you only care that your own logic reacts correctly to an answer, this is
  * lighter: a `Ref` holding canned answers and a couple of ordinary functions.
  * No mocking library is needed for either, which is the point of the client
  * taking its transport rather than building one.
  */
class FakeClientTest extends CatsEffectSuite:

  private def p(d: Double): Probability = Probability.either(d).toOption.get

  /** The kind of thing an application would actually write. */
  final class TriageService(escalateAbove: Double):
    def shouldEscalate(answer: NoulAnswer): Boolean =
      (answer.value.value: Double) >= escalateAbove

  private def cannedEvaluation(noul: Double): Evaluation =
    Evaluation(
      Model.JevLatest,
      SortedMap(QuestionKey.either("q00").toOption.get -> Answer.Noul(p(noul))),
      Usage(10, 2)
    )

  test("application logic can be exercised against a canned evaluation"):
    val ask = Ask(Question.noul("Is this urgent?"))
    val service = TriageService(escalateAbove = 0.8)
    val Right(highAnswers) = ask.answers(cannedEvaluation(0.95)): @unchecked
    val Right(lowAnswers) = ask.answers(cannedEvaluation(0.10)): @unchecked
    assert(service.shouldEscalate(highAnswers.head))
    assert(!service.shouldEscalate(lowAnswers.head))

  test("a recording double captures what the application asked"):
    for
      asked <- Ref[IO].of(List.empty[String])
      record = (state: String) =>
        asked.update(_ :+ state) *>
          IO.pure(Ask(Question.noul("urgent?")).answers(cannedEvaluation(0.9)))
      _ <- record("first ticket")
      _ <- record("second ticket")
      seen <- asked.get
    yield assertEquals(seen, List("first ticket", "second ticket"))
