# scala-mill-library-starter

A Nix flake template for bootstrapping Scala library projects using the [Mill](https://mill-build.org) build tool. Includes:

- Cross-Scala 3 build (3.3.8 LTS + 3.9.0 latest)
- Cross-platform: JVM and Scala.js from the same sources
- Three-layer structure: sans-IO core, tagless-final client, batteries-included bundle
- Git-tag-based automatic versioning via [mill-git](https://github.com/jodersky/mill-git)
- Maven Central publishing via Sonatype
- Laika documentation site (Helium theme) with build/preview commands and
  automatic publishing to GitHub Pages
- Scalafmt formatting
- GitHub Actions CI and release workflows
- Nix devshell with all required tools

## Quick Start

```bash
mkdir verdict4s && cd verdict4s
nix flake init --template github:softinio/templates#scala-mill-library-starter
```

## Setup

After initializing the template, customize the placeholder names using one of these methods:

### Option A: Shell script

```bash
bash setup.sh
```

### Option B: Claude Code

Open the project in Claude Code and run:

```
/project:setup
```

Both methods will prompt you for your library name, Maven organization, GitHub handle, developer info, and description, then rename all placeholders accordingly.

## Prerequisites

- [Nix](https://nixos.org/download) with flakes enabled
- JDK 25 (provided by the devshell); published artifacts target Java 25 bytecode

## Development

Enter the Nix devshell:

```bash
nix develop
```

### Common Commands

| Command | Description |
|---|---|
| `mill __.compile` | Compile all modules |
| `mill __.test` | Run all tests |
| `mill "__.jvm[3.9.0].test"` | Test every module on the JVM with Scala 3.9.0 |
| `mill "__.js[3.3.8].test"` | Test every module on Scala.js with Scala 3.3.8 |
| `mill "verdict4s-core.jvm[3.9.0].test"` | Test one module, one platform, one version |
| `fmt` | Format all sources with Scalafmt |
| `fmtCheck` | Check formatting without modifying |
| `mill "__.jvm[3.9.0].docJar"` | Generate Scaladoc |
| `mill docs.build` (or `buildDocs`) | Build the Laika documentation site |
| `mill docs.preview` (or `previewDocs`) | Serve the docs at http://localhost:4242 |
| `mill __.publishLocal` | Publish to local Ivy repository |

## Project Structure

```
.
├── build.mill                        # Mill build definition
├── devshell.toml                     # Nix devshell configuration
├── flake.nix                         # Nix flake
├── .mill-version                     # Mill version pin
├── .scalafmt.conf                    # Scalafmt configuration
├── verdict4s-core/                   # Sans-IO layer: types and codecs
│   ├── src/
│   │   └── Verdict4sError.scala
│   └── test/src/
│       └── Verdict4sErrorTest.scala
├── verdict4s-client/                 # Tagless-final client over http4s Client[F]
│   ├── src/
│   │   └── Verdict4sClient.scala
│   └── test/src/
│       └── Verdict4sClientTest.scala
├── verdict4s/                        # Batteries included: default transport
│   ├── src/                          #   shared across platforms
│   │   └── Verdict4s.scala
│   ├── src-jvm/                      #   JVM only: Ember
│   │   └── Transport.scala
│   ├── src-js/                       #   Scala.js only: Fetch
│   │   └── Transport.scala
│   └── test/src/
│       └── Verdict4sTest.scala
├── docs/                             # Laika documentation sources (Markdown)
│   └── index.md
├── scripts/                          # scala-cli scripts running Laika
│   ├── LaikaBuild.scala
│   └── LaikaPreview.scala
└── .github/workflows/
    ├── ci.yml                        # CI: test + format check + docs site
    └── release.yml                   # Release: publish to Maven Central
```

Three Mill modules, each cross-built for Scala 3.3.8 and 3.9.0 and for both the
JVM and Scala.js — twelve build targets, six published coordinates:

- **`verdict4s-core`** — sans-IO. Models, JSON codecs, request building and
  response parsing. Depends on cats-core and circe only: no effect type, no
  HTTP client, not even fs2, so it is usable from any effect system or none.
- **`verdict4s-client`** — tagless-final `Verdict4sClient[F]` over an http4s
  `Client[F]` the caller supplies. Backend-agnostic, so it works with Ember,
  Fetch, Blaze, Netty, the JDK client, or an in-memory fake.
- **`verdict4s`** — batteries included. Adds a default transport per platform:
  Ember on the JVM, the Fetch API on Scala.js.

`PlatformScalaModule` lets both platforms of a module share one directory:
`src/` is common, `src-jvm/` and `src-js/` hold platform-specific code.

All modules extend `GitVersionedPublishModule`, so the version is automatically derived from git tags (e.g. tagging `v0.1.0` publishes version `0.1.0`).

### Why releases publish from the LTS only

Every Scala 3.x is binary compatible, so all cross-versions share the `_3`
coordinate. TASTy, however, is only backward compatible: artifacts built by
3.9.0 cannot be read by a 3.3 compiler, while 3.3.8-built artifacts are readable
by both. The release workflow therefore pins publishing to `__[3.3.8]`, and the
3.9.0 cross-build in CI serves as compile verification rather than a publish
target.

## Documentation Site

The `docs/` directory holds the Markdown sources for a documentation site
rendered by [Laika](https://typelevel.org/Laika/) with the Helium theme.

| Command | Description |
|---|---|
| `mill docs.build` | Build the site into `site/target/docs/site` |
| `mill docs.preview` | Build and serve the site at http://localhost:4242 |

Inside the devshell the `buildDocs` and `previewDocs` aliases wrap these
commands. Both shell out to `scala-cli` (provided by the devshell) to run the
scripts in `scripts/`; customize the Helium theme (title, nav links, footer)
there. The CI workflow builds the site on every push and publishes it to the
`gh-pages` branch (GitHub Pages) on pushes to `main`.

## Publishing to Maven Central

Publishing uses [Sonatype Central](https://central.sonatype.com). You need a Sonatype account and a GPG key.

### Required GitHub Secrets

| Secret | Description |
|---|---|
| `MILL_PGP_PASSPHRASE` | GPG key passphrase |
| `MILL_PGP_SECRET_BASE64` | Base64-encoded GPG private key |
| `MILL_SONATYPE_PASSWORD` | Sonatype Central password |
| `MILL_SONATYPE_USERNAME` | Sonatype Central username |

### Publishing a Release

Push a git tag prefixed with `v`:

```bash
git tag v0.1.0
git push origin v0.1.0
```

The release workflow will publish to Maven Central and create a GitHub release with auto-generated notes.

## Customization

After setup, customize `build.mill` to:

- Add your library's actual dependencies in `mvnDeps`
- Add or remove Scala versions in `scalaVersions`, or platforms by adding a
  `ScalaNativeModule` alongside the existing `jvm` and `js` objects
- Update `pomSettings` with your project details
