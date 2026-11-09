package dev.restudio.dotnet.lsp

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.ServerStatus
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import dev.restudio.dotnet.DotnetBundle
import dev.restudio.dotnet.run.DotnetBuildBeforeRunTaskProvider
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import java.nio.file.Path

/**
 * Loads the solution into Roslyn LS and tells the user when a project does not load.
 *
 * Roslyn LS 5.13 shows the load and the `dotnet restore` steps as `$/progress`. LSP4IJ shows them in the status bar.
 * The server restores a project with missing packages by itself, because the client answers `true` for
 * `projects.dotnet_enable_automatic_restore`. It sends no restore request to the client
 * (LSP probe, case roslyn-solution-open on a copy without `obj` directories).
 * The server writes a load or restore failure as a `window/logMessage` of the type Error. The client collects
 * these messages until `workspace/projectInitializationComplete` and then shows them in one notification.
 */
class RoslynLanguageClient(project: Project) : LanguageClientImpl(project) {
  private val loadErrors = mutableListOf<String>()
  private var initialized = false

  override fun handleServerStatusChanged(serverStatus: ServerStatus) {
    super.handleServerStatusChanged(serverStatus)
    if (serverStatus != ServerStatus.started) return
    synchronized(loadErrors) {
      loadErrors.clear()
      initialized = false
    }
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

  override fun findSettings(section: String?): Any? =
    if (section == AUTOMATIC_RESTORE_SECTION) true else super.findSettings(section)

  override fun logMessage(message: MessageParams) {
    super.logMessage(message)
    if (message.type != MessageType.Error || !isProjectMessage(message.message)) return
    val text = message.message.substringAfter("] [LanguageServerProjectSystem] ", message.message)
    val showNow = synchronized(loadErrors) {
      if (initialized) true
      else {
        if (loadErrors.size < MAX_ERRORS) loadErrors += text
        false
      }
    }
    if (showNow) notifyErrors(listOf(text))
  }

  /** Roslyn LS sends this notification when it has loaded all the projects of the first `solution/open` or `project/open`. */
  @JsonNotification("workspace/projectInitializationComplete")
  fun projectInitializationComplete() {
    val errors = synchronized(loadErrors) {
      initialized = true
      loadErrors.toList().also { loadErrors.clear() }
    }
    if (errors.isNotEmpty()) notifyErrors(errors)
  }

  private fun notifyErrors(errors: List<String>) {
    if (project.isDisposed) return
    NotificationGroupManager.getInstance().getNotificationGroup(DotnetBuildBeforeRunTaskProvider.NOTIFICATION_GROUP)
      .createNotification(DotnetBundle.message("roslyn.load.failed"), errors.joinToString("<br>") { escape(it) }, NotificationType.WARNING)
      .notify(project)
  }

  private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  private companion object {
    val LOG = logger<RoslynLanguageClient>()
    const val AUTOMATIC_RESTORE_SECTION = "projects.dotnet_enable_automatic_restore"
    const val MAX_ERRORS = 10

    /** The server puts the name of the request and of the component before the text: `[solution/open] [LanguageServerProjectSystem] ...`. */
    fun isProjectMessage(text: String): Boolean =
      text.startsWith("[solution/open]") || text.startsWith("[project/open]") || text.contains("[LanguageServerProjectSystem]")
  }
}
