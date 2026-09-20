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
