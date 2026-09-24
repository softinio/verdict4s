# Errors and retries

Everything that can go wrong is one of five cases of `Verdict4sError`, all of
which live in `verdict4s-core` so you can match on them without depending on
cats-effect or http4s.

```scala
import com.softinio.verdict4s.Verdict4sError
import com.softinio.verdict4s.algebra.ApiFailure

val describe: Verdict4sError => String =
  case e: Verdict4sError.Api        => s"the service said ${e.status}: ${e.details}"
  case e: Verdict4sError.Decoding   => s"unexpected response: ${e.getMessage}"
  case e: Verdict4sError.Transport  => s"could not reach the service: ${e.details}"
  case e: Verdict4sError.Validation => s"bad question: ${e.getMessage}"
  case e: Verdict4sError.Invalid    => s"${e.errors.length} problems with the request"
```

## Validation happens before the network

`Validation` is a single broken rule; `Invalid` is the accumulated set. A
request with three malformed questions reports all three at once — and never
leaves your process.

```scala
Question.noul("", "", "")  // three blank fields, three errors
```

## Classifying a failure

Matching on raw status codes is awkward: the two statuses you most often want
to treat alike, `429` and `529`, are numerically unrelated, and `5xx` is a
range. `ApiFailure` names them instead.

```scala
error.failure match
  case ApiFailure.Unauthorized => refreshCredentials()
  case ApiFailure.RateLimited  => backOff()
  case ApiFailure.Overloaded   => backOff()
  case other                   => giveUp(other)
```

Every `Api` error also carries `requestId` — the service's own correlation id,
which is what to quote in a support request — and `retryAfter` when the server
sent one.

An error body this version does not recognise is **never** downgraded into a
decoding failure: the raw payload is preserved on the error, because it is
usually the only diagnostic you have.

## Retries are on by default

The API documents `429` and `529` with an explicit instruction to retry with
exponential backoff, so verdict4s does, matching the official SDKs: two
retries, 500ms doubling to a five second ceiling, 25% jitter, honouring
`Retry-After`, with a thirty second ceiling on the whole call.

Jitter is what stops many clients that were rate-limited together from all
retrying at the same instant.

To change it:

```scala
val config = ClientConfig.default.withRetry(
  RetryPolicy(maxRetries = 5, backoffMax = 30.seconds).toOption.get
)
```

To turn it off:

```scala
ClientConfig.default.withoutRetries
```

Only `408`, `429` and `5xx` are retried. `401`, `403`, `404` and `422` are not:
retrying them would just be slower failure.

`Retry-After` wins only when it asks for **longer** than the computed backoff —
waiting less than you already planned to would defeat the purpose.
