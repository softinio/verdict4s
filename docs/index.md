# Verdict4s

A Scala client for typed decision models, which answer questions about your
program state with typed choices, scores, and probabilities instead of text.
Supports TypeSafe AI's Jev.

Verdict4s cross-builds for the **JVM** and **Scala.js**, from the same sources
and at the same version.

## Choosing a module

Verdict4s is published to Maven Central for Scala 3 as three layers. Pick the
one that matches how much you want the library to decide for you.

| Module | Use it when |
|---|---|
| `verdict4s` | You want a working client in one line. Brings its own transport. |
| `verdict4s-client` | You already have an http4s `Client[F]`, or want a specific backend. |
| `verdict4s-core` | You want the types and codecs only, with no effect system at all. |

Each layer depends on the one below it, so taking `verdict4s` gives you all three.

### Mill

```scala
def mvnDeps = Seq(
  mvn"com.softinio::verdict4s::<version>"
)
```

### sbt

```scala
libraryDependencies += "com.softinio" %%% "verdict4s" % "<version>"
```

The `::` (Mill) and `%%%` (sbt) forms resolve the right artifact for whichever
platform you are building, so the same line works for JVM and Scala.js.

## Usage

### Batteries included

`Verdict4s.default` picks the transport for the platform you are on — Ember on
the JVM, the Fetch API on Scala.js — so the same code compiles and runs on both.

```scala
import cats.effect.{IO, IOApp}
import com.softinio.verdict4s.Verdict4s
import org.http4s.implicits.*

object Main extends IOApp.Simple:
  def run: IO[Unit] =
    Verdict4s.default[IO](uri"https://your-service.example/api").use { client =>
      client.health.flatMap(IO.println)
    }
```

The `Resource` owns the underlying connection pool, so allocate it once for the
lifetime of your application rather than once per call.

### Bring your own client

Depend on `verdict4s-client` and construct `Verdict4sClient` directly. It takes
any http4s `Client[F]`, which means any backend on any platform: Ember, Fetch,
Blaze, Netty, the JDK client, or an in-memory fake.

```scala
import cats.effect.IO
import com.softinio.verdict4s.Verdict4sClient
import org.http4s.client.Client
import org.http4s.implicits.*

def myClient: Client[IO] = ???

val verdict = Verdict4sClient[IO](myClient, uri"https://your-service.example/api")
```

This is also how you test without a network. `Client.fromHttpApp` serves routes
in memory, so no socket is opened and no port is bound:

```scala
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*

val routes = HttpRoutes.of[IO] { case GET -> Root / "health" => Ok() }.orNotFound
val fake   = Verdict4sClient[IO](Client.fromHttpApp(routes), uri"https://example.invalid")
```

### No effect system

`verdict4s-core` carries only cats-core and circe. It has no effect type, no
HTTP client and no fs2, so you can drive the protocol from whatever you already
use — including nothing at all.

```scala
import com.softinio.verdict4s.Verdict4sError

val describe: Verdict4sError => String =
  case Verdict4sError.Api(status, details) => s"service said $status: $details"
  case Verdict4sError.Decoding(details)    => s"bad response: $details"
  case Verdict4sError.Transport(details, _) => s"could not reach service: $details"
```

## Platform notes

On Scala.js the default transport is the Fetch API, which works both in the
browser and on Node 18+. Ember is deliberately not used there: it needs raw TCP
sockets, which browsers do not expose.

## Documentation

This site is built with [Laika](https://typelevel.org/Laika/) from the
Markdown sources in the `docs/` directory. Add more pages by dropping
additional `.md` files next to this one.

- `mill docs.build` — build the site into `site/target/docs/site`
- `mill docs.preview` — build and serve the site at `http://localhost:4242`
