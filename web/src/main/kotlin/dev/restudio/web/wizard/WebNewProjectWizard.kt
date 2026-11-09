package dev.restudio.web.wizard

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.icons.AllIcons
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.GitNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseData
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.GeneralModuleType
import com.intellij.openapi.module.ModuleTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.NioFiles
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.dsl.builder.Panel
import dev.restudio.core.sdk.ToolLocator
import dev.restudio.web.WebBundle
import dev.restudio.web.ide.PrettierFormattingService
import dev.restudio.web.sdk.NodeSdk
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon

/**
 * The "JavaScript / TypeScript" generator of File | New | Project. Each template runs the official CLI of its tool.
 * The project gets one module with the project directory as its content root.
 */
class WebNewProjectWizard : GeneratorNewProjectWizard {
  override val id: String = "restudio.web"

  override val name: String = WebBundle.message("wizard.name")

  override val icon: Icon = AllIcons.FileTypes.JavaScript

  override val description: String = WebBundle.message("wizard.description")

  override fun createStep(context: WizardContext): NewProjectWizardStep =
    RootNewProjectWizardStep(context)
      .nextStep(::NewProjectWizardBaseStep)
      .nextStep(::GitNewProjectWizardStep)
      .nextStep(::WebTemplateStep)
}

/** A project template. [arguments] are the npm arguments for the project name. */
enum class WebTemplate(private val titleKey: String, val arguments: (String) -> List<String>) {
  VITE_REACT("wizard.template.vite.react", { vite(it, "react-ts") }),
  VITE_VUE("wizard.template.vite.vue", { vite(it, "vue-ts") }),
  VITE_VANILLA("wizard.template.vite.vanilla", { vite(it, "vanilla-ts") }),
  NODE("wizard.template.node", { listOf("init", "-y") });

  val title: String get() = WebBundle.message(titleKey)

  /** A Vite template makes a new directory. `npm init` writes into the current directory. */
  val makesDirectory: Boolean get() = this != NODE
}

/**
 * The arguments of `npm create vite@latest`. `--yes` lets npm install create-vite without a question.
 * `--no-interactive` stops the create-vite questions (create-vite 9.3.4 makes the project with stdin closed).
 */
private fun vite(name: String, template: String): List<String> =
  listOf("create", "--yes", "vite@latest", name, "--", "--template", template, "--no-interactive")

class WebTemplateStep(parent: GitNewProjectWizardStep) : AbstractNewProjectWizardStep(parent), NewProjectWizardBaseData by parent {
  private val templateProperty = propertyGraph.property(WebTemplate.VITE_REACT)

  override fun setupUI(builder: Panel) {
    builder.row(WebBundle.message("wizard.template")) {
      segmentedButton(WebTemplate.entries) { text = it.title }.bind(templateProperty)
    }
  }

  override fun setupProject(project: Project) {
    val directory = Path.of(path, name)
    val template = templateProperty.get()
    val error = runWithProgress(project) { WebProjectGenerator.generate(template, name, directory) }
    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
    if (error != null) {
      NotificationGroupManager.getInstance().getNotificationGroup(PrettierFormattingService.NOTIFICATION_GROUP)
        .createNotification(WebBundle.message("wizard.failed", template.title), error, NotificationType.ERROR)
        .notify(project)
    }
    addModule(project, directory)
  }

  /** Runs [action] under a modal progress on the EDT, or directly on a background thread. */
  private fun runWithProgress(project: Project, action: () -> String?): String? {
    if (!ApplicationManager.getApplication().isDispatchThread) return action()
    var result: String? = null
    ProgressManager.getInstance().run(object : Task.Modal(project, WebBundle.message("wizard.progress"), false) {
      override fun run(indicator: ProgressIndicator) {
        result = action()
      }
    })
    return result
  }

  private fun addModule(project: Project, directory: Path) {
    val builder = ModuleTypeManager.getInstance().findByID(GeneralModuleType.TYPE_ID).createModuleBuilder()
    builder.name = name
    builder.moduleFilePath = directory.resolve(".idea").resolve("$name.iml").toString()
    builder.contentEntryPath = directory.toString()
    builder.commit(project)
  }
}

/** Runs the CLI of a [WebTemplate]. */
object WebProjectGenerator {
  private const val TIMEOUT_MS = 300_000

  /** Makes the project files in [directory]. Returns the error text, or `null` when the CLI succeeds. */
  fun generate(template: WebTemplate, name: String, directory: Path): String? {
    val npm = ToolLocator.find("npm") ?: return WebBundle.message("tool.not.found", "npm")
    Files.createDirectories(directory)
    // A Vite template needs an empty target. The project directory can already hold `.idea` or `.git`.
    // The temporary directory is next to the project, so a move stays on the same file system.
    val workDirectory = if (template.makesDirectory) Files.createTempDirectory(directory.parent, ".restudio-new-project") else directory
    try {
      val commandLine = GeneralCommandLine(listOf(npm.toString()) + template.arguments(name))
        .withWorkingDirectory(workDirectory)
        .withEnvironment(NodeSdk.environment())
        .withCharset(StandardCharsets.UTF_8)
      val handler = CapturingProcessHandler(commandLine)
      // No CLI question can get an answer, so the standard input closes at once.
      handler.processInput.close()
      val output = handler.runProcess(TIMEOUT_MS)
      if (output.isTimeout || output.exitCode != 0) return output.stderr.ifBlank { output.stdout }.takeLast(2000)
      if (template.makesDirectory) moveChildren(workDirectory.resolve(name), directory)
      return null
    }
    finally {
      if (template.makesDirectory) NioFiles.deleteRecursively(workDirectory)
    }
  }

  /** Moves each child of [source] into [target]. A child that is already in [target] stays where it is. */
  private fun moveChildren(source: Path, target: Path) {
    if (!Files.isDirectory(source)) return
    Files.list(source).use { children ->
      for (child in children.toList()) {
        val destination = target.resolve(child.fileName.toString())
        if (!Files.exists(destination)) Files.move(child, destination)
      }
    }
  }
}
