package dev.restudio.dotnet.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import dev.restudio.core.sdk.ToolLocator
import dev.restudio.dotnet.sdk.DotnetSdk
import org.eclipse.lsp4j.services.LanguageServer
import java.nio.file.Path

/** Starts Roslyn LS, the C# language server of Microsoft. Install it with `dotnet tool install -g roslyn-language-server --prerelease`. */
class RoslynServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider {
    val server = ToolLocator.find("roslyn-language-server")?.toString() ?: "roslyn-language-server"
    val logDirectory = Path.of(PathManager.getLogPath(), "roslyn")
    val commandLine = GeneralCommandLine(server, "--stdio", "--logLevel", "Information", "--extensionLogDirectory", logDirectory.toString())
      .withEnvironment(DotnetSdk.environment())
    project.basePath?.let { commandLine.withWorkingDirectory(Path.of(it)) }
    return OSProcessStreamConnectionProvider(commandLine)
  }

  override fun createLanguageClient(project: Project): LanguageClientImpl = RoslynLanguageClient(project)

  override fun getServerInterface(): Class<out LanguageServer> = RoslynLanguageServer::class.java
}
