package com.softinio.verdict4s.examples

import com.softinio.verdict4s.*
import com.softinio.verdict4s.algebra.*
import zio.Task
import zio.interop.catz.*

/** **Strategy A — cats-effect interop at the type level.**
  *
  * `zio-interop-cats` supplies a lawful `Async[zio.Task]`, so
  * [[com.softinio.verdict4s.Verdict4sClient]] works with `Task` directly and
  * nothing needs bridging at the edges. This is the best of the three
  * integrations: ZIO users get the whole typed API, retries included, with the
  * effect type they already use.
  */
object ZioExample:

  enum Dept derives Options:
    case Billing, Technical, Sales

  /** Ask three questions in one request, answered in `Task`. */
  def triage(apiKey: ApiKey, ticket: String): Task[String] =
    Verdict4s
      .default[Task](apiKey)
      .use: client =>
        client
          .ask(
            Ask(
              Question.noul("Does this convey urgency?"),
              Question.choice[Dept]("Which team should handle this?"),
              Question.score("How frustrated?", Seq("Calm", "Cross", "Livid"))
            ),
            ticket
          )
          .map: (urgent, dept, mood) =>
            // Exactly typed, positionally: no casts, no lookups by string.
            val u: NoulAnswer = urgent
            val d: ChoiceAnswer[Dept] = dept
            val m: ScoreAnswer = mood
            s"${d.choice} (urgent=${u.isTrue()}, mood=${m.levelDescription.getOrElse("?")})"
