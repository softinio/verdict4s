/*
 * Copyright 2026 Salar Rahmanian
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.softinio.verdict4s.algebra

class RefinementsTest extends munit.FunSuite:

  // `either` yields the nominal `.T`, which is NOT a subtype of its base;
  // `.value` unwraps it to `A :| C`, which is. Going via Option rather than
  // comparing Eithers keeps munit's Compare from inferring `Right[Nothing, _]`.
  private def prob(d: Double): Option[Double] =
    Probability.either(d).toOption.map(p => (p.value: Double))

  private def name(s: String): Option[String] =
    OptionName.either(s).toOption.map(n => (n.value: String))

  test("Probability accepts the closed unit interval"):
    assertEquals(prob(0.0), Some(0.0))
    assertEquals(prob(1.0), Some(1.0))
    assertEquals(prob(0.5), Some(0.5))

  test("Probability rejects values outside it"):
    assertEquals(prob(-0.001), None)
    assertEquals(prob(1.001), None)
    assertEquals(prob(Double.NaN), None)

  test("Probability folds a literal at compile time"):
    // The point of the refinement: no runtime check, no unwrapping.
    val p: Probability = Probability(0.95)
    assertEquals(p.value, 0.95)

  test("Confidence is a distinct type carrying the same bound"):
    assert(Confidence.either(1.001).isLeft)
    assertEquals(
      Confidence.either(0.81).toOption.map(c => (c.value: Double)),
      Some(0.81)
    )

  test("non-blank names reject empty and whitespace"):
    assertEquals(name(""), None)
    assertEquals(name("   "), None)
    assertEquals(name("\t\n"), None)
    assertEquals(name("billing"), Some("billing"))

  test("every non-blank identifier shares that rule"):
    assert(QuestionKey.either(" ").isLeft)
    assert(ModelId.either("").isLeft)
    assertEquals(
      ModelId.either("jev-1.13.0").toOption.map(m => (m.value: String)),
      Some("jev-1.13.0")
    )
