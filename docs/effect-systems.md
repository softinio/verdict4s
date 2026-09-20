# Other effect systems

Verdict4s is built on cats-effect, but you do not have to be. There are three
ways in, and which one you use depends on what your effect type can offer.

Every example on this page lives in the `verdict4s-examples` module and is
compiled by CI, so none of it can quietly rot.

| Framework | Scala 3 support | Strategy |
|---|---|---|
| [ZIO](#zio) | `zio` 2.1.19, `zio-interop-cats` 23.1.0.5 | **A** — direct |
| [Scala `Future`](#scala-future) | stdlib | **B** — convert at the edge |
| [Twitter `Future`](#twitter-future) | `util-core_3` 24.2.0 | **C** — sans-IO |
| [Finagle](#finagle) | 2.13 artifacts, usable from Scala 3 | **C** — sans-IO |
| [Kyo](#kyo) | not currently usable here | — |

## Strategy A — direct

Where a lawful `Async` instance exists, `Verdict4sClient` works with your
effect type directly and nothing needs bridging.

### ZIO

`zio-interop-cats` supplies `Async[zio.Task]`:

```scala
import zio.Task
import zio.interop.catz.*

def triage(apiKey: ApiKey, ticket: String): Task[String] =
  Verdict4s.default[Task](apiKey).use { client =>
    client
      .ask(Ask(urgency, team, frustration), ticket)
      .map { (urgent, dept, mood) => s"${dept.choice} (urgent=${urgent.isTrue()})" }
  }
```

ZIO users get the whole typed API, retries included, in the effect type they
already use. This is the best of the three integrations.

## Strategy B — convert at the boundary

Run `IO` inside and hand your own type to the caller.

### Scala `Future`

There is no Strategy A for `scala.concurrent.Future`, and that is a property of
`Future` rather than a gap in this library: `Future` is eager and
uncancellable, so no lawful `Async[Future]` exists, and cats-effect
deliberately does not ship one.

```scala
final class TriageService(apiKey: ApiKey)(using IORuntime):
  def triage(ticket: String): Future[(Boolean, Dept)] =
    Verdict4s
      .default[IO](apiKey)
      .use(_.ask(questions, ticket))
      .map((urgent, dept) => (urgent.isTrue(), dept.choice))
      .unsafeToFuture()
```

Allocate the client once for the lifetime of the application; the example above
is shortened for clarity, and building the `Resource` per request would create
and tear down a connection pool every time.

If you would rather not depend on cats-effect at all, use Strategy C instead.

## Strategy C — sans-IO

`verdict4s-core` renders a request to JSON and parses a reply back into typed
answers, with no effect system anywhere. You move the bytes.

### Twitter `Future`

`util-core` publishes for Scala 3, but `catbird-effect` does not, so there is
no cats-effect instance to bridge through. Sans-IO is the route:

```scala
def triage(apiKey: ApiKey, ticket: String, post: Post): Future[(Boolean, Dept)] =
  SystemOne.renderAsk(questions, ticket) match
    case Left(invalid) => Future.exception(invalid)
    case Right(body) =>
      post(SystemOne.evaluateUrl, SystemOne.headers(apiKey), body).flatMap {
        (status, responseBody) =>
          if status >= 400 then Future.exception(SystemOne.parseError(status, responseBody))
          else SystemOne.parseAsk(questions, responseBody) match
            case Left(error)           => Future.exception(error)
            case Right((urgent, dept)) => Future.value((urgent.isTrue(), dept.choice))
      }
```

### Finagle

Finagle publishes for Scala 2.13 only, but Scala 3 can consume Scala 2.13
artifacts, so a Scala 3 service can use it normally. Spell the suffix out,
since the usual `::` would look for a `_3` build that does not exist:

```scala
mvn"com.twitter:finagle-http_2.13:24.2.0"
```

```scala
val service = Http.client.withTls("api.typesafe.ai").newService("api.typesafe.ai:443")

def triage(apiKey: ApiKey, ticket: String): Future[(Boolean, Dept)] =
  SystemOne.renderAsk(questions, ticket) match
    case Left(invalid) => Future.exception(invalid)
    case Right(body)   => service(request(apiKey, body)).flatMap(read)
```

**Finatra** is a different matter. It is also 2.13-only, and a Scala 2.13
application cannot depend on verdict4s, which is Scala 3-only. The route there
is a Scala 3 module that owns the verdict4s calls, which your Finatra service
then depends on.

### Anything else

The same shape works for Akka HTTP, Pekko HTTP, sttp, and a plain blocking
`java.net.http.HttpClient`. See [no effect system](core-module.md).

## Kyo

Not currently usable with this project, for two independent reasons:

- Kyo's current release, **1.0.0-RC6**, is built with **Scala 3.8.4**. TASTy is
  only backward compatible, so a Scala 3.3 LTS module cannot read it.
- It ships **Java 25 bytecode**, which this project's JDK 21 toolchain cannot
  load. That 21 is a ceiling rather than a preference: no current Scala 3
  compiler emits past it.

`kyo-cats` would otherwise be the bridge, via `Cats.get`, making this a
Strategy B integration. If your own project already runs on Java 25 and a
recent Scala 3, that should work; it simply cannot be compiled and verified
here, so it is not shipped as an example.
