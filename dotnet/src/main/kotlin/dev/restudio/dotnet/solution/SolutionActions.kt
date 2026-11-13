package dev.restudio.dotnet.solution

import com.intellij.execution.ExecutionException
import com.intellij.execution.RunContentExecutor
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.restudio.dotnet.DotnetBundle
import dev.restudio.dotnet.nuget.NuGetToolWindowFactory
import dev.restudio.dotnet.project.DotnetMsbuild
import dev.restudio.dotnet.run.DotnetBuildBeforeRunTaskProvider
import dev.restudio.dotnet.run.DotnetBuildErrorFilter
import dev.restudio.dotnet.run.DotnetRunConfiguration
import dev.restudio.dotnet.run.DotnetRunConfigurationProducer
import dev.restudio.dotnet.run.DotnetRunConfigurationType
import dev.restudio.dotnet.sdk.DotnetCli
import dev.restudio.dotnet.sdk.DotnetSdk
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent

private val PROJECT_EXTENSIONS = setOf("csproj", "fsproj", "vbproj")

/** The solution or the projects that the action works on: from the Solution view, or a selected project or solution file. */
internal fun solutionTargets(e: AnActionEvent): List<SolutionTarget> {
  e.getData(SolutionViewPane.TARGETS)?.takeIf { it.isNotEmpty() }?.let { return it }
  val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return emptyList()
  return when (file.extension?.lowercase()) {
    in PROJECT_EXTENSIONS -> listOf(SolutionTarget.Project(Path.of(file.path)))
    "sln", "slnx" -> listOf(SolutionTarget.Solution(Path.of(file.path)))
    else -> emptyList()
  }
}

private fun singleProject(e: AnActionEvent): Path? = (solutionTargets(e).singleOrNull() as? SolutionTarget.Project)?.file

private fun notify(project: Project, text: String, type: NotificationType) {
  NotificationGroupManager.getInstance().getNotificationGroup(DotnetBuildBeforeRunTaskProvider.NOTIFICATION_GROUP)
    .createNotification(text.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>"), type)
    .notify(project)
}

/** Runs `dotnet build`, `dotnet build --no-incremental` or `dotnet clean` for the selected solution or projects, in the Run tool window. */
abstract class DotnetBuildActionBase(private val arguments: List<String>, private val titleKey: String) : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null && solutionTargets(e).isNotEmpty()
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    for (target in solutionTargets(e)) run(project, target.file)
  }

  private fun run(project: Project, file: Path) {
    val dotnet = DotnetSdk.command() ?: return notify(project, DotnetBundle.message("dotnet.not.found"), NotificationType.ERROR)
    val commandLine = GeneralCommandLine(listOf(dotnet.toString()) + arguments.first() + file.toString() + arguments.drop(1) + "-nologo")
      .withWorkingDirectory(file.parent)
      .withEnvironment(DotnetSdk.environment())
      .withCharset(StandardCharsets.UTF_8)
    val handler = try {
      KillableColoredProcessHandler(commandLine)
    }
    catch (exception: ExecutionException) {
      return notify(project, exception.message.orEmpty(), NotificationType.ERROR)
    }
    ProcessTerminatedListener.attach(handler)
    handler.addProcessListener(object : ProcessListener {
      override fun processTerminated(event: ProcessEvent) {
        LocalFileSystem.getInstance().refreshNioFiles(listOf(file.parent), true, true, null)
      }
    })
    RunContentExecutor(project, handler)
      .withTitle(DotnetBundle.message(titleKey, file.fileName.toString()))
      .withFilter(DotnetBuildErrorFilter(project))
      .withActivateToolWindow(true)
      .withRerun { run(project, file) }
      .run()
  }
}

class DotnetBuildAction : DotnetBuildActionBase(listOf("build"), "solution.build.title")

class DotnetRebuildAction : DotnetBuildActionBase(listOf("build", "--no-incremental"), "solution.rebuild.title")

class DotnetCleanAction : DotnetBuildActionBase(listOf("clean"), "solution.clean.title")

