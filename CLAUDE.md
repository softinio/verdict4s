# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Scala 3 client for typed decision models (TypeSafe AI's Jev), cross-built for
**JVM and Scala.js** from one source tree. Published to Maven Central as three
layered artifacts.

The Jev protocol is implemented across all three layers: models, codecs and the
`Ask` tuple builder in core, an http4s client with retries on top, and a
batteries-included bundle. The suite is green on all twelve targets. Releases
are cut by pushing a `v*` tag (see the README's "Releasing" section); run
`LiveApiIT` before each one, since it is the only check that the wire format
matches the service rather than its documentation.

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
mill "verdict4s-client.jvm[3.3.8].test" -- '*retried*'   # munit glob, matches test names

fmt / fmtCheck                            # scalafmt (devshell aliases)
testAll                                   # mill __.test
testJvm17                                 # JVM tests on Java 17, the floor, as CI does
testJvm25                                 # JVM tests on Java 25, as CI does
buildDocs / previewDocs                   # Laika site -> site/target/docs/site, or serve on :4242
mill verdict4s-examples.compile           # the ZIO / Future / JDK HttpClient interop examples

# against the real service; ignored without a key, so it is safe to run blind
TYPESAFE_API_KEY=… mill "verdict4s.jvm[3.3.8].test.testOnly" com.softinio.verdict4s.LiveApiIT
```

Module path segments are `<module>.<platform>[<scalaVersion>]`. A bare class
name passed to `.test` is *not* a class filter — it silently runs nothing
("0 total"). Use `testOnly` or the `-- '*glob*'` form above.

`docJar`, `publishVersion` and anything downstream need a git/jj repo, because
`mill-git` derives the version from tags. Outside one they fail with
`RepositoryNotFoundException`, which is not a build defect. Untagged, the
version is derived from the commit; tagging `v0.1.0` publishes `0.1.0`.

## Architecture

Three modules, each layered on the one below. The split exists so callers can
take exactly as much as they want:

| Module | Deps | Holds |
|---|---|---|
| `verdict4s-core` | cats-core, circe, Iron | Effect-free. Models, codecs, request building, response parsing, `Ask`. |
| `verdict4s-client` | + http4s-client, fs2, cats-effect-kernel | `Verdict4sClient[F]` over a caller-supplied `Client[F]`. |
| `verdict4s` | + ember (JVM) / http4s-dom (JS) | `Verdict4s.default[F]` — picks a transport for you. |

Three invariants hold this together. Breaking any of them defeats the design:

**`verdict4s-core` stays effect-free.** cats-core, circe, Iron and stdlib only.
No effect type, no HTTP client, and deliberately no fs2 — fs2-core pulls in `cats-effect-kernel`, which would
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

**The JDK floor is 17 (`-release 17`), and 21 is the ceiling.** 17 was chosen
before the first release because lowering a floor later is free and raising one
is a breaking change. Every runtime dependency targets Java 8 bytecode, so
nothing upstream forces it higher; CI's `jdk-compat` job runs the JVM suite on a
real Java 17. The ceiling is the compilers': both Scala 3.3.8 and 3.9.0 reject
`-release` 22 through 25, verified on a genuine JDK 25 toolchain.

**The nixpkgs `mill` wrapper hardcodes `JAVA_HOME` to its own JDK 21** and
exports it shell-wide, so every compile, test and scaladoc run happens on 21
whatever `devshell.toml` lists — `mill-jvm-version: system` does not override it.
`devshell.toml` says `jdk21` to reflect that truthfully. Newer JDKs are selected
through `VERDICT4S_JVM` (e.g. `temurin:25`), which drives Mill's `jvmVersion` and
resolves via coursier. Two JDKs cannot both live in the devshell — nix fails the
build on a file collision.

**`javacOptions` uses `--release`, not `-source`/`-target`.** The latter sets the
bytecode version without restricting the API classpath, so post-17 APIs compile
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

The site is published only from a release, never from `main`: `docs.yml` runs
after a successful `Release`, checks out the tag and deploys to `gh-pages`,
served at `verdict4s.softinio.dev`. The deploy replaces the whole branch, so it
must keep passing `cname:` -- a push without the `CNAME` file makes GitHub drop
the custom domain.

## Traps that cost time here

Each of these compiles or passes somewhere and fails somewhere else, so none of
them show up in a single local run.

**`import io.github.iltotore.iron.*` shadows `cats`.** Iron ships an object
named `cats` in its root package, so `cats.data.NonEmptyChain` resolves into
Iron's module and fails with "macro expansion was stopped". Import the type
directly in any file that imports Iron.

**`constValueTuple[...].toList` fails on 3.3.8 and compiles on 3.9.0.** It
leaks `Tuple.Union` through the Mirror proxy. Both derivations use
`.productIterator.toList` instead; the LTS leg of CI is what catches this.

**`Double.toString` differs between JVM and Node** -- `"1.0"` against `"1"`.
Core tests are shared across both platforms, so golden-JSON assertions compare
`Json` values structurally. The single exact-string assertion, which proves key
ordering, is deliberately free of floating point.

**`Order.by(identity[String])` on an opaque type recurses forever.** Inside the
defining file the opaque type *is* its underlying type, so the instance
resolves to itself. `Model` uses an explicit comparison, and a test pins it.

**`java.util.Formatter` is only partly implemented on Scala.js**, so
`f"q$i%02d"` does not work. `Ask.defaultKey` pads by hand.

**Iron's API is not what older examples show.** It is `RefinedType`, not
`RefinedTypeOps`; the opaque type it defines is `.T`, unwrapped with `.value`;
and there is no `MinLength`/`MaxLength` -- cardinality goes through
`Length[GreaterEqual[n] & LessEqual[m]]`.

**A tuple bind in a `for` over `IO` needs `withFilter`**, which `IO` lacks. Use
a pattern `val` inside the comprehension instead.

**`mill docs.preview` holds Mill's workspace lock** for as long as it runs,
because it serves inside a Mill task, so nothing else can build meanwhile. Set
`MILL_OUTPUT_DIR` to work around it, or run the scala-cli script directly.

**Laika validates internal links**, so the `/api/` reference -- generated by
`docJar`, not by Laika -- is linked absolutely. Laika's syntax highlighting is
an opt-in bundle, and in 1.x it lives at `laika.config.SyntaxHighlighting`.

**Scaladoc `-external-mappings` patterns match a symbol's package path, not the
jar.** `.*cats/kernel/.*` works; `.*/cats-kernel_3-.*` silently matches
nothing and every type from that library renders as unresolved plain text.
`.*scala.*` and `.*java.*` only appeared to work by jar name because the package
paths contain those words too. First match wins, so order specific patterns
first. Verify a mapping by counting links in the output, and check the target
URLs return 200: javadoc.io has not unpacked every version (circe, http4s and
cats-effect link to their projects' own Scala 2 sites for that reason). Two
external links are known dead because upstream publishes no page for them:
Iron's `Refined` and cats-effect's `Temporal` alias.

**Build Scaladoc with 3.3.8, never 3.9.0.** Scaladoc 3.9.0 stopped emitting
the `<script>` tag for jQuery, but its `ux.js` still navigates by intercepting
every same-origin link click (`preventDefault()`) and fetching the page with
`$.get`. With `$` undefined that throws after the click is cancelled, so *every
link* in the reference is dead when served over HTTP -- yet the HTML is
correct, every URL returns 200, and it works from `file://`, where `ux.js`
bails out early. The tell is a missing `code.jquery.com` script tag. Both
`unidoc` and CI's `docJar` step use 3.3.8; 3.9.0 also produced two spurious
option warnings that 3.3.8 does not.

**The Scaladoc snippet compiler is off on purpose.** `-snippet-compiler:compile`
intermittently crashed the compiler (`class String has non-class parent`) under
a long-lived Mill daemon while passing in fresh runs, and it was checking a
single snippet. Doc examples are guarded by `DocExamplesTest` instead.

**The site's API reference is `unidoc`, not the per-module `docJar`s.** Each
module's own Scaladoc cannot link to types in the others, so `unidoc` runs
Scaladoc once over all three modules' TASTy. The per-module `docJar`s remain
because Maven Central requires a javadoc jar per artifact; both use
`docOptions` in `build.mill` so they cannot drift.

**`GET /v1/models` wraps its array in a `models` field.** The HTTP reference
does not show the response shape, and the JS SDK's `list(): APIPromise<ModelCard[]>`
describes what that SDK returns *after* unwrapping, so modelling it as a bare
array looks right from the documentation and fails against the service. The
in-memory fake agreed with the mistake until `LiveApiIT` ran. When a fake and
the docs are the only evidence, neither is evidence.

**Docs pages write versions as `@VERSION@`, not Laika's `${...}`.** Every
version sits in a dependency line inside a fenced Scala block, and the
highlighter claims `${...}` in a string literal as Scala interpolation before
Laika resolves it, so a Laika variable reaches the page verbatim.
`LaikaBuild.scala` copies `docs/` to `site/target/docs/src` with the token
replaced and renders that; `mill docs.build` supplies the version.

**Every `.scala` and `.mill` file carries the Apache header**, dated by year, and
CI fails without it. Non-Scala files deliberately have none -- see the licence
commit for why. Add the header to any new source file.

## Integrations considered and dropped

The `effect-systems` docs page and `verdict4s-examples` deliberately cover only
ZIO, Scala `Future` and a plain JDK `HttpClient`. Three others were built or
investigated and removed. Do not re-add them without re-checking these facts.

**Kyo.** Kyo 1.0.0-RC6 is built with Scala 3.8.4, so the 3.3 LTS cannot read
its TASTy, and it ships Java 25 bytecode (class file 69) that the JDK 21
toolchain cannot load. The integration itself would be Strategy B via
`kyo.Cats.get` from `kyo-cats`, and it probably works for a user on Java 25 and
Scala 3.8+. It cannot be compiled and verified here, and a docs section calling
it "unusable" misstated that, so it was removed. Note `kyo-cats` trails
`kyo-core` by a release candidate.

**Finagle.** `finagle-http_2.13` does compile from Scala 3 (Scala 3 consumes
2.13 artifacts), and a working example existed. It was removed because it put a
Scala 2.13 artifact into an otherwise pure Scala 3 build, Finagle had not
released since 24.2.0 (May 2024), and it showed nothing an effect-free example does
not. Finatra was never viable: it is 2.13-only, and a 2.13
application cannot depend on verdict4s.

**Twitter `Future`.** `util-core_3` exists, and an example used it for the
no-effect-system strategy. Removed for the same staleness as Finagle (same 24.2.0 release
line) and because it took an abstract `post` function, so it never showed a
real transport. The JDK `HttpClient` example replaced it: no dependency, never
stale, and concrete from render to parse. `catbird-effect` has no Scala 3 build,
so the effect-free core is the only route for Twitter `Future` users anyway.

**Maven Central's search API is unreliable for "latest version".** It
reported Iron 3.0.2 (actual: 3.3.2) and Kyo 3.0.7 (actual: 1.0.0-RC6). Read
`https://repo1.maven.org/maven2/<group>/<artifact>/maven-metadata.xml` instead,
and check the POM's `scala3-library` version before assuming a dependency loads
on the LTS.
