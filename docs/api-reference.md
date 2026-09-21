# API reference

**[Open the API reference](api/index.html)** — generated Scaladoc for all three
modules in one place, so a type from one layer links through to where it is
defined in another, and a single search covers everything.

Everything lives under two packages:

| Package | What you will find there |
|---|---|
| [`com.softinio.verdict4s.algebra`](api/com/softinio/verdict4s/algebra.html) | The protocol as types: `Question`, `Options`, `Levels`, the answer types, `Probability` and the other refined values, `RetryPolicy`, `ApiKey`, `Model` |
| [`com.softinio.verdict4s`](api/com/softinio/verdict4s.html) | The entry points: `Ask`, `Verdict4sClient`, `Verdict4s.default`, `SystemOne`, `Verdict4sError`, and `Verdict4sEnv` on the JVM |

Most of what you write against is in `algebra` — start there.

## Which module a type comes from

You only need this when choosing a dependency: each module includes the ones
below it.

| Module | Types |
|---|---|
| `verdict4s-core` | everything in `algebra`, plus `Ask`, `SystemOne` and `Verdict4sError` |
| `verdict4s-client` | `Verdict4sClient`, `Jitter`, and the opt-in `syntax` for `.run` |
| `verdict4s` | `Verdict4s`, and `Verdict4sEnv` on the JVM |

`Verdict4sEnv` is JVM-only because Scala.js has no environment to read; every
other type is available on both platforms.
