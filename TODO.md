# TODO

Build, CI and publishing are wired up and green. What is left is the library itself.

1. **Replace the placeholder API with the real Jev protocol.**
   - `verdict4s-core` — the request/response models, their circe codecs, and the
     decision types (choices, scores, probabilities). Keep this layer free of
     cats-effect, http4s and fs2, or the sans-IO split stops paying for itself.
   - `verdict4s-client` — replace `health` with the real endpoints. Streaming
     responses go through fs2 here, not in core.
   - `verdict4s` — `default` currently takes a `baseUri`; give it whatever
     auth/config the service actually needs.
2. **Decide the JDK floor.** `-release 21` is the current setting, which is the
   highest both 3.3.8 and 3.9.0 accept. Drop to 17 if you want wider reach.
3. **Rewrite `README.md`.** The module and command sections are accurate, but the
   title, intro and Quick Start are still `scala-mill-library-starter` boilerplate
   from the template.
4. **Set up Maven Central publishing secrets** in the GitHub repo:
   `MILL_PGP_PASSPHRASE`, `MILL_PGP_SECRET_BASE64`, `MILL_SONATYPE_PASSWORD`,
   `MILL_SONATYPE_USERNAME`.
5. **Initialize version control.** There is no `.jj` or `.git` here yet, so
   `mill-git` cannot derive a version — which makes `docJar`, `publishVersion`
   and anything downstream of them fail locally.
