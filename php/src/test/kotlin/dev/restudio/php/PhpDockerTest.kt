package dev.restudio.php

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.restudio.php.docker.PhpDocker
import dev.restudio.php.docker.PhpDockerSettings
import dev.restudio.php.docker.PhpDockerTarget

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
