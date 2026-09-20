# Typed questions

The service offers exactly three question types. This page is about getting
answers back in *your* types rather than in strings and numbers.

## The three shapes

**Noul** is a yes/no question, answered with the probability that the answer is
yes. It carries no confidence — the probability *is* the answer.

```scala
Question.noul("Does this convey urgency?")
Question.noul("Is this urgent?", ifTrue = "Time-sensitive", ifFalse = "Routine")
```

**Choice** picks one option from a set you define, and returns the chosen
option, the full distribution, and a confidence.

**Score** rates against ordered levels and returns a probability-weighted
value, which can land *between* levels — `1.05` means "mostly level 1, leaning
towards 2".

## Options from an enum

The natural Scala spelling of "one of a closed set" is an `enum`, so Choice
derives from one:

```scala
enum Dept derives Options:
  case Billing, Technical, Sales

val q = Question.choice[Dept]("Which team should handle this?")
```

The case labels become the option names, and the answer comes back as a `Dept`,
not a `String`. Add rubric text where the names are not self-explanatory:

```scala
val options = Options.describing[Dept](
  Map("Billing" -> Instructions.text("Payments, invoicing, refunds").toOption.get)
)
```

Cardinality is checked when you declare the enum, not when you send the
request: an enum with more than 255 cases fails to compile.

For options only known at runtime:

```scala
Question.choiceOfStrings("Which team?", teams.map(_ -> None))
```

## Levels from an enum

```scala
enum Frustration derives Levels:
  case Calm, Frustrated, VeryAngry

Question.scoreOf[Frustration]("How frustrated is the customer?")
```

**Declaration order is rubric order**, lowest first, and it is what the
service's level indices refer to. Reordering the cases changes the meaning of
every score you have already recorded, so treat that order as part of your data
model. An enum with fewer than two or more than ten cases fails to compile.

## Asking several at once

```scala
val (urgent, team, mood) = client.ask(
  Ask(
    Question.noul("Does this convey urgency?"),
    Question.choice[Dept]("Which team should handle this?"),
    Question.score("How frustrated?", Seq("Calm", "Frustrated", "Very angry"))
  ),
  ticket
)
```

Answers come back positionally at exactly the right types. Question keys are
generated for you (`q00`, `q01`, …); use `withKeys` if they need to match
something else, and `withModel` to pin one request to a specific model.

## Your own answer types

`Question[A]` is a functor over its answer, so you can fold post-processing
into the question itself and have your own type appear in the tuple:

```scala
final case class Route(team: Dept, escalate: Boolean)

def route: Question[Route] =
  Question.choice[Dept]("Which team should handle this?")
    .map(a => Route(a.choice, escalate = (a.confidence.value: Double) < 0.7))
```

`emap` does the same but can reject an answer your type cannot represent; the
rejection surfaces as a `Verdict4sError` exactly as a malformed response would.

The wire request is unchanged by either — only the way the answer is read.

## When the questions are not known statically

Drop to the map-based API:

```scala
val evaluation: Evaluation = client.evaluate(request)

evaluation.answers.foreach { (key, answer) =>
  answer match
    case Answer.Noul(p)                       => ???
    case Answer.Choice(choice, probs, conf)   => ???
    case Answer.Score(score, legend, _, conf) => ???
}
```

## A question type this release does not model

If the service grows a fourth type, you do not have to wait for a release:

```scala
Question.raw[MyAnswer]("ranking", body) { answer => ??? }
```
