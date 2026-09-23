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
import cats.Show
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.syntax.*

/** Metadata for a model available to the account.
  *
  * `releaseDate` stays a `String` rather than a date: `java.time` is not
  * available on Scala.js without `scala-java-time`, and the effect-free core
  * does not take that dependency for one display field.
  *
  * @group Protocol
  */
final case class ModelCard(
    name: Model,
    description: String,
    releaseDate: String
)

/** Codecs and instances for [[ModelCard]]. */
object ModelCard:

  given Decoder[ModelCard] = Decoder.instance: c =>
    for
      name <- c.downField("name").as[Model]
      description <- c.downField("description").as[String]
      releaseDate <- c.downField("release_date").as[String]
    yield ModelCard(name, description, releaseDate)

  given Encoder[ModelCard] = Encoder.instance: m =>
    Json.obj(
      "name" -> m.name.asJson,
      "description" -> m.description.asJson,
      "release_date" -> m.releaseDate.asJson
    )

  given Eq[ModelCard] = Eq.fromUniversalEquals
  given Show[ModelCard] = Show.show(_.asJson.noSpaces)
