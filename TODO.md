# TODO

The Jev protocol is implemented across all three layers and the suite is green
on all twelve targets. What is left is release mechanics and follow-ups.

1. **Set up Maven Central publishing secrets** in the GitHub repo:
   `MILL_PGP_PASSPHRASE`, `MILL_PGP_SECRET_BASE64`, `MILL_SONATYPE_PASSWORD`,
   `MILL_SONATYPE_USERNAME`.
2. **Re-run the integration suite when the API changes.** `LiveApiIT` is gated
   on `TYPESAFE_API_KEY` and reports as ignored without one:
   ```
   TYPESAFE_API_KEY=… mill "verdict4s.jvm[3.3.8].test.testOnly" com.softinio.verdict4s.LiveApiIT
   ```
   It passed against the live service on 2026-09-23, which is what caught the
   `models` envelope. It is the only check that the wire format matches the
   service rather than its documentation.
3. **Decide the JDK floor.** `-release 21` is the current setting, the highest
   both 3.3.8 and 3.9.0 accept. Drop to 17 for wider reach.
