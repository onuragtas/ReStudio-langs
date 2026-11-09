package dev.restudio.dotnet.wizard

import com.intellij.icons.AllIcons
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.GitNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.dsl.builder.Panel
import dev.restudio.dotnet.DotnetBundle
import dev.restudio.dotnet.project.DotnetModuleSync
import dev.restudio.dotnet.run.DotnetBuildBeforeRunTaskProvider
import dev.restudio.dotnet.sdk.DotnetCli
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon

/** A project template of `dotnet new`. [shortName] is the template name of the CLI. */
enum class DotnetTemplate(val shortName: String) {
  CONSOLE("console"),
  CLASS_LIBRARY("classlib"),
  XUNIT("xunit"),
}

/**
 * Adds ".NET" to File > New > Project.
 *
 * The wizard runs `dotnet new <template> -o <directory> --name <name>` after the project opens, in the background.
 * Then [DotnetModuleSync] gives the new `.csproj` a module.
 */
class DotnetNewProjectWizard : GeneratorNewProjectWizard {
  override val id: String = "restudio.dotnet"
  override val name: String = DotnetBundle.message("wizard.name")
  override val icon: Icon = AllIcons.Nodes.Module
  override val description: String = DotnetBundle.message("wizard.description")

  override fun createStep(context: WizardContext): NewProjectWizardStep =
    RootNewProjectWizardStep(context)
      .nextStep(::NewProjectWizardBaseStep)
      .nextStep(::GitNewProjectWizardStep)
      .nextStep(::TemplateStep)

  private class TemplateStep(private val parent: GitNewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {
    private val templateBox = ComboBox(CollectionComboBoxModel(DotnetTemplate.entries.toList(), DotnetTemplate.CONSOLE)).apply {
      renderer = textListCellRenderer { templateName(it) }
    }
    private val frameworkModel = CollectionComboBoxModel<String>()
    private val frameworkBox = ComboBox(frameworkModel)

    @Volatile
    private var template = DotnetTemplate.CONSOLE

    @Volatile
    private var framework: String? = null

    init {
      templateBox.addActionListener { template = templateBox.selectedItem as? DotnetTemplate ?: DotnetTemplate.CONSOLE }
      frameworkBox.addActionListener { framework = frameworkBox.selectedItem as? String }
      // `dotnet --list-sdks` starts a process. The wizard runs on the EDT, so the list comes from the background.
      ApplicationManager.getApplication().executeOnPooledThread {
        val frameworks = DotnetFrameworks.installed()
        ApplicationManager.getApplication().invokeLater({
          frameworkModel.replaceAll(frameworks)
          frameworkModel.selectedItem = frameworks.firstOrNull()
          framework = frameworks.firstOrNull()
        }, ModalityState.any())
      }
    }

    override fun setupUI(builder: Panel) {
      builder.row(DotnetBundle.message("wizard.template")) { cell(templateBox) }
      builder.row(DotnetBundle.message("wizard.framework")) {
        cell(frameworkBox).comment(DotnetBundle.message("wizard.framework.comment"))
      }
    }

    override fun setupProject(project: Project) {
      val directory = Path.of(parent.path).resolve(parent.name)
      val request = Request(template, parent.name, directory, framework)
      StartupManager.getInstance(project).runAfterOpened { create(project, request) }
    }

    private fun templateName(template: DotnetTemplate): String = when (template) {
      DotnetTemplate.CONSOLE -> DotnetBundle.message("wizard.template.console")
      DotnetTemplate.CLASS_LIBRARY -> DotnetBundle.message("wizard.template.classlib")
      DotnetTemplate.XUNIT -> DotnetBundle.message("wizard.template.xunit")
    }
  }

  private data class Request(val template: DotnetTemplate, val name: String, val directory: Path, val framework: String?)

  private companion object {
    const val TIMEOUT_MS = 5 * 60 * 1000

    fun create(project: Project, request: Request) {
      object : Task.Backgroundable(project, DotnetBundle.message("wizard.progress", request.name), false) {
        override fun run(indicator: ProgressIndicator) {
          Files.createDirectories(request.directory)
          val arguments = listOf("new", request.template.shortName, "-o", request.directory.toString(), "--name", request.name)
          val output = DotnetCli.run(request.directory, arguments, TIMEOUT_MS)
          when {
            output == null -> return notify(project, DotnetBundle.message("dotnet.not.found"))
            output.isTimeout || output.exitCode != 0 -> return notify(project, DotnetBundle.message("wizard.failed", DotnetCli.errorSummary(output)))
          }
          request.framework?.let { setFramework(request.directory.resolve("${request.name}.csproj"), it) }
          LocalFileSystem.getInstance().refreshNioFiles(listOf(request.directory), false, true, null)
          DotnetModuleSync.sync(project)
        }
      }.queue()
    }

    /**
     * Writes [framework] into the new project file.
     *
     * The templates of an SDK offer only the framework of that SDK. Thus the wizard creates the project with the newest
     * SDK and then changes `TargetFramework`.
     */
    fun setFramework(file: Path, framework: String) {
      if (!Files.isRegularFile(file)) return
      val text = Files.readString(file)
      val changed = text.replace(Regex("<TargetFramework>[^<]*</TargetFramework>"), "<TargetFramework>$framework</TargetFramework>")
      if (changed != text) Files.writeString(file, changed)
    }

    fun notify(project: Project, text: String) {
      NotificationGroupManager.getInstance().getNotificationGroup(DotnetBuildBeforeRunTaskProvider.NOTIFICATION_GROUP)
        .createNotification(DotnetBundle.message("wizard.failed.title"), text.replace("<", "&lt;").replace("\n", "<br>"), NotificationType.ERROR)
        .notify(project)
    }
  }
}

/** The target frameworks of the installed SDKs: `net10.0` for SDK 10.0.403. The newest comes first. */
object DotnetFrameworks {
  /** Runs `dotnet --list-sdks`. It blocks, so call it from a background thread only. */
  fun installed(): List<String> {
    val output = DotnetCli.run(Path.of(System.getProperty("user.home")), listOf("--list-sdks"), LIST_TIMEOUT_MS) ?: return emptyList()
    return if (output.exitCode == 0) parse(output.stdout) else emptyList()
  }

  /** Reads lines such as `10.0.403 [/opt/homebrew/Cellar/dotnet/10.0.403/libexec/sdk]`. */
  fun parse(output: String): List<String> =
    output.lines()
      .mapNotNull { Regex("""^(\d+)\.(\d+)\.""").find(it.trim())?.destructured }
      .map { (major, minor) -> major.toInt() to minor.toInt() }
      .distinct()
      .sortedWith(compareByDescending<Pair<Int, Int>> { it.first }.thenByDescending { it.second })
      .map { (major, minor) -> "net$major.$minor" }

  private const val LIST_TIMEOUT_MS = 30 * 1000
}
