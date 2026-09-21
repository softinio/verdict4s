package com.softinio.verdict4s

import io.circe.Encoder

/** Optional sugar for the call site.
  *
  * The documented form is `client.ask(questions, state)`, which needs nothing
  * in implicit scope and infers `F` from the client. This import adds a
  * reading-order alternative for those who prefer it:
  *
  * ```scala
  * import com.softinio.verdict4s.syntax.*
  *
  * given Verdict4sClient[IO] = client
  * val (urgent, dept) = Ask(q1, q2).run("payouts failing")
  * ```
  *
  * `run` needs the client as a `given` for `F` to be inferable, which is an
  * unusual idiom; [[runWith]] is the same thing with the client passed
  * explicitly.
  *
  * @group Client
  */
object syntax:

  extension [Q <: Tuple](questions: Ask[Q])

    /** Ask these questions using a client from implicit scope.
      *
      * No `Temporal[F]` bound here on purpose: the client captured one when it
      * was built, and asking for it again would be searched *before* the
      * client, leaving `F` unconstrained and the summon failing.
      */
    def run[F[_], S: Encoder](state: S)(using
        client: Verdict4sClient[F]
    ): F[Ask.Answers[Q]] =
      client.ask(questions, state)

    /** Ask these questions using an explicitly supplied client. */
    def runWith[F[_], S: Encoder](
        client: Verdict4sClient[F],
        state: S
    ): F[Ask.Answers[Q]] =
      client.ask(questions, state)
