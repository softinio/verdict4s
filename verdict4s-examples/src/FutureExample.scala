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

package com.softinio.verdict4s.examples

import scala.concurrent.Future

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.softinio.verdict4s.*
import com.softinio.verdict4s.algebra.*

/** **Strategy B — run `IO`, convert at the boundary.**
  *
  * There is no Strategy A for `scala.concurrent.Future`, and that is a property
  * of `Future` rather than a gap in this library: `Future` is eager and
  * uncancellable, so no lawful `Async[Future]` exists and cats-effect
  * deliberately does not ship one.
  *
  * The practical answer is to keep `IO` inside and hand a `Future` to the
  * caller. If you would rather not depend on cats-effect at all, see
  * [[JdkHttpClientExample]], which uses the effect-free core instead.
  */
object FutureExample:

  enum Dept derives Options:
    case Billing, Technical, Sales

  /** A `Future`-shaped API over an `IO`-shaped client.
    *
    * Allocate the client once for the lifetime of the application: the
    * `Resource` owns the connection pool, so calling this per request would
    * build and tear one down every time.
    */
  final class TriageService(apiKey: ApiKey)(using runtime: IORuntime):

    private val questions = Ask(
      Question.noul("Does this convey urgency?"),
      Question.choice[Dept]("Which team should handle this?")
    )

    def triage(ticket: String): Future[(Boolean, Dept)] =
      Verdict4s
        .default[IO](apiKey)
        .use(_.ask(questions, ticket))
        .map((urgent, dept) => (urgent.isTrue(), dept.choice))
        .unsafeToFuture()
