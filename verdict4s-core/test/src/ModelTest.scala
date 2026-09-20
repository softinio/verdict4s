package com.softinio.verdict4s.algebra

import io.circe.Json

class ModelTest extends munit.FunSuite:

  test("aliases carry the documented identifiers"):
    assertEquals(Model.JevLatest.name, "jev-latest")
    assertEquals(Model.JevPreview.name, "jev-preview")

  test("fromString rejects blanks and trims"):
    assert(Model.fromString("").isInvalid)
    assert(Model.fromString("  ").isInvalid)
    assertEquals(
      Model.fromString("  jev-1.13.0 ").toOption.map(_.name),
      Some("jev-1.13.0")
    )

  test("encodes as a bare JSON string"):
    import io.circe.syntax.*
    assertEquals(Model.JevLatest.asJson, Json.fromString("jev-latest"))

  test("decodes from a bare JSON string"):
    assertEquals(
      Json.fromString("jev-1.13.0").as[Model].map(_.name),
      Right("jev-1.13.0")
    )

  test("ordering does not recurse"):
    // Guards the opaque-type trap: `Order.by(identity)` would stack overflow.
    val sorted = List(Model.JevPreview, Model.JevLatest).sorted(
      summon[cats.Order[Model]].toOrdering
    )
    assertEquals(sorted.map(_.name), List("jev-latest", "jev-preview"))
