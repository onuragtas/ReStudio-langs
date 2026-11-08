package dev.restudio.php.test

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
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
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationOptions
import dev.restudio.core.entrypoints.ContextEntryPoints
import dev.restudio.core.entrypoints.EntryPoint
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
 * Runs PHPUnit tests. Run shows the `--teamcity` output in the test tree; Debug runs PHPUnit under Xdebug.
 *
 * `--teamcity` writes the name-based messages of the IntelliJ test tree, with `php_qn://` location hints
 * (recorded with the test probe, see the plan). Thus the console needs no translator.
 */
class PhpUnitRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) : DAPRunConfiguration(project, factory, name) {
  override fun getOptions(): PhpUnitRunConfigurationOptions = super.getOptions() as PhpUnitRunConfigurationOptions

  /** The test file. */
  var testFile: String
    get() = options.testFile.orEmpty()
    set(value) {
      options.testFile = value
    }

  /** One test method of [testFile], or empty for all its tests. */
  var testMethod: String
    get() = options.testMethod.orEmpty()
    set(value) {
      options.testMethod = value
    }

  /** The PHPUnit arguments after the script. A method filter also takes the data set rows of the method. */
  fun phpunitArguments(teamcity: Boolean): List<String> = buildList {
    if (teamcity) add("--teamcity")
    add(testFile)
    if (testMethod.isNotEmpty()) addAll(listOf("--filter", "/::$testMethod(\\s|$)/"))
  }

  fun phpunitScript(): String = Path.of(workingDirectory, "vendor/bin/phpunit").toString()

  override fun canRun(executorId: String): Boolean = executorId != DefaultRunExecutor.EXECUTOR_ID && super.canRun(executorId)

  override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? =
    if (executor.id == DefaultRunExecutor.EXECUTOR_ID) PhpUnitRunState(environment, this) else super.getState(executor, environment)
}

class PhpUnitRunConfigurationOptions : DAPRunConfigurationOptions() {
  var testFile: String? by string()
  var testMethod: String? by string()
}

class PhpUnitRunState(environment: ExecutionEnvironment, private val configuration: PhpUnitRunConfiguration) : CommandLineState(environment) {
  fun commandLine(): GeneralCommandLine {
    val php = PhpSdk.php() ?: throw ExecutionException(PhpBundle.message("php.not.found"))
    return GeneralCommandLine(listOf(php.toString(), configuration.phpunitScript()) + configuration.phpunitArguments(teamcity = true))
      .withWorkingDirectory(Path.of(configuration.workingDirectory))
      .withEnvironment(PhpSdk.environment())
      .withCharset(StandardCharsets.UTF_8)
  }

  override fun startProcess(): ProcessHandler = KillableColoredProcessHandler(commandLine()).also { ProcessTerminatedListener.attach(it) }

  override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
    val handler = startProcess()
    val console = TestConsoles.attach(FRAMEWORK_NAME, configuration, executor, handler, PhpQnLocator, idBased = false) { TeamCityOutput }
    return DefaultExecutionResult(console, handler, *createActions(console, handler, executor))
  }

  /** PHPUnit writes the TeamCity messages itself. */
  private object TeamCityOutput : TestEventTranslator {
    override fun translate(line: String): List<TestOutput>? = null
  }

  private companion object {
    const val FRAMEWORK_NAME = "PHPUnit"
  }
}

class PhpUnitConfigurationType : ConfigurationTypeBase(
  ID,
  PhpBundle.message("phpunit.configuration.type.name"),
  PhpBundle.message("phpunit.configuration.type.description"),
  AllIcons.Nodes.Test,
) {
  init {
    addFactory(object : ConfigurationFactory(this) {
      override fun getId(): String = ID

      override fun createTemplateConfiguration(project: Project) = PhpUnitRunConfiguration(project, this, "")

      override fun getOptionsClass(): Class<out BaseState> = PhpUnitRunConfigurationOptions::class.java
    })
  }

  companion object {
    const val ID: String = "RestudioPhpUnit"

    fun getInstance(): PhpUnitConfigurationType = ConfigurationTypeUtil.findConfigurationType(PhpUnitConfigurationType::class.java)
  }
}

/** Creates a [PhpUnitRunConfiguration] for one PHPUnit test method. */
class PhpUnitRunConfigurationProducer : LazyRunConfigurationProducer<PhpUnitRunConfiguration>() {
  override fun getConfigurationFactory(): ConfigurationFactory = PhpUnitConfigurationType.getInstance().configurationFactories.single()

  override fun setupConfigurationFromContext(configuration: PhpUnitRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
    val (file, method, root) = testFor(context) ?: return false
    configuration.testFile = file
    configuration.testMethod = method
    configuration.workingDirectory = root
    configuration.name = PhpBundle.message("phpunit.configuration.name", method)
    PhpLaunches.debug(configuration.phpunitScript(), configuration.phpunitArguments(teamcity = false), root, file, method)?.applyTo(configuration)
    return true
  }

  override fun isConfigurationFromContext(configuration: PhpUnitRunConfiguration, context: ConfigurationContext): Boolean {
    val (file, method) = testFor(context) ?: return false
    return configuration.testFile == file && configuration.testMethod == method
  }

  private fun testFor(context: ConfigurationContext): Triple<String, String, String>? {
    val (file, entryPoint) = ContextEntryPoints.find(context, PhpEntryPointDetector) { it.extension == "php" } ?: return null
    if (entryPoint.kind != EntryPoint.Kind.TEST) return null
    val root = PhpSdk.composerRoot(file) ?: return null
    return Triple(file.path, entryPoint.name.substringAfter("::"), root.path)
  }
}
