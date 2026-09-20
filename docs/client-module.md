# Bring your own client

`verdict4s-client` takes an http4s `Client[F]` rather than building one. That
single decision buys every backend and every platform without this library
owning any of them.

```scala
def mvnDeps = Seq(mvn"com.softinio::verdict4s-client::<version>")
```

```scala
import com.softinio.verdict4s.Verdict4sClient

val client = Verdict4sClient[IO](myHttpClient, apiKey)
```

Ember, Fetch, Blaze, Netty, the JDK client — anything with an http4s backend
works, and so does anything you write yourself.

## Configuration

```scala
Verdict4sClient[IO](
  myHttpClient,
  apiKey,
  baseUri = uri"https://api.typesafe.ai",
  config  = ClientConfig.default.withModel(Model.JevPreview)
)
```

`ClientConfig` covers the default model, the retry policy and the per-request
timeout. Note it holds no base URI: that belongs to the client layer, because
`verdict4s-core` has no http4s types in it at all.

## Testing without a network

This is the real reason the client takes its transport. `Client.fromHttpApp`
serves routes in memory, so there is no socket and no port to bind, and the
same test runs on the JVM and on Node:

```scala
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*

val routes = HttpRoutes
  .of[IO] { case POST -> Root / "v1" / "systemone" => Ok(cannedResponse) }
  .orNotFound

val fake = Verdict4sClient[IO](Client.fromHttpApp(routes), apiKey, uri"https://example.invalid")
```

Capture the request in a `Ref` if you want to assert what was actually sent:

```scala
for
  seen <- Ref[IO].of(Option.empty[Json])
  app = HttpRoutes.of[IO] { case req @ POST -> Root / "v1" / "systemone" =>
          req.as[Json].flatMap(j => seen.set(Some(j))) *> Ok(cannedResponse)
        }.orNotFound
  _    <- Verdict4sClient[IO](Client.fromHttpApp(app), apiKey).ask(questions, state)
  body <- seen.get
yield assert(body.isDefined)
```

No mocking library is needed for any of this. Verdict4s' own test suite is
built exactly this way.

### Testing your own logic instead

If you only care that *your* code reacts correctly to an answer, you do not
need HTTP at all — build an `Evaluation` and project it:

```scala
val canned = Evaluation(Model.JevLatest, answers, Usage(10, 2))
val Right(answers) = questions.answers(canned)
myService.decide(answers.head)
```

### Making retries deterministic

Retry delays are jittered, which is good in production and awkward in a test.
Pin the sample:

```scala
client.withJitter(Jitter.constant[IO](0.5))
```

Combined with a small `backoffInitial`, retry behaviour becomes exactly
reproducible and fast.
