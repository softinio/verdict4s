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
