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

import cats.syntax.all.*

class ApiKeyTest extends munit.FunSuite:

  private def key(s: String): ApiKey = ApiKey.fromString(s).toOption.get

  test("rejects blank keys"):
    assert(ApiKey.fromString("").isInvalid)
    assert(ApiKey.fromString("   ").isInvalid)
    assert(ApiKey.fromString(null).isInvalid)

  test("trims surrounding whitespace"):
    assertEquals(key("  sk-abc  "), key("sk-abc"))

  test("toString never reveals the secret"):
    val k = key("sk-super-secret")
    assertEquals(k.toString, "ApiKey(***)")
    assert(!k.toString.contains("secret"))

  test("Show never reveals the secret"):
    assertEquals(key("sk-super-secret").show, "ApiKey(***)")

  test("a containing case class does not leak it either"):
    // This is the case an opaque type would have got wrong: the secret would
    // have appeared in every failed assertEquals on a surrounding value.
    final case class Config(apiKey: ApiKey, model: String)
    val rendered = Config(key("sk-super-secret"), "jev-latest").toString
    assert(!rendered.contains("super"), rendered)
    assert(rendered.contains("ApiKey(***)"), rendered)

  test("equality and hashing still work"):
    assertEquals(key("sk-a"), key("sk-a"))
    assertNotEquals(key("sk-a"), key("sk-b"))
    assertEquals(key("sk-a").hashCode, key("sk-a").hashCode)
    assertEquals(Set(key("sk-a"), key("sk-a")).size, 1)