/** Makes the selected executable project the startup project: it selects (or makes) the .NET run configuration of the project. */
class DotnetSetStartupProjectAction : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project = e.project
    val file = singleProject(e)
    val solutionProject = file?.let { project?.let(SolutionModel::getInstance)?.snapshot()?.projects?.get(it) }
    e.presentation.isVisible = project != null && file != null
    e.presentation.isEnabled = solutionProject?.project?.isExecutable == true && project != null && !isStartupProject(project, file)
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val file = singleProject(e) ?: return
    val solutionProject = SolutionModel.getInstance(project).snapshot()?.projects?.get(file) ?: return
    val runManager = RunManager.getInstance(project)
    val existing = runManager.getConfigurationSettingsList(DotnetRunConfigurationType.getInstance())
      .firstOrNull { (it.configuration as? DotnetRunConfiguration)?.projectFile == file.toString() }
    if (existing != null) {
      runManager.selectedConfiguration = existing
      return
    }
    ApplicationManager.getApplication().executeOnPooledThread {
      val factory = DotnetRunConfigurationType.getInstance().configurationFactories.single()
      val settings = runManager.createConfiguration(solutionProject.name, factory)
      val program = solutionProject.directory.resolve("Program.cs").takeIf { Files.isRegularFile(it) } ?: file
      DotnetRunConfigurationProducer.setUp(settings.configuration as DotnetRunConfiguration, solutionProject.project, program.toString())
      settings.name = settings.configuration.name
      ApplicationManager.getApplication().invokeLater({
        runManager.addConfiguration(settings)
        runManager.selectedConfiguration = settings
      }, project.disposed)
    }
  }
}

/** Opens the NuGet tool window for the selected project. */
class DotnetManageNuGetAction : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null && singleProject(e) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    NuGetToolWindowFactory.show(project, singleProject(e) ?: return)
  }
}

/** Opens the project file in the editor. The Solution view does not show it as a file. */
class DotnetEditProjectFileAction : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null && !e.getData(SolutionViewPane.TARGETS).isNullOrEmpty() && singleProject(e) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val file = LocalFileSystem.getInstance().findFileByNioFile(singleProject(e) ?: return) ?: return
    OpenFileDescriptor(project, file).navigate(true)
  }
}

/**
 * Shows the other projects of the solution with a check box each. A checked box is a `ProjectReference`.
 * The changes run `dotnet add reference` and `dotnet remove reference`.
 */
class DotnetAddProjectReferenceAction : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project = e.project
    e.presentation.isEnabledAndVisible = project != null && singleProject(e)?.let { SolutionModel.getInstance(project).snapshot()?.projects?.get(it) } != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val file = singleProject(e) ?: return
    val snapshot = SolutionModel.getInstance(project).snapshot() ?: return
    val current = snapshot.projects[file] ?: return
    val existing = current.msbuild?.projectReferences.orEmpty().toSet()
    // A project that references this project cannot be a reference of it: that makes a cycle.
    val candidates = snapshot.projects.values.filter { it.file != file && !references(snapshot, it.file, file) }.sortedBy { it.name.lowercase() }
    val dialog = ProjectReferenceDialog(project, candidates.map { it.file to it.name }, existing)
    if (!dialog.showAndGet()) return
    val selected = dialog.selected()
    val added = selected - existing
    val removed = existing.filter { it in candidates.map(SolutionProject::file) } - selected
    if (added.isEmpty() && removed.isEmpty()) return
    object : Task.Backgroundable(project, DotnetBundle.message("solution.reference.progress", current.name), false) {
      override fun run(indicator: ProgressIndicator) {
        val commands = listOfNotNull(
          added.takeIf { it.isNotEmpty() }?.let { listOf("add", file.toString(), "reference") + it.map(Path::toString) },
          removed.takeIf { it.isNotEmpty() }?.let { listOf("remove", file.toString(), "reference") + it.map(Path::toString) },
        )
        for (arguments in commands) {
          val output = DotnetCli.run(file.parent, arguments) ?: return notify(project, DotnetBundle.message("dotnet.not.found"), NotificationType.ERROR)
          if (output.isTimeout || output.exitCode != 0) notify(project, DotnetBundle.message("nuget.command.failed", DotnetCli.errorSummary(output)), NotificationType.ERROR)
        }
        DotnetMsbuild.invalidate(file)
        LocalFileSystem.getInstance().refreshNioFiles(listOf(file), true, false, null)
      }
    }.queue()
  }

  /** `true` when [from] references [to], directly or through other projects. */
  private fun references(snapshot: SolutionSnapshot, from: Path, to: Path, seen: MutableSet<Path> = HashSet()): Boolean {
    if (!seen.add(from)) return false
    val references = snapshot.projects[from]?.msbuild?.projectReferences.orEmpty()
    return to in references || references.any { references(snapshot, it, to, seen) }
  }

  private class ProjectReferenceDialog(project: Project, private val items: List<Pair<Path, String>>, checked: Set<Path>) : DialogWrapper(project) {
    private val list = CheckBoxList<Path>()

    init {
      title = DotnetBundle.message("solution.reference.title")
      for ((file, name) in items) list.addItem(file, name, file in checked)
      init()
    }

    override fun createCenterPanel(): JComponent = JBScrollPane(list).apply { preferredSize = JBUI.size(360, 300) }

    fun selected(): Set<Path> = items.map { it.first }.filter { list.isItemSelected(it) }.toSet()
  }
}
