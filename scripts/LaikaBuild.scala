//> using scala 3.9.0
//> using dep org.typelevel::laika-core:1.3.2
//> using dep org.typelevel::laika-io:1.3.2

import laika.api.*
import laika.format.*
import laika.io.api.*
import laika.io.syntax.*
import laika.theme.*
import cats.effect.*
import laika.ast.Path.Root
import laika.helium.Helium
import laika.helium.config.*
import laika.config.SyntaxHighlighting

object LaikaBuild extends IOApp.Simple {
  def run: IO[Unit] = for {
    _ <- IO.println("Starting Laika documentation build...")

    heliumTheme = Helium.defaults
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

    transformer = Transformer
      .from(Markdown)
      .to(HTML)
      // GitHubFlavor only adds GFM parsing (tables, strikethrough, fenced
      // blocks). Highlighting is a separate opt-in bundle; without it, fenced
      // code renders as bare <pre><code> and Helium's CSS has no tokens to
      // colour.
      .using(Markdown.GitHubFlavor, SyntaxHighlighting)
      .parallel[IO]
      .withTheme(heliumTheme)
      .build

    _ <- IO.println("Running transformation...")
    _ <- transformer.use { t =>
      t.fromDirectory("docs")
        .toDirectory("site/target/docs/site")
        .transform
    }

    _ <- IO.println("Documentation site built successfully!")
  } yield ()
}
