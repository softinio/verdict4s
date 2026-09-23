//> using scala 3.9.0
//> using dep org.http4s::http4s-ember-server:0.23.37
//> using dep org.http4s::http4s-dsl:0.23.37

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

import cats.effect.*
import com.comcast.ip4s.*
import java.nio.file.Paths
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder

/** Serves the site `mill docs.build` produced, at http://localhost:4242.
  *
  * Deliberately does not render anything itself. It used to carry its own
  * copy of the Laika transformer, which drifted from `LaikaBuild.scala` — so
  * the preview lacked syntax highlighting while the real build had it. Serving
  * the built output means the preview is, by construction, what gets
  * published, API reference included.
  */
object LaikaPreview extends IOApp.Simple {

  private val siteDir = "site/target/docs/site"

  private val app = HttpRoutes
    .of[IO] { case request @ GET -> path =>
      val raw  = path.toString
      val rel  = if raw == "/" || raw.isEmpty then "/index.html" else raw
      // Scaladoc links to directories; serve their index.html.
      val file = Paths.get(siteDir, rel).toFile match
        case dir if dir.isDirectory => Paths.get(dir.getPath, "index.html").toFile
        case other                  => other
      if file.exists() then StaticFile.fromFile[IO](file, Some(request)).getOrElseF(NotFound())
      else NotFound()
    }
    .orNotFound

  def run: IO[Unit] =
    IO.println("Serving the built site at http://localhost:4242 (Ctrl+C to stop)") *>
      EmberServerBuilder
        .default[IO]
        .withHost(ipv4"0.0.0.0")
        .withPort(port"4242")
        .withHttpApp(app)
        .build
        .useForever
}
