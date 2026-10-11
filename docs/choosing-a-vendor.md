# Choosing a vendor

Verdict4s speaks to two decision APIs:

- **TypeSafe AI's [Jev](https://docs.typesafe.ai)**: `POST https://api.typesafe.ai/v1/systemone`
- **[OpenAI Decisions](https://developers.openai.com/api/docs/guides/decisions)**: `POST https://api.openai.com/v1/decisions`

Both answer the same three kinds of question, with the same kinds of answer:

| Question | Answer |
|---|---|
| Yes/no | a probability |
| Pick one | a choice, with a probability per option and a confidence |
| Rubric | a score, with a probability per level and a confidence |

They differ only in how they spell these on the wire. Verdict4s hides that
difference, so the same questions, the same `Ask` and the same typed answers
work with either vendor:

```scala
def triage[F[_]](client: Verdict4sClient[F]) =
  client.ask(
    Ask(
      Question.noul("Does this convey urgency?"),
      Question.choice[Dept]("Which team should handle this?")
    ),
    "Help! My payouts have been failing for 3 days."
  )

Verdict4s.jev[IO](typesafeKey).use(triage)
Verdict4s.openAI[IO](openAIKey).use(triage)
```

## Picking one at runtime

A `Vendor` is a plain value, so the choice can come from configuration:

```scala
val vendor: Vendor = if useOpenAI then Vendor.OpenAI else Vendor.Jev
Verdict4s.of[IO](vendor, key).use(triage)
```

On the JVM, `Verdict4sEnv.default` reads `VERDICT4S_VENDOR` (`jev`, the
default, or `openai`) and the matching key: `TYPESAFE_API_KEY` or
`OPENAI_API_KEY`.

## What only one vendor has

A feature that only one vendor offers is a separate capability trait, mixed
into that vendor's client:

- `ModelCatalog` on `JevClient`
- `SafetyIdentity` on `OpenAIClient`

Ask for exactly the capabilities you use, and the compiler rejects a call the
chosen vendor cannot serve:

```scala
def listModels[F[_]](client: Verdict4sClient[F] & ModelCatalog[F]) = client.models

listModels(jevClient)     // compiles
listModels(openAIClient)  // does not: OpenAI Decisions has no models endpoint
```

| | Jev | OpenAI Decisions |
|---|---|---|
| Default model | `jev-latest` | `gpt-6-luna`, the only one |
| List models | `ModelCatalog` | — |
| End-user identifier | — | `SafetyIdentity` (`safety_identifier`) |
| Choice options | 1 to 255 | 2 to 255 |
| Questions per request | no limit | at most 200 |
| Yes/no criteria (`noul(q, ifTrue, ifFalse)`) | yes | no |
| Instructions written as JSON | sent as JSON | sent as compact JSON text |
| Refuses individual questions | never | may |
| Request id header | `x-typesafe-request-id` | `x-request-id` |

Requests are checked against the chosen vendor's own limits before they are
sent, and every problem is reported at once. A Noul with criteria, for
example, fails with `Verdict4sError.Invalid` against OpenAI rather than having
its criteria silently dropped.

## Thresholds do not carry over

Switching vendors is one line of code, but the numbers that come back are not
interchangeable. Both vendors define probability, score and confidence the same
way: OpenAI's confidence values have so far matched the formulas Jev
[publishes](https://docs.typesafe.ai/confidence), although OpenAI does not
document its own. They are still different models, though, and they give
different numbers for the same input.

A rule such as `confidence >= 0.8`, tuned on one vendor's answers, is a guess on
the other's. When you switch, or run both, set each threshold again from
labelled examples of your own, per vendor.

## Refusals

OpenAI Decisions may decline to answer one question while answering the rest.
By default that fails the whole `ask` with `Verdict4sError.Refused`. The error
names every refused question and carries the full response, so the other
answers are not lost:

```scala
client.ask(questions, state).recover:
  case Verdict4sError.Refused(keys, evaluation) => fallback(evaluation)
```

Where an unanswered question is something your code can handle, build that
question with `orRefused`. It then answers in `Option`:

```scala
val (abusive, team) = client.ask(
  Ask(Question.noul("Is this abusive?").orRefused, Question.choice[Dept]("Who handles it?")),
  ticket
)
// abusive: Option[NoulAnswer]
```

Jev never refuses, so on Jev `orRefused` always gives `Some`, and the same code
runs against both vendors.

## Naming

Verdict4s uses Jev's names, because they came first: a yes/no question is a
`noul`, answered by a `NoulAnswer`. OpenAI calls the same thing a `predicate`,
and `Question.predicate` is provided as an alias.
