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

import cats.Eq
import cats.Order
import cats.Show
import cats.data.ValidatedNec
import cats.syntax.all.*
import com.softinio.verdict4s.Verdict4sError
import io.circe.Decoder
import io.circe.Encoder

/** The model that handles a request.
  *
  * Not an `enum`: the set of models is owned by the service and grows without a
  * library release, so pinning it in a closed sum would make every new model a
  * breaking change. The aliases below are provided as constants instead.
  *
  * @group Protocol
  */
opaque type Model = String

/** The model aliases, and naming a specific model. */
object Model:

  /** The most recent stable release. The default, and what you usually want. */
  val JevLatest: Model = "jev-latest"

  /** The newest release, official or preview. */
  val JevPreview: Model = "jev-preview"

  /** Name a model explicitly, for example a pinned `jev-1.13.0`. */
  def fromString(
      raw: String
  ): ValidatedNec[Verdict4sError.Validation, Model] =
    val trimmed = if raw == null then "" else raw.trim
    if trimmed.isEmpty then
      Verdict4sError.Validation("model", "must not be blank").invalidNec
    else (trimmed: Model).validNec

  /** Wrap a value already known to be well formed. */
  private[verdict4s] def unsafe(raw: String): Model = raw

  extension (m: Model)
    /** The identifier as it appears on the wire. */
    def name: String = m

  given Encoder[Model] = Encoder.encodeString
  given Decoder[Model] = Decoder.decodeString
  // Not `Order.by(identity[String])`: inside this file `Model` *is* `String`,
  // so that resolves back to this very instance and loops forever.
  given Order[Model] = Order.from((a, b) => a.compareTo(b))
  given Eq[Model] = Eq.fromUniversalEquals
  given Show[Model] = Show.show(identity)
