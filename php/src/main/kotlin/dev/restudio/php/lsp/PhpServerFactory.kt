package dev.restudio.php.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import dev.restudio.php.sdk.PhpSdk
import dev.restudio.php.settings.PhpLanguageServer
import dev.restudio.php.settings.PhpSettings
import java.nio.file.Path

/**
 * Starts the PHP language server that [PhpSettings] selects.
 *
 * Phpactor is the default. It is MIT-licensed and reads `composer.json` by itself.
 * Intelephense is optional. The user installs it with npm.
 */
class PhpServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider {
    val command = when (PhpSettings.getInstance().languageServer) {
      PhpLanguageServer.PHPACTOR -> (PhpSdk.phpactor() ?: listOf("phpactor")) + "language-server"
      PhpLanguageServer.INTELEPHENSE -> listOf(PhpSdk.intelephense()?.toString() ?: "intelephense", "--stdio")
    }
    val commandLine = GeneralCommandLine(command).withEnvironment(PhpSdk.environment())
    project.basePath?.let { commandLine.withWorkingDirectory(Path.of(it)) }
    return OSProcessStreamConnectionProvider(commandLine)
  }

  override fun createClientFeatures(): LSPClientFeatures = PhpClientFeatures()

  companion object {
    /** The `id` of the `server` entry in `plugin.xml`. */
    const val SERVER_ID: String = "restudio.php"
  }
}
