# No effect system

`verdict4s-core` carries only cats-core, circe and Iron. No effect type, no
HTTP client, deliberately no fs2. You can drive either vendor with whatever you
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

val vendor = Vendor.Jev   // or Vendor.OpenAI

// 1. render, checked against the vendor's own limits
val body: Either[Verdict4sError, String] =
  vendor.renderAsk(questions, "Help! My payouts are failing.")

// 2. send it however you like -- your code, your HTTP client
//    POST to vendor.evaluateUrl with vendor.headers(apiKey)

// 3. read the answers back, at exactly the right types
val answers = vendor.parseAsk(questions, responseBody)
```

Every `Vendor` also gives you `evaluateUrl`, `headers`, and `parseError`, which
turns a failed response into a typed error. `Vendor.Jev` adds `modelsUrl` and
`parseModels`. (In 0.1 this was the `SystemOne` object; `Vendor.Jev` replaces
it.)

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

// and against a particular vendor's stricter rules:
result.toEither.flatMap(Vendor.OpenAI.validate(_).toEither)
```
