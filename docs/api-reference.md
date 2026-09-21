# API reference

Generated Scaladoc for each module, built from the same sources as this site.
Each layer depends on the one below it, so the types you meet in
`verdict4s-client` are defined in `verdict4s-core`.

| Module | What you will find there |
|---|---|
| [verdict4s](api/verdict4s/index.html) | `Verdict4s.default`, the one-line entry point, and `Verdict4sEnv` on the JVM |
| [verdict4s-client](api/verdict4s-client/index.html) | `Verdict4sClient`, `Jitter`, and the opt-in `syntax` for `.run` |
| [verdict4s-core](api/verdict4s-core/index.html) | `Question`, `Ask`, `Options`, `Levels`, the answer types, `RetryPolicy`, `SystemOne`, `Verdict4sError` |

Most of what you write against lives in `verdict4s-core`, in
`com.softinio.verdict4s.algebra` — start there.

