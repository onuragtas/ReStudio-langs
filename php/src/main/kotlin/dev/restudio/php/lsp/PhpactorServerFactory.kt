package dev.restudio.php.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import dev.restudio.php.sdk.PhpSdk
import java.nio.file.Path

/** Starts Phpactor, the MIT-licensed PHP language server. It reads `composer.json` by itself. */
class PhpactorServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider {
    val command = (PhpSdk.phpactor() ?: listOf("phpactor")) + "language-server"
    val commandLine = GeneralCommandLine(command).withEnvironment(PhpSdk.environment())
    project.basePath?.let { commandLine.withWorkingDirectory(Path.of(it)) }
    return OSProcessStreamConnectionProvider(commandLine)
  }
}
