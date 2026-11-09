package dev.restudio.php.test

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.Location
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationOptions
import dev.restudio.core.entrypoints.ContextEntryPoints
import dev.restudio.core.entrypoints.EntryPoint
import dev.restudio.core.entrypoints.EntryPointLocation
import dev.restudio.core.testing.TestConsoles
import dev.restudio.core.testing.TestEventTranslator
import dev.restudio.core.testing.TestOutput
import dev.restudio.php.PhpBundle
import dev.restudio.php.run.PhpEntryPointDetector
import dev.restudio.php.run.PhpLaunches
import dev.restudio.php.sdk.PhpSdk
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Runs Pest tests. Run shows the `--teamcity` output in the test tree; Debug runs Pest under Xdebug.
 *
 * Pest writes name-based TeamCity messages with `pest_qn://<file>::<test name>` hints. The file path is relative to
 * the working directory (recorded with Pest 4). [PestLocator] resolves it.
 */
class PestRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) : DAPRunConfiguration(project, factory, name) {
  override fun getOptions(): PestRunConfigurationOptions = super.getOptions() as PestRunConfigurationOptions

  /** The test file. */
  var testFile: String
    get() = options.testFile.orEmpty()
    set(value) {
      options.testFile = value
    }

  /** One test or one `describe` block of [testFile], or empty for all its tests. */
  var testName: String
    get() = options.testName.orEmpty()
    set(value) {
      options.testName = value
    }

  fun pestArguments(teamcity: Boolean): List<String> = buildList {
    if (teamcity) add("--teamcity")
    add(testFile)
    if (testName.isNotEmpty()) addAll(listOf("--filter", PestEntryPointDetector.filter(testName, describe = testName.endsWith("`"))))
  }

  fun pestScript(): String = Path.of(workingDirectory, PEST_SCRIPT).toString()

  override fun canRun(executorId: String): Boolean = executorId != DefaultRunExecutor.EXECUTOR_ID && super.canRun(executorId)

  override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? =
    if (executor.id == DefaultRunExecutor.EXECUTOR_ID) PestRunState(environment, this) else super.getState(executor, environment)

  companion object {
    const val PEST_SCRIPT: String = "vendor/bin/pest"
  }
}

class PestRunConfigurationOptions : DAPRunConfigurationOptions() {
  var testFile: String? by string()
  var testName: String? by string()
}

class PestRunState(environment: ExecutionEnvironment, private val configuration: PestRunConfiguration) : CommandLineState(environment) {
  fun commandLine(): GeneralCommandLine {
    val php = PhpSdk.php() ?: throw ExecutionException(PhpBundle.message("php.not.found"))
    return GeneralCommandLine(listOf(php.toString(), configuration.pestScript()) + configuration.pestArguments(teamcity = true))
      .withWorkingDirectory(Path.of(configuration.workingDirectory))
      .withEnvironment(PhpSdk.environment())
      .withCharset(StandardCharsets.UTF_8)
  }

  override fun startProcess(): ProcessHandler = KillableColoredProcessHandler(commandLine()).also { ProcessTerminatedListener.attach(it) }

  override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
    val handler = startProcess()
    val locator = PestLocator(Path.of(configuration.workingDirectory))
    val console = TestConsoles.attach(FRAMEWORK_NAME, configuration, executor, handler, locator, idBased = false) { TeamCityOutput }
    return DefaultExecutionResult(console, handler, *createActions(console, handler, executor))
  }

  /** Pest writes the TeamCity messages itself. */
  private object TeamCityOutput : TestEventTranslator {
    override fun translate(line: String): List<TestOutput>? = null
  }

  private companion object {
    const val FRAMEWORK_NAME = "Pest"
  }
}

/** Finds a Pest test from its `pest_qn` hint: `<file>` for a file and `<file>::<test name>` for a test. */
class PestLocator(private val workingDirectory: Path) : SMTestLocator {
  override fun getLocation(protocol: String, path: String, project: Project, scope: GlobalSearchScope): List<Location<*>> {
    if (protocol != PROTOCOL) return emptyList()
    val filePath = path.substringBefore("::")
    val testName = path.substringAfter("::", "")
    val file = LocalFileSystem.getInstance().findFileByNioFile(workingDirectory.resolve(filePath)) ?: return emptyList()
    if (!scope.contains(file)) return emptyList()
    val psiFile = PsiManager.getInstance(project).findFile(file) ?: return emptyList()
    val entryPoint = PestEntryPointDetector.detect(psiFile.viewProvider.contents).firstOrNull { it.name == testName }
    val element = psiFile.findElementAt(entryPoint?.offset ?: 0) ?: return emptyList()
    return listOf(EntryPointLocation(element, entryPoint ?: EntryPoint(EntryPoint.Kind.TEST, testName, 0)))
  }

  companion object {
    const val PROTOCOL: String = "pest_qn"
  }
}

class PestConfigurationType : ConfigurationTypeBase(
  ID,
  PhpBundle.message("pest.configuration.type.name"),
  PhpBundle.message("pest.configuration.type.description"),
  AllIcons.Nodes.Test,
) {
  init {
    addFactory(object : ConfigurationFactory(this) {
      override fun getId(): String = ID

      override fun createTemplateConfiguration(project: Project) = PestRunConfiguration(project, this, "")

      override fun getOptionsClass(): Class<out BaseState> = PestRunConfigurationOptions::class.java
    })
  }

  companion object {
    const val ID: String = "RestudioPest"

    fun getInstance(): PestConfigurationType = ConfigurationTypeUtil.findConfigurationType(PestConfigurationType::class.java)
  }
}

/** Creates a [PestRunConfiguration] for one Pest test or `describe` block in a project that has `vendor/bin/pest`. */
class PestRunConfigurationProducer : LazyRunConfigurationProducer<PestRunConfiguration>() {
  override fun getConfigurationFactory(): ConfigurationFactory = PestConfigurationType.getInstance().configurationFactories.single()

  override fun setupConfigurationFromContext(configuration: PestRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
    val (file, name, root) = testFor(context) ?: return false
    configuration.testFile = file
    configuration.testName = name
    configuration.workingDirectory = root
    configuration.name = PhpBundle.message("pest.configuration.name", name)
    PhpLaunches.debug(configuration.pestScript(), configuration.pestArguments(teamcity = false), root, file, name)?.applyTo(configuration)
    return true
  }

  override fun isConfigurationFromContext(configuration: PestRunConfiguration, context: ConfigurationContext): Boolean {
    val (file, name) = testFor(context) ?: return false
    return configuration.testFile == file && configuration.testName == name
  }

  private fun testFor(context: ConfigurationContext): Triple<String, String, String>? {
    val (file, entryPoint) = ContextEntryPoints.find(context, PhpEntryPointDetector) { it.extension == "php" } ?: return null
    if (entryPoint.kind != EntryPoint.Kind.TEST || "::" in entryPoint.name) return null
    val root = PhpSdk.composerRoot(file)?.takeIf(::usesPest) ?: return null
    return Triple(file.path, entryPoint.name, root.path)
  }

  companion object {
    /** `true` when the Composer project in [root] has Pest installed. */
    fun usesPest(root: VirtualFile): Boolean = PhpSdk.vendorBinary(root, "pest") != null
  }
}
