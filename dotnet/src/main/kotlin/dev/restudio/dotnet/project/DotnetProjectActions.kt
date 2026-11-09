package dev.restudio.dotnet.project

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import dev.restudio.dotnet.DotnetBundle
import dev.restudio.dotnet.lsp.RoslynWorkspace
import dev.restudio.dotnet.run.DotnetBuildBeforeRunTaskProvider
import dev.restudio.dotnet.sdk.DotnetCli
import java.nio.file.Path

/** Runs `dotnet restore` for the solution, or for each project when there is no solution. */
class DotnetRestoreAction : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project?.basePath != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    restore(project)
  }

  companion object {
    fun restore(project: Project) {
      val basePath = project.basePath ?: return
      object : Task.Backgroundable(project, DotnetBundle.message("restore.progress"), true) {
        override fun run(indicator: ProgressIndicator) {
          val workspace = RoslynWorkspace.find(Path.of(basePath)) ?: return notify(project, DotnetBundle.message("restore.nothing"), NotificationType.INFORMATION)
          val targets = when (workspace) {
            is RoslynWorkspace.Solution -> listOf(workspace.file)
            is RoslynWorkspace.Projects -> workspace.files
          }
          val failures = mutableListOf<String>()
          for (target in targets) {
            indicator.checkCanceled()
            indicator.text2 = target.fileName.toString()
            val output = DotnetCli.run(target.parent, listOf("restore", target.toString()), RESTORE_TIMEOUT_MS)
              ?: return notify(project, DotnetBundle.message("dotnet.not.found"), NotificationType.ERROR)
            if (output.isTimeout || output.exitCode != 0) failures += "${target.fileName}: ${DotnetCli.errorSummary(output)}"
          }
          targets.forEach { DotnetMsbuild.invalidate(it) }
          LocalFileSystem.getInstance().refreshNioFiles(targets.map { it.parent }, true, true, null)
          if (failures.isEmpty()) notify(project, DotnetBundle.message("restore.done"), NotificationType.INFORMATION)
          else notify(project, DotnetBundle.message("restore.failed", failures.joinToString("\n")), NotificationType.ERROR)
        }
      }.queue()
    }

    private fun notify(project: Project, text: String, type: NotificationType) {
      NotificationGroupManager.getInstance().getNotificationGroup(DotnetBuildBeforeRunTaskProvider.NOTIFICATION_GROUP)
        .createNotification(text.replace("\n", "<br>"), type)
        .notify(project)
    }

    private const val RESTORE_TIMEOUT_MS = 10 * 60 * 1000
  }
}

/** Runs [DotnetModuleSync] again, for example after the user adds a project to the solution. */
class DotnetSyncModulesAction : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project?.basePath != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    object : Task.Backgroundable(project, DotnetBundle.message("sync.progress"), false) {
      override fun run(indicator: ProgressIndicator) = DotnetModuleSync.sync(project)
    }.queue()
  }
}
