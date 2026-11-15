package dev.restudio.core.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerManager
import dev.restudio.core.sdk.Tool
import dev.restudio.core.sdk.install.ToolInstaller
import java.nio.file.Path

/** Finds the executable of a language server and installs its pinned version on first use. */
object ManagedServers {
  /**
   * Returns the path of [tool], as [ToolInstaller.findOrOffer] does.
   *
   * When the pinned version is missing, the user gets a notification with an "Install" action.
   * After the install, the LSP4IJ server [serverId] restarts with the new executable.
   */
  fun find(project: Project, tool: Tool, serverId: String): Path? =
    ToolInstaller.findOrOffer(project, tool) { restart(project, serverId) }

  /** Stops and starts the LSP4IJ server [serverId] in a background thread. */
  fun restart(project: Project, serverId: String) {
    ApplicationManager.getApplication().executeOnPooledThread {
      if (project.isDisposed) return@executeOnPooledThread
      val manager = LanguageServerManager.getInstance(project)
      manager.stop(serverId)
      manager.start(serverId)
    }
  }
}
