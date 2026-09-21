# verdict4s

[![CI](https://github.com/softinio/verdict4s/actions/workflows/ci.yml/badge.svg)](https://github.com/softinio/verdict4s/actions/workflows/ci.yml)

A Scala 3 client for [TypeSafe AI](https://typesafe.ai)'s **Jev**, a decision
model that answers typed questions about your program state with choices,
scores and calibrated probabilities instead of text.

Cross-built for the **JVM** and **Scala.js** from one source tree.

```scala
enum Dept derives Options:
  case Billing, Technical, Sales

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

## Why the types matter

The service enforces real limits: a Choice takes 1 to 255 options, a Score 2 to
10 ordered levels, probabilities live in the unit interval. Those are carried
by the types rather than discovered a network round trip later.

```scala
Probability(0.95)   // fine
Probability(1.5)    // does not compile
```

Questions are validated before anything is sent, and failures accumulate, so a
request with three problems reports all three at once.

## Install

```scala
// Mill
def mvnDeps = Seq(mvn"com.softinio::verdict4s::<version>")

// sbt
libraryDependencies += "com.softinio" %%% "verdict4s" % "<version>"
```

| Module | Use it when |
|---|---|
| `verdict4s` | You want a working client in one line. Brings its own transport. |
| `verdict4s-client` | You already have an http4s `Client[F]`, or want a specific backend. |
| `verdict4s-core` | You want the types and codecs only, with no effect system at all. |

## Documentation

- [Getting started](https://softinio.github.io/verdict4s/getting-started.html)
- [Typed questions](https://softinio.github.io/verdict4s/typed-questions.html)
- [Errors and retries](https://softinio.github.io/verdict4s/errors-and-retries.html)
- [Other effect systems](https://softinio.github.io/verdict4s/effect-systems.html) — ZIO, Future, and anything without a cats-effect instance
- [API reference](https://softinio.github.io/verdict4s/api/verdict4s/index.html)

## Development

Everything runs inside the Nix devshell. `nix develop --command <cmd>` works
non-interactively; the aliases below assume you are already inside it.

```bash
mill __.test                              # all 12 targets: 3 modules x {jvm,js} x {3.3.8,3.9.0}
mill "__.jvm[3.9.0].test"                 # one platform, one Scala version
mill "verdict4s-core.jvm[3.3.8].test"     # one module

fmt / fmtCheck                            # scalafmt
testAll                                   # mill __.test
testJvm25                                 # JVM tests on Java 25, as CI does
buildDocs / previewDocs                   # Laika site, or serve on :4242
mill verdict4s-examples.compile           # the ZIO / Future / JDK HttpClient examples
```

Integration tests run against the real service and are gated on an API key.
Without one they report as ignored, so the suite stays green offline:

```bash
TYPESAFE_API_KEY=… mill "verdict4s.jvm[3.3.8].test"
```

`mill docs.preview` holds Mill's workspace lock while it runs; stop it before
building anything else.

## Structure

```
verdict4s-core/      sans-IO: models, codecs, Ask, no effect system
verdict4s-client/    a client over a caller-supplied http4s Client[F]
verdict4s/           batteries included: Ember on JVM, Fetch on Scala.js
verdict4s-examples/  interop examples, compiled by CI, never published
docs/                Laika narrative site
```

## License

Apache-2.0. See [LICENSE](LICENSE).
