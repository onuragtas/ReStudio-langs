package dev.restudio.php

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.restudio.php.docker.ContainerUris
import dev.restudio.php.docker.PhpDocker
import dev.restudio.php.docker.PhpDockerSettings
import dev.restudio.php.docker.PhpDockerTarget
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier

class PhpDockerTest : BasePlatformTestCase() {
  private val target = PhpDockerTarget("api", "/Users/me/projects/EPA_API", "/var/www/html")

  fun `test a host path maps to the container and back`() {
    assertEquals("/var/www/html/app/User.php", target.toContainer("/Users/me/projects/EPA_API/app/User.php"))
    assertEquals("/Users/me/projects/EPA_API/app/User.php", target.toHost("/var/www/html/app/User.php"))
    assertEquals("/var/www/html", target.toContainer("/Users/me/projects/EPA_API"))
  }

  fun `test a path outside the root does not map`() {
    assertNull(target.toContainer("/Users/me/projects/EPA_API2/a.php"))
    assertNull(target.toHost("/usr/share/php/a.php"))
    assertNull(target.hostPath("jar:file:///x.jar!/a.php"))
  }

  fun `test the URIs map in both directions`() {
    assertEquals("file:///var/www/html/app/My%20File.php", target.containerUri("/Users/me/projects/EPA_API/app/My File.php").toString())
    assertEquals("/Users/me/projects/EPA_API/app/My File.php", target.hostPath("file:///var/www/html/app/My%20File.php"))
  }

  fun `test a request gets the container URI of the document`() {
    // LSP4IJ sends the host URI in a request, also when the document was opened with the container URI.
    val params = DefinitionParams(TextDocumentIdentifier("file:///Users/me/projects/EPA_API/app/My%20File.php"), Position(3, 4))
    ContainerUris.toContainer(params, target)
    assertEquals("file:///var/www/html/app/My%20File.php", params.textDocument.uri)

    val change = DidChangeTextDocumentParams(VersionedTextDocumentIdentifier("file:///Users/me/projects/EPA_API/a.php", 2), emptyList())
    ContainerUris.toContainer(change, target)
    assertEquals("file:///var/www/html/a.php", change.textDocument.uri)

    val watched = DidChangeWatchedFilesParams(listOf(FileEvent("file:///Users/me/projects/EPA_API/composer.json", FileChangeType.Changed)))
    ContainerUris.toContainer(watched, target)
    assertEquals("file:///var/www/html/composer.json", watched.changes.single().uri)
  }

  fun `test a URI outside the root or in the container stays`() {
    val outside = HoverParams(TextDocumentIdentifier("file:///tmp/other.php"), Position(0, 0))
    ContainerUris.toContainer(outside, target)
    assertEquals("file:///tmp/other.php", outside.textDocument.uri)

    val inContainer = ReferenceParams(TextDocumentIdentifier("file:///var/www/html/a.php"), Position(0, 0), ReferenceContext(false))
    ContainerUris.toContainer(inContainer, target)
    assertEquals("file:///var/www/html/a.php", inContainer.textDocument.uri)

    ContainerUris.toContainer(null, target)
    ContainerUris.toContainer(InitializedParams(), target)
  }

  fun `test the server starts in the container directory`() {
    assertEquals(
      listOf("docker", "exec", "-i", "-w", "/var/www/html", "api", "php", "/tmp/restudio-phpactor-1.phar", "language-server"),
      PhpDocker.execCommand("docker", target, PhpDocker.pharInContainer("1")),
    )
  }

  fun `test the project has a target only with both settings`() {
    val settings = PhpDockerSettings.getInstance(project)
    assertNull(PhpDocker.target(project))
    settings.container = "api"
    assertNull(PhpDocker.target(project))
    settings.containerPath = "/var/www/html/"
    val target = PhpDocker.target(project)!!
    assertEquals("api", target.container)
    assertEquals(project.basePath, target.hostRoot)
    assertEquals("/var/www/html/app/A.php", target.toContainer("${project.basePath}/app/A.php"))
    settings.container = ""
    settings.containerPath = ""
  }
}
