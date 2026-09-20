# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Scala 3 client for typed decision models (TypeSafe AI's Jev), cross-built for
**JVM and Scala.js** from one source tree. Published to Maven Central as three
layered artifacts.

The library API is still a placeholder: `Verdict4sError` and a `health` probe
stand in for the real Jev protocol. The build, CI and publishing pipeline around
them is finished and green. See `TODO.md`.

## Commands

Everything runs inside the Nix devshell. `nix develop --command <cmd>` works
non-interactively; the aliases below assume you are already inside it.

```bash
mill __.test                              # all 12 targets: 3 modules x {jvm,js} x {3.3.8,3.9.0}
mill "__.jvm[3.9.0].test"                 # one platform, one Scala version, all modules
mill "verdict4s-core.jvm[3.3.8].test"     # one module
mill "__.js[_].test"                      # all Scala versions of a platform ([_] is the cross wildcard)

# single test
mill "verdict4s-core.jvm[3.3.8].test.testOnly" com.softinio.verdict4s.Verdict4sErrorTest
mill "verdict4s-client.jvm[3.3.8].test" -- '*health is true*'   # munit glob, matches test names

fmt / fmtCheck                            # scalafmt (devshell aliases)
testAll                                   # mill __.test
testJvm25                                 # JVM tests on Java 25, as CI does
buildDocs / previewDocs                   # Laika site -> site/target/docs/site, or serve on :4242
```

Module path segments are `<module>.<platform>[<scalaVersion>]`. A bare class
name passed to `.test` is *not* a class filter — it silently runs nothing
("0 total"). Use `testOnly` or the `-- '*glob*'` form above.

`docJar`, `publishVersion` and anything downstream need a git/jj repo, because
`mill-git` derives the version from tags. There is none yet, so those tasks fail
locally with `RepositoryNotFoundException` — not a build defect.

## Architecture

Three modules, each layered on the one below. The split exists so callers can
take exactly as much as they want:

| Module | Deps | Holds |
|---|---|---|
| `verdict4s-core` | cats-core, circe | Sans-IO. Models, codecs, request building, response parsing. |
| `verdict4s-client` | + http4s-client, fs2, cats-effect-kernel | `Verdict4sClient[F]` over a caller-supplied `Client[F]`. |
| `verdict4s` | + ember (JVM) / http4s-dom (JS) | `Verdict4s.default[F]` — picks a transport for you. |

Three invariants hold this together. Breaking any of them defeats the design:

**`verdict4s-core` stays effect-free.** No effect type, no HTTP client, and
deliberately no fs2 — fs2-core pulls in `cats-effect-kernel`, which would
reintroduce the dependency this layer exists to avoid. Streaming belongs in
`verdict4s-client`. The point is that someone with no effect system, on any
platform, can still drive the protocol.

**`verdict4s-client` takes a `Client[F]` rather than building one.** That single
decision buys every backend and every platform without the library owning any of
them, and it is why the tests need no network: `Client.fromHttpApp` serves routes
in memory, so the suite runs identically on JVM and Node.

**The bundle's transport is per-platform, and JS is not Ember.** Ember needs raw
TCP sockets, which browsers do not expose, so `src-js/Transport.scala` uses the
Fetch API (works in browsers and Node 18+) while `src-jvm/Transport.scala` uses
Ember. `Network[F]` is derived inside the JVM transport rather than demanded
from callers, so both platforms share one `Verdict4s.default` signature.

### Source layout

`PlatformScalaModule` drops the trailing `jvm`/`js` segment, so both platforms of
a module share one directory: `src/` is common, `src-jvm/` and `src-js/` are
platform-specific. Tests live in `<module>/test/src` and are shared across
platforms.

## Build constraints

These are non-obvious and were each established by testing. Do not "fix" them
back.

**`-release 21` is a ceiling, not a preference.** Both Scala 3.3.8 and 3.9.0
reject 22 through 25, verified on a genuine JDK 25 toolchain. Java 25 bytecode is
not reachable from any current Scala 3 compiler. 17 also works if you ever want a
lower floor.

**The nixpkgs `mill` wrapper hardcodes `JAVA_HOME` to its own JDK 21** and
exports it shell-wide, so every compile, test and scaladoc run happens on 21
whatever `devshell.toml` lists — `mill-jvm-version: system` does not override it.
`devshell.toml` says `jdk21` to reflect that truthfully. Newer JDKs are selected
through `VERDICT4S_JVM` (e.g. `temurin:25`), which drives Mill's `jvmVersion` and
resolves via coursier. Two JDKs cannot both live in the devshell — nix fails the
build on a file collision.

**`javacOptions` uses `--release`, not `-source`/`-target`.** The latter sets the
bytecode version without restricting the API classpath, so post-21 APIs compile
clean and then fail at runtime.

**`.mill-version` tracks what nixpkgs ships** (currently 1.1.8), not the latest
Mill release. A mismatch makes the wrapper warn on every invocation.

**`-Xkind-projector` is gated on "not 3.3.x"**, because the LTS only has
`-Ykind-projector`. Gating on a specific newer version silently drops the flag
the next time that version is bumped.

## Publishing

`artifactScalaVersion` collapses every Scala 3.x to the `_3` suffix, so both
cross-versions produce the *same* coordinate. Releases are therefore pinned:

```
mill mill.scalalib.SonatypeCentralPublishModule/ --publishArtifacts "__[3.3.8].publishArtifacts"
```

Publishing unpinned races the cross-versions to one address and the winner varies
between releases. TASTy is only backward compatible besides, so 3.9.0-built
artifacts would lock out every 3.3 LTS consumer. The 3.9.0 build is compile
verification, not a publish target. Six coordinates result: each module in `_3`
and `_sjs1_3`.

`versionScheme` is set to `EarlySemVer` so the POM carries
`info.versionScheme`; without it downstream sbt users get noisier eviction
warnings than they do against cats, fs2, http4s or circe, which all set it.

Versions come from git tags via `mill-git` — tagging `v0.1.0` publishes `0.1.0`.

## Known stale content

`README.md` is still `scala-mill-library-starter` boilerplate from the template:
the title, intro, Quick Start and the "JDK 25 / Java 25 bytecode" prerequisite
line are all wrong for this project. The module, command and structure sections
were updated and are accurate. `docs/index.md` is current.
