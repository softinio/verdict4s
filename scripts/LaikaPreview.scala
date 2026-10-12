//> using scala 3.9.0
//> using dep org.http4s::http4s-ember-server:0.23.37
// http4s logs through log4cats -> slf4j-api 1.7.36, which warns at startup when
// it finds no backend. nop is the backend we want: silence, explicitly.
//> using dep org.slf4j:slf4j-nop:1.7.36

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
import org.http4s.HttpApp
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.staticcontent.*

/** Serves the site `mill docs.build` produced, at http://localhost:4242.
  *
  * Deliberately does not render anything itself. It used to carry its own
  * copy of the Laika transformer, which drifted from `LaikaBuild.scala` — so
  * the preview lacked syntax highlighting while the real build had it. Serving
  * the built output means the preview is, by construction, what gets
  * published, API reference included.
  */
object LaikaPreview extends IOApp.Simple {

  // http4s' file service serves a directory's index.html, which Scaladoc's
  // directory links rely on, and rejects `..` segments, so nothing outside the
  // site is reachable.
  private val app: HttpApp[IO] =
    fileService[IO](FileService.Config("site/target/docs/site")).orNotFound

  def run: IO[Unit] =
    IO.println("Serving the built site at http://localhost:4242 (Ctrl+C to stop)") *>
      EmberServerBuilder
        .default[IO]
        .withHost(ipv4"127.0.0.1")
        .withPort(port"4242")
        .withHttpApp(app)
        .build
        .useForever
}
