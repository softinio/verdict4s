package com.softinio.verdict4s.algebra

import io.circe.Json
import io.circe.syntax.*

class InstructionsTest extends munit.FunSuite:

  test("accepts a non-blank string"):
    assertEquals(
      Instructions.text("Does this convey urgency?").toOption.map(_.toJson),
      Some(Json.fromString("Does this convey urgency?"))
    )

  test("rejects a blank string"):
    assert(Instructions.text("").isInvalid)
    assert(Instructions.text("   ").isInvalid)
    assert(Instructions.text(null).isInvalid)

  test("accepts objects and arrays"):
    val obj = Json.obj("question" -> Json.fromString("same person?"))
    val arr = Json.arr(Json.fromString("a"), Json.fromString("b"))
    assertEquals(Instructions.json(obj).toOption.map(_.toJson), Some(obj))
    assertEquals(Instructions.json(arr).toOption.map(_.toJson), Some(arr))

  test("rejects numbers, booleans and null"):
    assert(Instructions.json(Json.fromInt(3)).isInvalid)
    assert(Instructions.json(Json.fromBoolean(true)).isInvalid)
    assert(Instructions.json(Json.Null).isInvalid)

  test("a JSON string still has to be non-blank"):
    assert(Instructions.json(Json.fromString("  ")).isInvalid)

  test("encoded builds structured instructions from any encodable value"):
    val m = Map("question" -> "same person as `dup`?", "dup" -> "John Smith")
    assertEquals(Instructions.encoded(m).toOption.map(_.toJson), Some(m.asJson))

  test("round trips through its codec"):
    val i = Instructions.text("rate the tone").toOption.get
    assertEquals(i.toJson.as[Instructions], Right(i))

  test("decoding rejects a payload the API would not accept"):
    assert(Json.fromInt(1).as[Instructions].isLeft)
