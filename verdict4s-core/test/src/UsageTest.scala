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

import io.circe.Json
import io.circe.syntax.*

class UsageTest extends munit.FunSuite:

  test("decodes the documented snake_case payload"):
    val json = Json.obj(
      "input_tokens" -> Json.fromInt(296),
      "output_tokens" -> Json.fromInt(20)
    )
    assertEquals(json.as[Usage], Right(Usage(296, 20)))

  test("encodes back to snake_case"):
    assertEquals(
      Usage(296, 20).asJson,
      Json.obj(
        "input_tokens" -> Json.fromInt(296),
        "output_tokens" -> Json.fromInt(20)
      )
    )

  test("total sums both directions"):
    assertEquals(Usage(296, 20).total, 316)

  test("a missing field is a decoding failure"):
    assert(Json.obj("input_tokens" -> Json.fromInt(1)).as[Usage].isLeft)
