# No effect system

`verdict4s-core` carries only cats-core, circe and Iron. No effect type, no
HTTP client, deliberately no fs2. You can drive the protocol with whatever you
already use — including nothing at all.

```scala
def mvnDeps = Seq(mvn"com.softinio::verdict4s-core::@VERSION@")
```

This style — the library builds and reads the messages, you do the I/O — is
sometimes called *sans-IO*.

## The shape of it

```scala
import com.softinio.verdict4s.*
import com.softinio.verdict4s.algebra.*

val questions = Ask(
  Question.noul("Does this convey urgency?"),
  Question.choice[Dept]("Which team should handle this?")
)

// 1. render
val body: Either[Verdict4sError, String] =
  SystemOne.renderAsk(questions, "Help! My payouts are failing.")

// 2. send it however you like -- your code, your HTTP client
//    POST to SystemOne.evaluateUrl with SystemOne.headers(apiKey)

// 3. read the answers back, at exactly the right types
val answers = SystemOne.parseAsk(questions, responseBody)
```

`SystemOne` also gives you `evaluateUrl`, `modelsUrl`, `headers`, and
`parseError` for turning a failed response into a typed error.

## Why this layer exists

Three things fall out of keeping it effect-free:

**Any effect system works.** Twitter `Future`, Akka or Pekko HTTP, sttp, a
plain blocking `HttpClient` — see [other effect systems](effect-systems.md).

**Any platform works.** The same code runs on the JVM and on Scala.js.

**Tests need nothing.** Verdict4s' own codec tests have no network, no effect
system and no mocks, because there is nothing there to mock.

The invariant that makes this true is narrow and easy to break: core must not
gain a dependency on cats-effect, http4s or fs2. Notably fs2 is excluded even
though it looks harmless, because `fs2-core` pulls in `cats-effect-kernel`.

## Validation without sending anything

Because building a request is pure, you can check one in a unit test, or at
startup, without a key or a network:

```scala
val result = Ask(Question.score("How frustrated?", levels))
  .request(state, Model.JevLatest)

result.isValid  // false if `levels` is not 2..10 entries
```
