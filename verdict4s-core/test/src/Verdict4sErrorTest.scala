package com.softinio.verdict4s

class Verdict4sErrorTest extends munit.FunSuite:

  test("Api carries the status in its message"):
    val e = Verdict4sError.Api(503, "upstream unavailable")
    assert(e.getMessage.contains("503"))
    assert(e.getMessage.contains("upstream unavailable"))

  test("Transport preserves the underlying cause"):
    val boom = new RuntimeException("socket closed")
    val e = Verdict4sError.Transport("connection reset", Some(boom))
    assertEquals(e.getCause, boom)

  test("Decoding has no cause by default"):
    assertEquals(Verdict4sError.Decoding("unexpected field").getCause, null)
