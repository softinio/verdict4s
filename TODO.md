# TODO

The Jev protocol is implemented across all three layers and the suite is green
on all twelve targets. What is left is release mechanics and follow-ups.

1. **Set up Maven Central publishing secrets** in the GitHub repo:
   `MILL_PGP_PASSPHRASE`, `MILL_PGP_SECRET_BASE64`, `MILL_SONATYPE_PASSWORD`,
   `MILL_SONATYPE_USERNAME`.
2. **Run the integration suite against the real service.** `LiveApiIT` is
   gated on `TYPESAFE_API_KEY` and reports as ignored without one:
   ```
   TYPESAFE_API_KEY=… mill "verdict4s.jvm[3.3.8].test"
   ```
   Worth doing before the first release: it is the only check that the wire
   format matches the live service rather than the published documentation.
3. **Decide whether decoding should stay strict about probabilities.** A value
   outside the unit interval is currently a decoding failure. That is
   deliberate — a value the service should never send signals a protocol
   change — but if floating-point summation ever produces `1.0000000002` it
   would reject a valid response. Item 2 is what will tell us.
4. **Decide the JDK floor.** `-release 21` is the current setting, the highest
   both 3.3.8 and 3.9.0 accept. Drop to 17 for wider reach.
5. **Revisit Kyo** when it stops requiring Java 25 bytecode and a Scala 3.8+
   compiler, or if the JDK floor moves. See `docs/effect-systems.md`.
