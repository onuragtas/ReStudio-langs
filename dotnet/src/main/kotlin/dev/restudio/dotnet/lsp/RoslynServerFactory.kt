package dev.restudio.dotnet.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import dev.restudio.core.lsp.TracingClientFeatures
import dev.restudio.core.sdk.ToolLocator
import dev.restudio.dotnet.sdk.DotnetSdk
import dev.restudio.dotnet.settings.CSharpServer
import dev.restudio.dotnet.settings.DotnetSettings
import org.eclipse.lsp4j.services.LanguageServer
import java.nio.file.Path

/**
 * Starts the C# language server.
 *
 * The default is Roslyn LS, the C# language server of Microsoft. Install it with
 * `dotnet tool install -g roslyn-language-server --prerelease`. When Roslyn LS is not installed, or when the user
 * selects it in the settings, the factory starts csharp-ls (`dotnet tool install -g csharp-ls`).
 * csharp-ls loads the solution from `--solution` and needs no `solution/open` notification (LSP probe, case csharp-ls).
 */
class RoslynServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider {
    val commandLine = when (selectedServer()) {
      CSharpServer.CSHARP_LS -> csharpLsCommandLine(project)
      else -> {
        val server = ToolLocator.find(ROSLYN) ?: Path.of(ROSLYN)
        val logDirectory = Path.of(PathManager.getLogPath(), "roslyn")
        GeneralCommandLine(server.toString(), "--stdio", "--logLevel", "Information", "--extensionLogDirectory", logDirectory.toString())
      }
    }
    commandLine.withEnvironment(DotnetSdk.environment())
    project.basePath?.let { commandLine.withWorkingDirectory(Path.of(it)) }
    return OSProcessStreamConnectionProvider(commandLine)
  }

  override fun createLanguageClient(project: Project): LanguageClientImpl =
    if (selectedServer() == CSharpServer.CSHARP_LS) LanguageClientImpl(project) else RoslynLanguageClient(project)

  override fun getServerInterface(): Class<out LanguageServer> = RoslynLanguageServer::class.java

  override fun createClientFeatures(): LSPClientFeatures = TracingClientFeatures()

  private fun csharpLsCommandLine(project: Project): GeneralCommandLine {
    val server = ToolLocator.find(CSHARP_LS) ?: Path.of(CSHARP_LS)
    val commandLine = GeneralCommandLine(server.toString())
    val solution = project.basePath?.let { RoslynWorkspace.find(Path.of(it)) } as? RoslynWorkspace.Solution
    if (solution != null) commandLine.addParameters("--solution", solution.file.fileName.toString())
    return commandLine
  }

  companion object {
    private const val ROSLYN = "roslyn-language-server"
    private const val CSHARP_LS = "csharp-ls"

    /** The server for [CSharpServer.AUTO] is Roslyn LS when it is installed, else csharp-ls when that is installed. */
    fun selectedServer(): CSharpServer = when (val choice = DotnetSettings.getInstanceOrNull()?.server ?: CSharpServer.AUTO) {
      CSharpServer.AUTO -> if (ToolLocator.find(ROSLYN) == null && ToolLocator.find(CSHARP_LS) != null) CSharpServer.CSHARP_LS else CSharpServer.ROSLYN
      else -> choice
    }
  }
}
