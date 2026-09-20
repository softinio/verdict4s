//> using scala 3.9.0
//> using dep org.typelevel::laika-core:1.3.2
//> using dep org.typelevel::laika-io:1.3.2
//> using dep org.typelevel::laika-preview:1.3.2
//> using dep org.http4s::http4s-ember-server:0.23.36
//> using dep org.http4s::http4s-dsl:0.23.36

import laika.preview.*
import laika.api.*
import laika.format.*
import laika.io.api.*
import laika.io.syntax.*
import laika.io.model.*
import laika.theme.*
import cats.effect.*
import cats.syntax.all.*
import laika.ast.Path.Root
import laika.helium.Helium
import laika.helium.config.*
import scala.concurrent.duration.*
import com.comcast.ip4s.*

object LaikaPreview extends IOApp.Simple {
  def run: IO[Unit] = {
    val heliumTheme = Helium.defaults
      .all.metadata(
        title = Some("Verdict4s"),
        language = Some("en")
      )
      .site.topNavigationBar(
        homeLink = IconLink.internal(Root / "index.md", HeliumIcon.home),
        navLinks = Seq(
          IconLink.external("https://github.com/softinio/verdict4s", HeliumIcon.github)
        )
      )
      .site.mainNavigation(depth = 3)
      .site.footer(
        """Verdict4s is a <a href="https://www.scala-lang.org">Scala 3</a> library.
          |Documentation built with <a href="https://typelevel.org/Laika/">Laika</a>.
          |""".stripMargin
      )
      .build

    import org.http4s._
    import org.http4s.dsl.io._
    import org.http4s.ember.server.EmberServerBuilder
    import org.http4s.server.staticcontent._
    import java.nio.file.Paths

    val transformer = Transformer
      .from(Markdown)
      .to(HTML)
      .using(Markdown.GitHubFlavor)
      .parallel[IO]
      .withTheme(heliumTheme)
      .build

    transformer.use { t =>
      val siteDir = "site/target/docs/preview"

      for {
        _ <- IO.println("Building documentation site...")
        _ <- t.fromDirectory("docs").toDirectory(siteDir).transform
        _ <- IO.println(s"Site built at: $siteDir")

        // Serve the built site with HTTP server
        httpApp = HttpRoutes.of[IO] {
          case request @ GET -> path =>
            val filePath = if (path.toString == "/" || path.toString.isEmpty) "/index.html" else path.toString
            val file = Paths.get(siteDir, filePath).toFile
            if (file.exists()) {
              StaticFile.fromFile[IO](file, Some(request)).getOrElseF(NotFound())
            } else {
              NotFound()
            }
        }.orNotFound

        _ <- IO.println("Starting preview server at http://localhost:4242")
        _ <- IO.println("Press Ctrl+C to stop")

        _ <- EmberServerBuilder
          .default[IO]
          .withHost(ipv4"0.0.0.0")
          .withPort(port"4242")
          .withHttpApp(httpApp)
          .build
          .use(_ => IO.never)
      } yield ()
    }
  }
}
