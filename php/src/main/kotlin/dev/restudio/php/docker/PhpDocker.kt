package dev.restudio.php.docker

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import dev.restudio.core.sdk.ToolLocator
import dev.restudio.php.PhpBundle
import java.net.URI
import java.nio.file.Path

/**
 * The Docker container that runs Phpactor for a project, and the mapping between the host paths and the container paths.
 *
 * [hostRoot] is the project directory on this computer. [containerRoot] is the same directory in the container.
 */
data class PhpDockerTarget(val container: String, val hostRoot: String, val containerRoot: String) {
  /** The container path of the host path [path], or `null` when [path] is outside [hostRoot]. */
  fun toContainer(path: String): String? = map(path, hostRoot, containerRoot)

  /** The host path of the container path [path], or `null` when [path] is outside [containerRoot]. */
  fun toHost(path: String): String? = map(path, containerRoot, hostRoot)

  /** The `file:` URI of the container path for the host path [path], or `null` when [path] is outside [hostRoot]. */
  fun containerUri(path: String): URI? = toContainer(path)?.let(::fileUri)

  /** The host path for the `file:` URI [uri] of a container file, or `null` when the URI is not under [containerRoot]. */
  fun hostPath(uri: String): String? {
    val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
    if (parsed.scheme != "file") return null
    return parsed.path?.let(::toHost)
  }

  private fun map(path: String, from: String, to: String): String? {
    val root = from.trimEnd('/')
    return when {
      path == root -> to.trimEnd('/').ifEmpty { "/" }
      path.startsWith("$root/") -> to.trimEnd('/') + path.substring(root.length)
      else -> null
    }
  }
}

/** The `file:///` URI of [path]. The empty authority gives the three slashes that the LSP clients use. */
fun fileUri(path: String): URI = URI("file", "", path, null)

/**
 * Starts Phpactor with the PHP of a running Docker container.
 *
 * The IDE copies the pinned PHAR into the container with `docker cp`, then runs it with `docker exec -i`.
 * A copy keeps the PHAR out of the project directory, so the project repository gets no new file.
 * The copy goes to `/tmp`, so it is gone when the container is created again. Thus each start copies it again.
 */
object PhpDocker {
  /** The PHAR path in the container. The version is in the name, so that two versions do not mix. */
  fun pharInContainer(version: String): String = "/tmp/restudio-phpactor-$version.phar"

  /** The target of [project], or `null` when the project settings name no container. */
  fun target(project: Project): PhpDockerTarget? {
    val settings = PhpDockerSettings.getInstance(project)
    val container = settings.container.trim()
    val containerRoot = settings.containerPath.trim()
    val hostRoot = project.basePath ?: return null
    if (container.isEmpty() || containerRoot.isEmpty()) return null
    return PhpDockerTarget(container, hostRoot, containerRoot)
  }

  /** The `docker exec` command that starts the language server. */
  fun execCommand(docker: String, target: PhpDockerTarget, phar: String): List<String> =
    listOf(docker, "exec", "-i", "-w", target.containerRoot, target.container, "php", phar, "language-server")

  /**
   * Copies [phar] into the container and returns the start command.
   *
   * When the copy fails, for example because the container is not running, the user gets a notification.
   * The command is returned all the same, so that LSP4IJ shows the server as failed.
   */
  fun phpactorCommand(project: Project, target: PhpDockerTarget, phar: Path, version: String): List<String> {
    val docker = ToolLocator.find("docker")?.toString() ?: "docker"
    val inContainer = pharInContainer(version)
    val copy = GeneralCommandLine(docker, "cp", phar.toString(), "${target.container}:$inContainer")
      .withEnvironment("PATH", ToolLocator.childPath())
    val output = runCatching { CapturingProcessHandler(copy).runProcess(COPY_TIMEOUT_MS) }.getOrNull()
    if (output == null || output.exitCode != 0 || output.isTimeout) {
      val reason = output?.stderr?.trim()?.takeLast(500).orEmpty()
      notify(project, PhpBundle.message("docker.copy.failed", target.container, reason))
    }
    return execCommand(docker, target, inContainer)
  }

  private fun notify(project: Project, content: String) {
    NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
      .createNotification(content, NotificationType.WARNING)
      .notify(project)
  }

  private const val NOTIFICATION_GROUP = "ReStudio PHP"
  private const val COPY_TIMEOUT_MS = 30_000
}
