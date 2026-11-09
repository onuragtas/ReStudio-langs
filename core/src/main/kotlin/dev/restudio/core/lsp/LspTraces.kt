package dev.restudio.core.lsp

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import dev.restudio.core.CoreBundle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Opens the LSP trace files that [TracingClientFeatures] writes. */
object LspTraces {
  /**
   * Opens the trace of [serverId] in an editor. When [serverId] is `null`, the user selects one of the trace files.
   *
   * It reads the file system in a background thread. When no trace exists, it tells the user how to turn on the trace.
   */
  fun open(project: Project, serverId: String? = null) {
    ApplicationManager.getApplication().executeOnPooledThread {
      val files = if (serverId != null) listOf(TracingClientFeatures.traceFile(serverId)).filter { Files.isRegularFile(it) } else traceFiles()
      ApplicationManager.getApplication().invokeLater({
        when {
          files.isEmpty() -> NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(CoreBundle.message("lsp.trace.none", TracingClientFeatures.traceDirectory().toString()), NotificationType.INFORMATION)
            .notify(project)
          files.size == 1 -> openFile(project, files.single())
          else -> JBPopupFactory.getInstance().createPopupChooserBuilder(files.map { it.name })
            .setTitle(CoreBundle.message("lsp.trace.choose"))
            .setItemChosenCallback { name -> files.firstOrNull { it.name == name }?.let { openFile(project, it) } }
            .createPopup()
            .showCenteredInCurrentWindow(project)
        }
      }, project.disposed)
    }
  }

  private fun traceFiles(): List<Path> {
    val directory = TracingClientFeatures.traceDirectory()
    if (!directory.isDirectory()) return emptyList()
    return directory.listDirectoryEntries("*.log").sortedByDescending { it.getLastModifiedTime() }
  }

  private fun openFile(project: Project, file: Path) {
    ApplicationManager.getApplication().executeOnPooledThread {
      val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file) ?: return@executeOnPooledThread
      ApplicationManager.getApplication().invokeLater({ OpenFileDescriptor(project, virtualFile).navigate(true) }, project.disposed)
    }
  }

  const val NOTIFICATION_GROUP: String = "ReStudio"
}

/** Help > Diagnostic Tools > Open LSP Trace. */
class OpenLspTraceAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    LspTraces.open(e.project ?: return)
  }
}
