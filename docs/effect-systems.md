# Other effect systems

Verdict4s is built on cats-effect, but you do not have to be. There are three
ways in, and which one you use depends on what your effect type can offer.

Every example on this page lives in the `verdict4s-examples` module and is
compiled by CI, so none of it can quietly rot.

## Which strategy fits

The rule is the same whatever you use, so it applies to libraries this page
does not mention:

- **Your effect has a lawful cats-effect `Async` instance** → Strategy A. Use
  `Verdict4sClient[F]` directly, with nothing to bridge.
- **It does not, but something converts to and from `cats.effect.IO`** →
  Strategy B. Keep `IO` inside and convert at the edge.
- **Neither** → Strategy C. Use the sans-IO core and move the bytes with
  whatever HTTP client you already have.

| Example | Strategy |
|---|---|
| [ZIO](#zio) | **A** — direct, via `zio-interop-cats` |
| [Scala `Future`](#scala-future) | **B** — convert at the edge |
| [Twitter `Future`](#twitter-future) | **C** — sans-IO |

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

### Anything else

The same shape works for Akka HTTP, Pekko HTTP, sttp, and a plain blocking
`java.net.http.HttpClient`. See [no effect system](core-module.md).

A library published only for Scala 2.13 is usually still usable: Scala 3 can
consume Scala 2.13 artifacts, as long as you depend on them with the suffix
spelled out (`mvn"org:lib_2.13:version"`), since `::` would look for a `_3`
build. The reverse does not hold — a Scala 2.13 application cannot depend on
verdict4s, which is Scala 3 only.
