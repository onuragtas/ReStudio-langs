package dev.restudio.go.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import dev.restudio.core.sdk.ToolLocator

/** Starts gopls for the project. gopls reads `go.mod` and `go.work` by itself. */
class GoplsServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider {
    val gopls = ToolLocator.find("gopls")?.toString() ?: "gopls"
    val commandLine = GeneralCommandLine(gopls)
      .withEnvironment("PATH", ToolLocator.childPath())
    project.basePath?.let { commandLine.withWorkingDirectory(java.nio.file.Path.of(it)) }
    return OSProcessStreamConnectionProvider(commandLine)
  }
}
