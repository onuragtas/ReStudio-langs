package dev.restudio.core.lsp

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.LanguageServersRegistry
import com.redhat.devtools.lsp4ij.ServerStatus
import dev.restudio.core.CoreBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls
import kotlin.time.Duration.Companion.seconds

class LanguageServerStatusWidgetFactory : StatusBarWidgetFactory {
  override fun getId(): String = LanguageServerStatusWidget.ID

  override fun getDisplayName(): String = CoreBundle.message("lsp.widget.name")

  override fun createWidget(project: Project, scope: CoroutineScope): StatusBarWidget = LanguageServerStatusWidget(project, scope)
}

/**
 * Shows the state of the language servers of the project, for example `LSP 2/3`.
 *
 * The click opens a popup: open the trace of a server, or restart a server.
 * LSP4IJ gives no public status listener, so the widget reads [LanguageServerManager.getServerStatus] each two seconds
 * in a background coroutine. A server with the status [ServerStatus.none] did not start in this project. The widget does not count it.
 * With no started server, the widget shows `LSP 0/0`, so that the user can find it.
 */
class LanguageServerStatusWidget(private val project: Project, private val scope: CoroutineScope) :
  StatusBarWidget, StatusBarWidget.MultipleTextValuesPresentation {
  private data class Server(val id: String, val name: String, val status: ServerStatus)

  @Volatile
  private var servers: List<Server> = emptyList()

  override fun ID(): String = ID

  override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

  override fun install(statusBar: StatusBar) {
    scope.launch(Dispatchers.Default) {
      while (isActive) {
        val next = readServers()
        if (next != servers) {
          servers = next
          withContext(Dispatchers.EDT) { statusBar.updateWidget(ID) }
        }
        delay(2.seconds)
      }
    }
  }

  override fun getSelectedValue(): String? {
    val current = servers
    return CoreBundle.message("lsp.widget.text", current.count { it.status == ServerStatus.started }, current.size)
  }

  override fun getTooltipText(): String? {
    val current = servers
    if (current.isEmpty()) return CoreBundle.message("lsp.widget.empty")
    val builder = HtmlBuilder().append(CoreBundle.message("lsp.widget.name"))
    for (server in current) builder.br().append(HtmlChunk.text(CoreBundle.message("lsp.widget.server", server.name, statusText(server.status))))
    return builder.wrapWithHtmlBody().toString()
  }

  override fun getPopup(): JBPopup? {
    val current = servers
    if (current.isEmpty()) return null
    val group = DefaultActionGroup()
    for (server in current) {
      group.add(DumbAwareAction.create(CoreBundle.message("lsp.widget.open.trace", server.name, statusText(server.status))) {
        LspTraces.open(project, server.id)
      })
    }
    group.addSeparator()
    for (server in current) {
      group.add(DumbAwareAction.create(CoreBundle.message("lsp.widget.restart", server.name)) { restart(server.id) })
    }
    return JBPopupFactory.getInstance().createActionGroupPopup(
      CoreBundle.message("lsp.widget.name"), group, SimpleDataContext.getProjectContext(project),
      JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false,
    )
  }

  private fun restart(serverId: String) {
    ApplicationManager.getApplication().executeOnPooledThread {
      val manager = LanguageServerManager.getInstance(project)
      manager.stop(serverId)
      manager.start(serverId)
    }
  }

  private fun readServers(): List<Server> {
    if (project.isDisposed) return emptyList()
    val manager = LanguageServerManager.getInstance(project)
    return LanguageServersRegistry.getInstance().serverDefinitions
      .mapNotNull { definition ->
        val status = manager.getServerStatus(definition.id) ?: ServerStatus.none
        if (status == ServerStatus.none) null else Server(definition.id, definition.displayName, status)
      }
      .sortedBy { it.name }
  }

  companion object {
    const val ID: String = "dev.restudio.LanguageServerStatus"

    @Nls
    private fun statusText(status: ServerStatus): String = when (status) {
      ServerStatus.none -> CoreBundle.message("lsp.status.none")
      ServerStatus.checking_installed -> CoreBundle.message("lsp.status.checking.installed")
      ServerStatus.installing -> CoreBundle.message("lsp.status.installing")
      ServerStatus.installed -> CoreBundle.message("lsp.status.installed")
      ServerStatus.not_installed -> CoreBundle.message("lsp.status.not.installed")
      ServerStatus.starting -> CoreBundle.message("lsp.status.starting")
      ServerStatus.started -> CoreBundle.message("lsp.status.started")
      ServerStatus.stopping -> CoreBundle.message("lsp.status.stopping")
      ServerStatus.stopped -> CoreBundle.message("lsp.status.stopped")
    }
  }
}
