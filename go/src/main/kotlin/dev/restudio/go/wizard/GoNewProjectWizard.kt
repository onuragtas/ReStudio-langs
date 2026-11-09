package dev.restudio.go.wizard

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.icons.AllIcons
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.GitNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseData.Companion.baseData
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.ide.wizard.newProjectWizardBaseStepWithoutGap
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.GeneralModuleType
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindText
import dev.restudio.core.sdk.ToolLocator
import dev.restudio.go.GoBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon

/** File > New > Project > Go: makes a Go module with `go mod init <module path>` and a `main.go` file. */
class GoNewProjectWizard : GeneratorNewProjectWizard {
  override val id: String = "restudio.go"
  override val name: String = GoBundle.message("wizard.name")
  override val icon: Icon = AllIcons.Nodes.Module
  override val description: String = GoBundle.message("wizard.description")

  override fun createStep(context: WizardContext): NewProjectWizardStep =
    RootNewProjectWizardStep(context)
      .nextStep(::newProjectWizardBaseStepWithoutGap)
      .nextStep(::GitNewProjectWizardStep)
      .nextStep(::GoModuleStep)
}

private class GoModuleStep(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {
  private val modulePathProperty = propertyGraph.lazyProperty { suggestModulePath() }

  init {
    baseData?.nameProperty?.let { nameProperty -> propertyGraph.dependsOn(modulePathProperty, nameProperty) { suggestModulePath() } }
  }

  private fun suggestModulePath(): String = GoModulePath.suggest(baseData?.name.orEmpty())

  override fun setupUI(builder: Panel) {
    builder.row(GoBundle.message("wizard.module.path.label")) {
      textField()
        .bindText(modulePathProperty)
        .align(AlignX.FILL)
        .comment(GoBundle.message("wizard.module.path.comment"))
        .validationOnApply { field ->
          if (GoModulePath.isValid(field.text.trim())) null else error(GoBundle.message("wizard.module.path.invalid"))
        }
    }
  }

  override fun setupProject(project: Project) {
    val data = baseData ?: return
    val directory = Path.of(data.contentEntryPath)
    val modulePath = modulePathProperty.get().trim()
    Files.createDirectories(directory)
    val mainFile = directory.resolve("main.go")
    if (!Files.exists(mainFile)) Files.writeString(mainFile, MAIN_GO, StandardCharsets.UTF_8)
    createModule(project, data.name, directory)
    project.service<GoWizardScope>().scope.launch {
      withBackgroundProgress(project, GoBundle.message("wizard.progress.init")) {
        withContext(Dispatchers.IO) { initModule(directory, modulePath) }
        val main = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(mainFile)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)?.refresh(false, true)
        if (main != null) withContext(Dispatchers.EDT) { OpenFileDescriptor(project, main).navigate(true) }
      }
    }
  }

  /** Makes one IDE module with [directory] as its content root, as the empty project generator does. */
  private fun createModule(project: Project, name: String, directory: Path) {
    WriteAction.runAndWait<RuntimeException> {
      val module = ModuleManager.getInstance(project).newModule(directory.resolve("$name.iml"), GeneralModuleType.TYPE_ID)
      val model = ModuleRootManager.getInstance(module).modifiableModel
      val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)
      if (root != null) model.addContentEntry(root) else model.addContentEntry(VfsUtilCore.pathToUrl(directory.toString()))
      model.inheritSdk()
      model.commit()
    }
  }

  /** Runs `go mod init`. Without `go`, writes a `go.mod` file with only the module line. */
  private fun initModule(directory: Path, modulePath: String) {
    if (Files.exists(directory.resolve("go.mod"))) return
    val go = ToolLocator.find("go")
    if (go != null) {
      val commandLine = GeneralCommandLine(go.toString(), "mod", "init", modulePath)
        .withWorkingDirectory(directory)
        .withEnvironment("PATH", ToolLocator.childPath())
        .withCharset(StandardCharsets.UTF_8)
      val output = CapturingProcessHandler(commandLine).runProcess(60_000)
      if (output.exitCode == 0) return
      LOG.warn("go mod init failed with exit code ${output.exitCode}: ${output.stderr}")
    }
    Files.writeString(directory.resolve("go.mod"), "module $modulePath\n", StandardCharsets.UTF_8)
  }

  private companion object {
    val LOG = logger<GoModuleStep>()

    const val MAIN_GO: String = "package main\n\nimport \"fmt\"\n\nfunc main() {\n\tfmt.Println(\"Hello, World!\")\n}\n"
  }
}

/** The checks and the default of a Go module path. */
object GoModulePath {
  private val pattern = Regex("""^[A-Za-z0-9._~\-]+(/[A-Za-z0-9._~\-]+)*$""")

  /** Returns `true` for a path such as `example.com/hello` or `hello`. */
  fun isValid(path: String): Boolean = pattern.matches(path) && path.split('/').none { it == "." || it == ".." }

  /** Makes a module path from a project name. */
  fun suggest(projectName: String): String {
    val name = projectName.trim().replace(Regex("""[^A-Za-z0-9._~\-]+"""), "-").trim('-')
    return "example.com/" + name.ifEmpty { "hello" }.lowercase()
  }
}

/** Owns the coroutines that the wizard starts for a new project. */
@Service(Service.Level.PROJECT)
internal class GoWizardScope(val scope: CoroutineScope)
