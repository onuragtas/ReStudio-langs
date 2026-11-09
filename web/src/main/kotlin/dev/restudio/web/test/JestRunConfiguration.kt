package dev.restudio.web.test

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
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationOptions
import dev.restudio.core.entrypoints.ContextEntryPoints
import dev.restudio.core.entrypoints.EntryPoint
import dev.restudio.core.testing.FileLineLocator
import dev.restudio.core.testing.TestConsoles
import dev.restudio.core.testing.TestEventTranslator
import dev.restudio.core.testing.TestOutput
import dev.restudio.web.WebBundle
import dev.restudio.web.run.JsDebugLaunches
import dev.restudio.web.sdk.NodeSdk
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Selects the test runner of a package: Vitest when it is installed, else Jest when it is installed. */
object JsTestFrameworks {
  fun isJest(file: VirtualFile): Boolean {
    val root = NodeSdk.packageRoot(file) ?: return false
    return root.findFileByRelativePath("node_modules/vitest/package.json") == null &&
           root.findFileByRelativePath("node_modules/jest/package.json") != null
  }
}

/**
 * Runs Jest tests with the project's Jest. Run fills the test tree through the ReStudio reporter
 * (`restudio-reporter.cjs`). Debug runs Jest in one process under js-debug.
 */
class JestRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) : DAPRunConfiguration(project, factory, name) {
  override fun getOptions(): JestRunConfigurationOptions = super.getOptions() as JestRunConfigurationOptions

  var testFile: String
    get() = options.testFile.orEmpty()
    set(value) {
      options.testFile = value
    }

  /** A full test name as [JestEntryPointDetector] makes it, or empty for all the tests of [testFile]. */
  var testName: String
    get() = options.testName.orEmpty()
    set(value) {
      options.testName = value
    }

  /** The Jest CLI script of the project. */
  fun jestScript(): Path = Path.of(workingDirectory, "node_modules/jest/bin/jest.js")

  /**
   * The Jest arguments after the script. A suite name selects its tests: their full names start with the suite name
   * and a space. `--runTestsByPath` takes the file path as it is, not as a regular expression.
   */
  fun jestArguments(): List<String> = buildList {
    addAll(listOf("--runTestsByPath", testFile))
    if (testName.isNotEmpty()) {
      addAll(listOf("-t", "^" + VitestRunConfiguration.escapeRegex(testName) + "($|" + VitestRunConfiguration.escapeRegex(JestEntryPointDetector.SEPARATOR) + ")"))
    }
  }

  override fun canRun(executorId: String): Boolean = executorId != DefaultRunExecutor.EXECUTOR_ID && super.canRun(executorId)

  override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? =
    if (executor.id == DefaultRunExecutor.EXECUTOR_ID) JestRunState(environment, this) else super.getState(executor, environment)
}

class JestRunConfigurationOptions : DAPRunConfigurationOptions() {
  var testFile: String? by string()
  var testName: String? by string()
}

class JestRunState(environment: ExecutionEnvironment, private val configuration: JestRunConfiguration) : CommandLineState(environment) {
  fun commandLine(): GeneralCommandLine {
    val node = NodeSdk.node() ?: throw ExecutionException(WebBundle.message("tool.not.found", "node"))
    val script = configuration.jestScript()
    if (!Files.isRegularFile(script)) throw ExecutionException(WebBundle.message("jest.not.installed", configuration.workingDirectory.orEmpty()))
    val arguments = configuration.jestArguments() + listOf("--reporters=${reporter()}", "--testLocationInResults")
    val root = Path.of(configuration.workingDirectory)
    return GeneralCommandLine(listOf(node.toString(), script.toString()) + arguments)
      .withWorkingDirectory(root)
      .withEnvironment(NodeSdk.environment(root))
      .withCharset(StandardCharsets.UTF_8)
  }

  override fun startProcess(): ProcessHandler = KillableColoredProcessHandler(commandLine()).also { ProcessTerminatedListener.attach(it) }

  override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
    val handler = startProcess()
    val console = TestConsoles.attach(FRAMEWORK_NAME, configuration, executor, handler, FileLineLocator) { ReporterOutput }
    return DefaultExecutionResult(console, handler, *createActions(console, handler, executor))
  }

  /** The reporter writes the TeamCity messages itself. */
  private object ReporterOutput : TestEventTranslator {
    override fun translate(line: String): List<TestOutput>? = null
  }

  companion object {
    private const val FRAMEWORK_NAME = "Jest"

    /** Copies the reporter from the plugin to the IDE system directory, because Jest loads it from a file. */
    fun reporter(): Path {
      val target = Path.of(PathManager.getSystemPath(), "restudio", "jest", "restudio-reporter.cjs")
      val content = JestRunState::class.java.getResourceAsStream("/jest/restudio-reporter.cjs")!!.use { it.readBytes() }
      if (!Files.isRegularFile(target) || !Files.readAllBytes(target).contentEquals(content)) {
        Files.createDirectories(target.parent)
        Files.write(target, content)
      }
      return target
    }
  }
}

class JestConfigurationType : ConfigurationTypeBase(
  ID,
  WebBundle.message("jest.configuration.type.name"),
  WebBundle.message("jest.configuration.type.description"),
  AllIcons.Nodes.Test,
) {
  init {
    addFactory(object : ConfigurationFactory(this) {
      override fun getId(): String = ID

      override fun createTemplateConfiguration(project: Project) = JestRunConfiguration(project, this, "")

      override fun getOptionsClass(): Class<out BaseState> = JestRunConfigurationOptions::class.java
    })
  }

  companion object {
    const val ID: String = "RestudioJest"

    fun getInstance(): JestConfigurationType = ConfigurationTypeUtil.findConfigurationType(JestConfigurationType::class.java)
  }
}

/** Creates a [JestRunConfiguration] for a `describe`, `it` or `test` call in a Jest project. */
class JestRunConfigurationProducer : LazyRunConfigurationProducer<JestRunConfiguration>() {
  override fun getConfigurationFactory(): ConfigurationFactory = JestConfigurationType.getInstance().configurationFactories.single()

  override fun setupConfigurationFromContext(configuration: JestRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
    val (file, name, root) = testFor(context) ?: return false
    configuration.workingDirectory = root
    configuration.testFile = file
    configuration.testName = name
    configuration.name = WebBundle.message("jest.configuration.name", name)
    val launch = mapOf("program" to configuration.jestScript().toString(), "args" to configuration.jestArguments() + "--runInBand")
    JsDebugLaunches.node(configuration.name, root, file, launch)?.applyTo(configuration)
    return true
  }

  override fun isConfigurationFromContext(configuration: JestRunConfiguration, context: ConfigurationContext): Boolean {
    val (file, name) = testFor(context) ?: return false
    return configuration.testFile == file && configuration.testName == name
  }

  private fun testFor(context: ConfigurationContext): Triple<String, String, String>? {
    val (file, entryPoint) = ContextEntryPoints.find(context, JestEntryPointDetector) {
      JestEntryPointDetector.isTestFile(it.name) && JsTestFrameworks.isJest(it)
    } ?: return null
    if (entryPoint.kind != EntryPoint.Kind.TEST) return null
    val root = NodeSdk.packageRoot(file) ?: return null
    return Triple(file.path, entryPoint.name, root.path)
  }
}
