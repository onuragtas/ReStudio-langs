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

/**
 * Runs Vitest tests with the project's Vitest. Run fills the test tree through the ReStudio reporter; Debug runs
 * Vitest under js-debug.
 */
class VitestRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) : DAPRunConfiguration(project, factory, name) {
  override fun getOptions(): VitestRunConfigurationOptions = super.getOptions() as VitestRunConfigurationOptions

  var testFile: String
    get() = options.testFile.orEmpty()
    set(value) {
      options.testFile = value
    }

  /** A full test name as [VitestEntryPointDetector] makes it, or empty for all the tests of [testFile]. */
  var testName: String
    get() = options.testName.orEmpty()
    set(value) {
      options.testName = value
    }

  /** The Vitest CLI script of the project. */
  fun vitestScript(): Path = Path.of(workingDirectory, "node_modules/vitest/vitest.mjs")

  /**
   * The Vitest arguments after the script. A suite name selects its tests: they start with the name and the separator.
   */
  fun vitestArguments(): List<String> = buildList {
    addAll(listOf("run", testFile))
    if (testName.isNotEmpty()) addAll(listOf("-t", "^" + escapeRegex(testName) + "($|" + escapeRegex(VitestEntryPointDetector.SEPARATOR) + ")"))
  }

  override fun canRun(executorId: String): Boolean = executorId != DefaultRunExecutor.EXECUTOR_ID && super.canRun(executorId)

  override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? =
    if (executor.id == DefaultRunExecutor.EXECUTOR_ID) VitestRunState(environment, this) else super.getState(executor, environment)

  companion object {
    /** Escapes the JavaScript regular expression characters. */
    fun escapeRegex(text: String): String = text.replace(Regex("""[.*+?^${'$'}{}()|\[\]\\/]"""), """\\$0""")
  }
}

class VitestRunConfigurationOptions : DAPRunConfigurationOptions() {
  var testFile: String? by string()
  var testName: String? by string()
}

class VitestRunState(environment: ExecutionEnvironment, private val configuration: VitestRunConfiguration) : CommandLineState(environment) {
  fun commandLine(): GeneralCommandLine {
    val node = NodeSdk.node() ?: throw ExecutionException(WebBundle.message("tool.not.found", "node"))
    val script = configuration.vitestScript()
    if (!Files.isRegularFile(script)) throw ExecutionException(WebBundle.message("vitest.not.installed", configuration.workingDirectory.orEmpty()))
    val arguments = configuration.vitestArguments() + listOf("--reporter=${reporter()}", "--includeTaskLocation")
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
    private const val FRAMEWORK_NAME = "Vitest"

    /** Copies the reporter from the plugin to the IDE system directory, because Vitest loads it from a file. */
    fun reporter(): Path {
      val target = Path.of(PathManager.getSystemPath(), "restudio", "vitest", "restudio-reporter.mjs")
      val content = VitestRunState::class.java.getResourceAsStream("/vitest/restudio-reporter.mjs")!!.use { it.readBytes() }
      if (!Files.isRegularFile(target) || !Files.readAllBytes(target).contentEquals(content)) {
        Files.createDirectories(target.parent)
        Files.write(target, content)
      }
      return target
    }
  }
}

class VitestConfigurationType : ConfigurationTypeBase(
  ID,
  WebBundle.message("vitest.configuration.type.name"),
  WebBundle.message("vitest.configuration.type.description"),
  AllIcons.Nodes.Test,
) {
  init {
    addFactory(object : ConfigurationFactory(this) {
      override fun getId(): String = ID

      override fun createTemplateConfiguration(project: Project) = VitestRunConfiguration(project, this, "")

      override fun getOptionsClass(): Class<out BaseState> = VitestRunConfigurationOptions::class.java
    })
  }

  companion object {
    const val ID: String = "RestudioVitest"

    fun getInstance(): VitestConfigurationType = ConfigurationTypeUtil.findConfigurationType(VitestConfigurationType::class.java)
  }
}

/** Creates a [VitestRunConfiguration] for a `describe`, `it` or `test` call. */
class VitestRunConfigurationProducer : LazyRunConfigurationProducer<VitestRunConfiguration>() {
  override fun getConfigurationFactory(): ConfigurationFactory = VitestConfigurationType.getInstance().configurationFactories.single()

  override fun setupConfigurationFromContext(configuration: VitestRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
    val (file, name, root) = testFor(context) ?: return false
    configuration.workingDirectory = root
    configuration.testFile = file
    configuration.testName = name
    configuration.name = WebBundle.message("vitest.configuration.name", name.substringAfterLast(VitestEntryPointDetector.SEPARATOR))
    val launch = mapOf("program" to configuration.vitestScript().toString(), "args" to configuration.vitestArguments())
    JsDebugLaunches.node(configuration.name, root, file, launch)?.applyTo(configuration)
    return true
  }

  override fun isConfigurationFromContext(configuration: VitestRunConfiguration, context: ConfigurationContext): Boolean {
    val (file, name) = testFor(context) ?: return false
    return configuration.testFile == file && configuration.testName == name
  }

  private fun testFor(context: ConfigurationContext): Triple<String, String, String>? {
    val (file, entryPoint) = ContextEntryPoints.find(context, VitestEntryPointDetector) { VitestEntryPointDetector.isTestFile(it.name) && !JsTestFrameworks.isJest(it) } ?: return null
    if (entryPoint.kind != EntryPoint.Kind.TEST) return null
    val root = NodeSdk.packageRoot(file) ?: return null
    return Triple(file.path, entryPoint.name, root.path)
  }
}
