# Getting started

## Add the dependency

```scala
def mvnDeps = Seq(mvn"com.softinio::verdict4s::@VERSION@")
```

## Get an API key

Every request carries a key from the vendor you use: `TYPESAFE_API_KEY` for
Jev, `OPENAI_API_KEY` for OpenAI Decisions. Wrap it once, at the edge of your
application:

```scala
import com.softinio.verdict4s.algebra.ApiKey

val apiKey = ApiKey.fromString(sys.env("TYPESAFE_API_KEY"))
```

`ApiKey.fromString` returns a `ValidatedNec`, so a blank key is caught where
you read it rather than on your first request. The key never appears in a
`toString`, a `Show`, or a rendered error — it prints as `ApiKey(***)`, and
http4s redacts the `Authorization` header too.

On the JVM you can skip the boilerplate:

```scala
Verdict4sEnv.default[IO].use { client => ??? }
```

This picks the vendor from `VERDICT4S_VENDOR` (`jev`, the default, or
`openai`). It then reads `TYPESAFE_API_KEY`, `TYPESAFE_BASE_URL` and
`TYPESAFE_DEFAULT_MODEL` for Jev, or `OPENAI_API_KEY` and `OPENAI_BASE_URL` for
OpenAI, matching each vendor's official SDKs. `Verdict4sEnv.jev` and
`Verdict4sEnv.openAI` pin the vendor. The object is JVM-only, because Scala.js
has no environment to read.

## Your first request

```scala
import cats.effect.{IO, IOApp}
import com.softinio.verdict4s.*
import com.softinio.verdict4s.algebra.*

object Main extends IOApp.Simple:

  enum Dept derives Options:
    case Billing, Technical, Sales

  def run: IO[Unit] =
    val key = ApiKey.fromString(sys.env("TYPESAFE_API_KEY")).toOption.get

    Verdict4s.jev[IO](key).use { client =>
      client
        .ask(
          Ask(
            Question.noul("Does this convey urgency?"),
            Question.choice[Dept]("Which team should handle this?")
          ),
          "Help! My payouts have been failing for 3 days."
        )
        .flatMap { (urgent, team) =>
          IO.println(s"urgent=${urgent.isTrue()} team=${team.choice}")
        }
    }
```

For OpenAI Decisions, use `Verdict4s.openAI[IO](key)` with an OpenAI key.
Nothing else changes.

The `Resource` owns the connection pool, so allocate it once for the lifetime
of your application rather than once per call.

## The same code on Scala.js

There is nothing to change. `Verdict4s.jev` and `Verdict4s.openAI` have one signature on both
platforms and picks the transport for you — Ember on the JVM, the Fetch API on
Scala.js, which works in the browser and on Node 18+.

Ember is deliberately not used on Scala.js: it needs raw TCP sockets, which a
browser does not expose.

One caveat for browsers specifically: a key shipped to a browser is a key you
have given away. Put verdict4s behind your own endpoint rather than calling the
service directly from a page.

## Batch your questions

Asking three questions in one request is markedly cheaper and faster than three
requests, and it is the shape both services are designed around. Each question
keeps its own answer type.

Speculative questions are cheap: ask something you *might* need and let your
code decide whether to read it.

## Reading confidence

Choice and Score answers carry a `confidence`, derived from the whole
probability distribution. It is a second axis: the answer tells you *what*, and
confidence tells you *whether to act*.

```scala
if (team.confidence.value: Double) >= 0.8 then route(team.choice)
else sendToHumanReview()
```
