package dev.restudio.php.wizard

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.icons.AllIcons
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.GitNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseData.Companion.baseData
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.ide.wizard.runAfterOpened
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.module.GeneralModuleType
import com.intellij.openapi.module.ModuleTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindText
import dev.restudio.php.PhpBundle
import dev.restudio.php.lsp.PhpClientFeatures
import dev.restudio.php.project.ComposerRoots
import dev.restudio.php.sdk.PhpSdk
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import javax.swing.Icon
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * File > New > Project > PHP: an empty Composer project or a Laravel project.
 *
 * The wizard makes the project directory and one module. After the project opens, a background task runs Composer:
 * `composer init` with a PSR-4 autoload for `src/`, or `composer create-project laravel/laravel`.
 */
class PhpNewProjectWizard : GeneratorNewProjectWizard {
  override val id: String = "restudio.php"

  override val name: String = PhpBundle.message("wizard.name")

  override val icon: Icon = AllIcons.Nodes.Module

  override val description: String = PhpBundle.message("wizard.description")

  override fun createStep(context: WizardContext): NewProjectWizardStep =
    RootNewProjectWizardStep(context)
      .nextStep(::NewProjectWizardBaseStep)
      .nextStep(::GitNewProjectWizardStep)
      .nextStep(::PhpProjectStep)
}

/** The kinds of PHP project that the wizard makes. */
enum class PhpProjectTemplate { COMPOSER, LARAVEL }

private class PhpProjectStep(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {
  private val templateProperty = propertyGraph.property(PhpProjectTemplate.COMPOSER)
  private val packageNameProperty = propertyGraph.lazyProperty { defaultPackageName() }

  init {
    baseData?.nameProperty?.let { name -> packageNameProperty.dependsOn(name, ::defaultPackageName) }
  }

  private fun defaultPackageName(): String {
    val slug = baseData?.name.orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "project" }
    return "app/$slug"
  }

  override fun setupUI(builder: Panel) {
    builder.apply {
      buttonsGroup(PhpBundle.message("wizard.template")) {
        row { radioButton(PhpBundle.message("wizard.template.composer"), PhpProjectTemplate.COMPOSER) }
        row { radioButton(PhpBundle.message("wizard.template.laravel"), PhpProjectTemplate.LARAVEL) }
      }.bind({ templateProperty.get() }, { templateProperty.set(it) })
      row(PhpBundle.message("wizard.package.name")) {
        textField()
          .bindText(packageNameProperty)
          .align(AlignX.FILL)
          .comment(PhpBundle.message("wizard.package.name.comment"))
          .validationOnApply { field -> if (PACKAGE_NAME.matches(field.text.trim())) null else error(PhpBundle.message("wizard.package.name.invalid")) }
      }
    }
  }

  override fun setupProject(project: Project) {
    val data = baseData ?: return
    val directory = Path.of(data.contentEntryPath)
    val builder = ModuleTypeManager.getInstance().findByID(GeneralModuleType.TYPE_ID).createModuleBuilder()
    builder.name = data.name
    builder.contentEntryPath = directory.toString()
    builder.moduleFilePath = directory.resolve("${data.name}.iml").toString()
    builder.commitModule(project, null)

    val template = templateProperty.get()
    val packageName = packageNameProperty.get().trim()
    runAfterOpened(project) { openedProject -> ComposerProjectTask(openedProject, directory, template, packageName).queue() }
  }

  private companion object {
    /** The package name rule of Composer: `vendor/name` in lower case. */
    val PACKAGE_NAME = Regex("""^[a-z0-9]([_.-]?[a-z0-9]+)*/[a-z0-9](([_.]|-{1,2})?[a-z0-9]+)*$""")
  }
}

/** Runs Composer in [directory] to fill the new project. Composer can download for minutes, so it is a background task. */
private class ComposerProjectTask(
  project: Project,
  private val directory: Path,
  private val template: PhpProjectTemplate,
  private val packageName: String,
) : Task.Backgroundable(project, PhpBundle.message("wizard.progress"), true) {
  override fun run(indicator: ProgressIndicator) {
    val composer = PhpSdk.composer()
    if (composer == null) {
      notifyError(PhpBundle.message("composer.not.found"))
      return
    }
    when (template) {
      PhpProjectTemplate.COMPOSER -> run(indicator, composer.toString(), "init", "--no-interaction", "--name", packageName, "--autoload", "src/")
      PhpProjectTemplate.LARAVEL -> createLaravel(indicator, composer.toString())
    }
    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
    if (!project.isDisposed) ComposerRoots.getInstance(project).request()
  }

  /**
   * `composer create-project` needs an empty target directory, but the project directory already holds `.idea`.
   * Thus Composer writes into a new child directory, and the task moves its files up.
   */
  private fun createLaravel(indicator: ProgressIndicator, composer: String): Boolean {
    val target = directory.resolve(LARAVEL_TEMPORARY)
    if (!run(indicator, composer, "create-project", "--no-interaction", "laravel/laravel", target.toString())) return false
    for (entry in target.listDirectoryEntries()) {
      val destination = directory.resolve(entry.name)
      when {
        !destination.exists() -> Files.move(entry, destination)
        entry.name == ".gitignore" -> {
          Files.write(destination, Files.readAllBytes(entry), StandardOpenOption.APPEND)
          Files.delete(entry)
        }
        !entry.isDirectory() && !destination.isDirectory() -> Files.move(entry, destination, StandardCopyOption.REPLACE_EXISTING)
      }
    }
    if (target.listDirectoryEntries().isEmpty()) Files.delete(target)
    return true
  }

  private fun run(indicator: ProgressIndicator, vararg command: String): Boolean {
    val commandLine = GeneralCommandLine(*command)
      .withWorkingDirectory(directory)
      .withEnvironment(PhpSdk.environment())
      .withCharset(StandardCharsets.UTF_8)
    indicator.text = commandLine.commandLineString
    val output = CapturingProcessHandler(commandLine).runProcessWithProgressIndicator(indicator)
    if (output.isCancelled) return false
    if (output.exitCode != 0) {
      notifyError(PhpBundle.message("composer.failed", output.exitCode, output.stderr.ifBlank { output.stdout }.trim().takeLast(2000)))
      return false
    }
    return true
  }

  private fun notifyError(message: String) {
    NotificationGroupManager.getInstance().getNotificationGroup(PhpClientFeatures.NOTIFICATION_GROUP)
      .createNotification(message, NotificationType.ERROR)
      .notify(project)
  }

  private companion object {
    const val LARAVEL_TEMPORARY = ".restudio-laravel"
  }
}
