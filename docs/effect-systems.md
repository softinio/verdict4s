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
- **Neither** → Strategy C. Use the effect-free core and move the bytes with
  whatever HTTP client you already have.

| Example | Strategy |
|---|---|
| [ZIO](#zio) | **A** — direct, via `zio-interop-cats` |
| [Scala `Future`](#scala-future) | **B** — convert at the edge |
| [JDK `HttpClient`](#jdk-httpclient) | **C** — no effect system |

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

## Strategy C — no effect system

`verdict4s-core` renders a request to JSON and parses a reply back into typed
answers, with no effect system anywhere. You move the bytes.

### JDK `HttpClient`

No dependencies at all — the JDK's own client moves the bytes:

```scala
def triage(apiKey: ApiKey, ticket: String, http: HttpClient): Either[Verdict4sError, (Boolean, Dept)] =
  for
    body     <- SystemOne.renderAsk(questions, ticket)
    response <- send(http, apiKey, body)   // HttpRequest + http.send
    answers  <- if response.statusCode >= 400
                then Left(SystemOne.parseError(response.statusCode, response.body))
                else SystemOne.parseAsk(questions, response.body)
  yield
    val (urgent, dept) = answers
    (urgent.isTrue(), dept.choice)
```

Every failure — an invalid question, a network error, a rejected request, an
unexpected body — comes back as a `Verdict4sError`. For a non-blocking call,
`HttpClient.sendAsync` returns a `CompletableFuture`; rendering and parsing
stay exactly the same.

### Anything else

The same shape works for Twitter `Future`, Akka HTTP, Pekko HTTP and sttp: only
the lines that send the bytes change. Twitter `Future` in particular has no
other route, since `catbird-effect` is not published for Scala 3. See
[no effect system](core-module.md).

A library published only for Scala 2.13 is usually still usable: Scala 3 can
consume Scala 2.13 artifacts, as long as you depend on them with the suffix
spelled out (`mvn"org:lib_2.13:version"`), since `::` would look for a `_3`
build. The reverse does not hold — a Scala 2.13 application cannot depend on
verdict4s, which is Scala 3 only.
