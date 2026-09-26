# Verdict4s

A Scala 3 client for [TypeSafe AI](https://typesafe.ai)'s **Jev**, a decision
model that answers typed questions about your program state with choices,
scores and calibrated probabilities instead of text.

You describe the decision; your code keeps control of what happens next.

```scala
val (urgent, team, mood) = client.ask(
  Ask(
    Question.noul("Does this convey urgency?"),
    Question.choice[Dept]("Which team should handle this?"),
    Question.score("How frustrated?", Seq("Calm", "Frustrated", "Very angry"))
  ),
  "Help! My payouts have been failing for 3 days."
)
```

`urgent` is a `NoulAnswer`, `team` a `ChoiceAnswer[Dept]`, `mood` a
`ScoreAnswer` — destructured positionally, at exactly the right types, with no
casts and no lookups by string.

Verdict4s cross-builds for the **JVM** and **Scala.js** from the same sources
and at the same version.

## Why the types matter here

The service enforces real limits: a Choice takes 1 to 255 options, a Score
takes 2 to 10 ordered levels, probabilities live in the unit interval. Those
are carried by the types rather than discovered a network round trip later.

```scala
Probability(0.95)   // fine
Probability(1.5)    // does not compile
```

Questions are validated before anything is sent, and failures accumulate, so
a request with three problems reports all three at once rather than one per
attempt.

## Choosing a module

Verdict4s is published to Maven Central for Scala 3 as three layers. Pick the
one that matches how much you want the library to decide for you.

| Module | Use it when |
|---|---|
| `verdict4s` | You want a working client in one line. Brings its own transport. |
| `verdict4s-client` | You already have an http4s `Client[F]`, or want a specific backend. |
| `verdict4s-core` | You want the types and codecs only, with no effect system at all. |

Each layer depends on the one below it, so taking `verdict4s` gives you all
three. On the JVM they need Java 17 or later.

### Mill

```scala
def mvnDeps = Seq(
  mvn"com.softinio::verdict4s::@VERSION@"
)
```

### sbt

```scala
libraryDependencies += "com.softinio" %%% "verdict4s" % "@VERSION@"
```

The `::` (Mill) and `%%%` (sbt) forms resolve the right artifact for whichever
platform you are building, so the same line works for JVM and Scala.js.

## Where to next

- [Getting started](getting-started.md) — your first request, on JVM and Scala.js
- [Typed questions](typed-questions.md) — `Ask`, enum derivation, your own answer types
- [Errors and retries](errors-and-retries.md) — what can fail, and what is retried
- [Bring your own client](client-module.md) — any http4s backend, or an in-memory fake
- [No effect system](core-module.md) — driving the protocol by hand
- [Other effect systems](effect-systems.md) — ZIO, Future, and anything else
- [API reference](api-reference.md) — generated Scaladoc for each module

## A note on dependencies

`verdict4s-core` depends on cats-core, circe and
[Iron](https://github.com/Iltotore/iron). Iron is what carries the API's
numeric and cardinality bounds in the types, and it is a public dependency: it
appears in signatures such as `Probability`, so it comes along with the
library.
