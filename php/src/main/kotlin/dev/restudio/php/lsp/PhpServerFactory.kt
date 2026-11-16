package dev.restudio.php.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import dev.restudio.core.lsp.ManagedServers
import dev.restudio.php.docker.PhpDocker
import dev.restudio.php.docker.PhpDockerTarget
import dev.restudio.php.sdk.PhpSdk
import dev.restudio.php.sdk.PhpTools
import dev.restudio.php.settings.PhpLanguageServer
import dev.restudio.php.settings.PhpSettings
import java.nio.file.Path

/**
 * Starts the PHP language server that [PhpSettings] selects.
 *
 * Phpactor is the default. It is MIT-licensed and reads `composer.json` by itself. It needs PHP on this computer.
 * When it is missing, the user gets an offer to install the pinned PHAR ([PhpTools.PHPACTOR]).
 * When this computer has no `php`, for example because PHP runs only in Docker, the user gets an offer to install
 * a static PHP build only for Phpactor ([PhpTools.PHP_RUNTIME]).
 * When the project settings name a Docker container, Phpactor runs in that container instead ([PhpDocker]).
 * Intelephense needs only Node.js. When it is missing, the user gets an offer to install it ([PhpTools.INTELEPHENSE]).
 */
class PhpServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider {
    val command = when (PhpSettings.getInstance().languageServer) {
      PhpLanguageServer.PHPACTOR -> phpactor(project)
      PhpLanguageServer.INTELEPHENSE ->
        listOf(ManagedServers.find(project, PhpTools.INTELEPHENSE, SERVER_ID)?.toString() ?: PhpTools.INTELEPHENSE.executable, "--stdio")
    }
    val commandLine = GeneralCommandLine(command).withEnvironment(PhpSdk.environment())
    project.basePath?.let { commandLine.withWorkingDirectory(Path.of(it)) }
    return OSProcessStreamConnectionProvider(commandLine)
  }

  override fun createClientFeatures(): LSPClientFeatures = PhpClientFeatures()

  /**
   * The Phpactor command. Without `php` on this computer, [PhpTools.PHP_RUNTIME] runs the PHAR.
   * Each missing part gives its own install offer, and each install restarts the server.
   */
  private fun phpactor(project: Project): List<String> {
    PhpDocker.target(project)?.let { target -> return dockerPhpactor(project, target) }
    val php = PhpSdk.php() ?: ManagedServers.find(project, PhpTools.PHP_RUNTIME, SERVER_ID)
    return PhpSdk.phpactorCommand(ManagedServers.find(project, PhpTools.PHPACTOR, SERVER_ID), php) + "language-server"
  }

  /**
   * The Phpactor command in the Docker container of the project. The container runs the pinned PHAR, so a PHAR
   * from the PATH does not count: it can be a shell script that needs the host.
   */
  private fun dockerPhpactor(project: Project, target: PhpDockerTarget): List<String> {
    val phar = ManagedServers.find(project, PhpTools.PHPACTOR, SERVER_ID)?.takeIf { it.fileName.toString().endsWith(".phar") }
      ?: return PhpSdk.phpactorCommand(null) + "language-server"
    return PhpDocker.phpactorCommand(project, target, phar, phar.parent.fileName.toString())
  }

  companion object {
    /** The `id` of the `server` entry in `plugin.xml`. */
    const val SERVER_ID: String = "restudio.php"
  }
}
