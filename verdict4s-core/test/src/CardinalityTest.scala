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

/** The API's documented limits: a Choice takes 1 to 255 options, a Score takes
  * 2 to 10 levels. Checked at every boundary, because off-by-one here means a
  * request that fails a network round trip later instead of at construction.
  */
class CardinalityTest extends munit.FunSuite:

  private def options(n: Int) =
    Options.of((1 to n).map(i => s"option$i")*)

  private def levels(n: Int) =
    Levels.descriptionsOf((1 to n).map(i => s"level $i"))

  test("choice accepts 1 option"):
    assert(options(1).isValid)

  test("choice accepts 255 options"):
    assert(options(255).isValid)

  test("choice rejects 0 options"):
    assert(options(0).isInvalid)

  test("choice rejects 256 options"):
    assert(options(256).isInvalid)

  test(
    "the choice cardinality message names the real limit and the actual size"
  ):
    val err = options(256).swap.toOption.get.head
    assertEquals(err.field, "criteria")
    assert(err.details.contains("255"), err.details)
    assert(err.details.contains("256"), err.details)

  test("score rejects 1 level"):
    assert(levels(1).isInvalid)

  test("score accepts 2 levels"):
    assert(levels(2).isValid)

  test("score accepts 10 levels"):
    assert(levels(10).isValid)

  test("score rejects 11 levels"):
    assert(levels(11).isInvalid)

  test("the score cardinality message names the real limits and actual size"):
    val err = levels(11).swap.toOption.get.head
    assertEquals(err.field, "criteria")
    assert(err.details.contains("2 to 10"), err.details)
    assert(err.details.contains("11"), err.details)

  test("choice rejects a blank option name"):
    assert(Options.of("billing", "   ").isInvalid)

  test("choice rejects duplicate option names"):
    val err = Options.of("billing", "sales", "billing").swap.toOption.get.head
    assert(err.details.contains("billing"), err.details)

  test("score rejects a blank level description"):
    assert(Levels.descriptionsOf(Seq("Calm", "")).isInvalid)

  test("validation errors accumulate rather than stopping at the first"):
    // A request with several problems should report all of them, which is the
    // whole reason construction goes through ValidatedNec.
    val bad = Levels.descriptionsOf(Seq("", "  ", "\t"))
    assertEquals(bad.swap.toOption.get.length, 3L)

  test("Options exposes its names in sorted, wire order"):
    val o = Options.of("zebra", "apple", "mango").toOption.get
    assertEquals(
      o.names.map(n => n.value: String),
      List("apple", "mango", "zebra")
    )
    assertEquals(o.size, 3)

  test("Options round trips a value through render and parse"):
    val o = Options.of("billing", "sales").toOption.get
    assertEquals(o.parse("billing"), Some("billing"))
    assertEquals(o.parse("nope"), None)
    assertEquals(o.render("sales").value: String, "sales")

  test("Levels indexes by position, not by sort order"):
    val l = Levels.strings(Seq("Zebra", "Apple", "Mango")).toOption.get
    assertEquals(l.at(0), Some("Zebra"))
    assertEquals(l.at(2), Some("Mango"))
    assertEquals(l.at(3), None)
    assertEquals(l.indexOf("Apple"), 1)
    assertEquals(l.size, 3)
