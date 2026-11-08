package dev.restudio.dotnet.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.ServerStatus
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import java.nio.file.Path

/** Sends `solution/open` or `project/open` when Roslyn LS has started. */
class RoslynLanguageClient(project: Project) : LanguageClientImpl(project) {
  override fun handleServerStatusChanged(serverStatus: ServerStatus) {
    super.handleServerStatusChanged(serverStatus)
    if (serverStatus != ServerStatus.started) return
    val basePath = project.basePath ?: return
    val server = languageServer as? RoslynLanguageServer ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
      when (val workspace = RoslynWorkspace.find(Path.of(basePath))) {
        is RoslynWorkspace.Solution -> server.openSolution(SolutionOpenParams(workspace.file.toUri().toString()))
        is RoslynWorkspace.Projects -> server.openProjects(ProjectOpenParams(workspace.files.map { it.toUri().toString() }))
        null -> LOG.info("No solution and no project under $basePath. Roslyn LS loads no project.")
      }
    }
  }

  private companion object {
    val LOG = logger<RoslynLanguageClient>()
  }
}
