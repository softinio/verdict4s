<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/img/logo-wordmark-dark.svg">
    <img alt="verdict4s" src="docs/img/logo-wordmark.svg" width="440">
  </picture>
</p>

[![CI](https://github.com/softinio/verdict4s/actions/workflows/ci.yml/badge.svg)](https://github.com/softinio/verdict4s/actions/workflows/ci.yml)
[![Release](https://github.com/softinio/verdict4s/actions/workflows/release.yml/badge.svg)](https://github.com/softinio/verdict4s/actions/workflows/release.yml)
[![Documentation](https://github.com/softinio/verdict4s/actions/workflows/docs.yml/badge.svg)](https://verdict4s.softinio.dev)
[![Maven Central](https://img.shields.io/maven-central/v/com.softinio/verdict4s_3)](https://central.sonatype.com/artifact/com.softinio/verdict4s_3)

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

Requires Java 17 or later on the JVM. The latest `<version>` is on the Maven
Central badge above.

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

- [Getting started](https://verdict4s.softinio.dev/getting-started.html)
- [Typed questions](https://verdict4s.softinio.dev/typed-questions.html)
- [Errors and retries](https://verdict4s.softinio.dev/errors-and-retries.html)
- [Bring your own client](https://verdict4s.softinio.dev/client-module.html) — any http4s backend, or an in-memory fake
- [No effect system](https://verdict4s.softinio.dev/core-module.html) — driving the protocol by hand
- [Other effect systems](https://verdict4s.softinio.dev/effect-systems.html) — ZIO, Future, and anything without a cats-effect instance
- [API reference](https://verdict4s.softinio.dev/api-reference.html) — all three modules in one Scaladoc site

## Development

Everything runs inside the Nix devshell. `nix develop --command <cmd>` works
non-interactively; the aliases below assume you are already inside it.

```bash
mill __.test                              # all 12 targets: 3 modules x {jvm,js} x {3.3.8,3.9.0}
mill "__.jvm[3.9.0].test"                 # one platform, one Scala version
mill "verdict4s-core.jvm[3.3.8].test"     # one module

fmt / fmtCheck                            # scalafmt
testAll                                   # mill __.test
testJvm17                                 # JVM tests on Java 17, the floor, as CI does
testJvm25                                 # JVM tests on Java 25, as CI does
buildDocs / previewDocs                   # Laika site, or serve on :4242
mill verdict4s-examples.compile           # the ZIO / Future / JDK HttpClient examples
```

Integration tests run against the real service and are gated on an API key.
Without one they report as ignored, so the suite stays green offline:

```bash
TYPESAFE_API_KEY=… mill "verdict4s.jvm[3.3.8].test.testOnly" com.softinio.verdict4s.LiveApiIT
```

`mill docs.preview` holds Mill's workspace lock while it runs; stop it before
building anything else.

### Releasing

Versions come from git tags. Pushing a `v*` tag from `main` runs the release:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

1. **Release** publishes the six artifacts (each module for the JVM and
   Scala.js, all built by Scala 3.3.8) to Maven Central and creates a GitHub
   release with generated notes.
2. **Documentation** then builds the site from the tag, with that version in
   its dependency lines, and deploys it to <https://verdict4s.softinio.dev>.
   It can be re-run by hand for a tag from the Actions tab.

Run the integration tests above before tagging; they are the only check
against the live service.

## Structure

```
verdict4s-core/      effect-free: models, codecs, Ask, no HTTP client
verdict4s-client/    a client over a caller-supplied http4s Client[F]
verdict4s/           batteries included: Ember on JVM, Fetch on Scala.js
verdict4s-examples/  interop examples, compiled by CI, never published
docs/                Laika narrative site
```

## Contributing

**GitHub Issues** are disabled to encourage direct community contribution. When you encounter bugs or documentation issues, please contribute fixes through Pull Requests instead.

**How to contribute:** [Open a PR](https://github.com/softinio/verdict4s/pulls) with your solution, draft changes, or a test reproducing the issue. We'll collaborate from there to refine and merge improvements.

This approach creates faster fixes while building a stronger, community-driven project where everyone benefits from shared contributions.

Everyone taking part is expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## License

Apache-2.0. See [LICENSE](LICENSE).
