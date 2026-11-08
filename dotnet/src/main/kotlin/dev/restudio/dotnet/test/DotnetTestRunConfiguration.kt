package dev.restudio.dotnet.test

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationOptions
import dev.restudio.core.testing.DirectoryTestLocator
import dev.restudio.core.testing.TestConsoles
import dev.restudio.core.testing.TestEventTranslator
import dev.restudio.core.testing.TestOutput
import dev.restudio.dotnet.DotnetBundle
import dev.restudio.dotnet.run.CSharpEntryPointDetector
import dev.restudio.dotnet.sdk.DotnetSdk
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs .NET tests with `dotnet test` and shows the TRX results in the test tree.
 *
 * Debug is not supported yet. It needs `VSTEST_HOST_DEBUG` and a netcoredbg attach. Thus [canRun] is `false` for
 * the LSP4IJ debug runner. The parent class keeps the DAP settings for that later step.
 */
class DotnetTestRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) : DAPRunConfiguration(project, factory, name) {
  override fun getOptions(): DotnetTestRunConfigurationOptions = super.getOptions() as DotnetTestRunConfigurationOptions

  /** The path of the test `.csproj` file. */
  var projectFile: String
    get() = options.projectFile.orEmpty()
    set(value) {
      options.projectFile = value
    }

  /** The value for `--filter`, for example `FullyQualifiedName=Shop.Tests.ProductTests.Adds`. Empty runs all the tests. */
  var testFilter: String
    get() = options.testFilter.orEmpty()
    set(value) {
      options.testFilter = value
    }

  override fun canRun(executorId: String): Boolean = false

  override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? =
    if (executor.id == DefaultRunExecutor.EXECUTOR_ID) DotnetTestRunState(environment, this) else null
}

class DotnetTestRunConfigurationOptions : DAPRunConfigurationOptions() {
  var projectFile: String? by string()
  var testFilter: String? by string()
}

/** Finds a C# test from the hint `dotnettest://<project directory>#<Namespace.Class.Method>`. */
val DotnetTestLocator: DirectoryTestLocator = DirectoryTestLocator("dotnettest", CSharpEntryPointDetector, recursive = true) { it.endsWith(".cs") }

/** Starts `dotnet test` with a TRX logger. When the process ends, it sends the TRX results to the test tree. */
class DotnetTestRunState(environment: ExecutionEnvironment, private val configuration: DotnetTestRunConfiguration) : CommandLineState(environment) {
  fun commandLine(resultsDirectory: Path): GeneralCommandLine {
    val dotnet = DotnetSdk.command() ?: throw ExecutionException(DotnetBundle.message("dotnet.not.found"))
    val commandLine = GeneralCommandLine(dotnet.toString(), "test", configuration.projectFile, "--logger", "trx;LogFileName=$TRX_NAME", "--results-directory", resultsDirectory.toString())
    if (configuration.testFilter.isNotEmpty()) commandLine.addParameters("--filter", configuration.testFilter)
    return commandLine
      .withWorkingDirectory(Path.of(configuration.workingDirectory))
      .withEnvironment(DotnetSdk.environment())
      .withCharset(StandardCharsets.UTF_8)
  }

  public override fun startProcess(): KillableColoredProcessHandler {
    val resultsDirectory = Files.createTempDirectory("restudio-trx")
    val projectDirectory = Path.of(configuration.projectFile).parent.toString()
    val handler = object : KillableColoredProcessHandler(commandLine(resultsDirectory)) {
      override fun onOSProcessTerminated(exitCode: Int) {
        try {
          val trx = resultsDirectory.resolve(TRX_NAME)
          if (Files.isRegularFile(trx)) {
            TrxReport.messages(Files.readString(trx)) { className, method -> DotnetTestLocator.hint(projectDirectory, "$className.$method") }
              .forEach { notifyTextAvailable(it.toString() + "\n", ProcessOutputTypes.STDOUT) }
          }
        }
        catch (e: Exception) {
          LOG.warn("Cannot read the TRX results in $resultsDirectory", e)
        }
        finally {
          resultsDirectory.toFile().deleteRecursively()
          super.onOSProcessTerminated(exitCode)
        }
      }
    }
    ProcessTerminatedListener.attach(handler)
    return handler
  }

  override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
    val handler = startProcess()
    // The console shows the dotnet test output as it is. The tree comes from the TRX lines at the end.
    val console = TestConsoles.attach(FRAMEWORK_NAME, configuration, executor, handler, DotnetTestLocator) { PlainOutput }
    return DefaultExecutionResult(console, handler, *createActions(console, handler, executor))
  }

  private object PlainOutput : TestEventTranslator {
    override fun translate(line: String): List<TestOutput>? = null
  }

  private companion object {
    const val FRAMEWORK_NAME = "DotnetTest"
    const val TRX_NAME = "restudio.trx"
    val LOG = logger<DotnetTestRunState>()
  }
}

class DotnetTestConfigurationType : ConfigurationTypeBase(
  ID,
  DotnetBundle.message("test.configuration.type.name"),
  DotnetBundle.message("test.configuration.type.description"),
  AllIcons.Nodes.Test,
) {
  init {
    addFactory(object : ConfigurationFactory(this) {
      override fun getId(): String = ID

      override fun createTemplateConfiguration(project: Project) = DotnetTestRunConfiguration(project, this, "")

      override fun getOptionsClass(): Class<out BaseState> = DotnetTestRunConfigurationOptions::class.java
    })
  }

  companion object {
    const val ID: String = "RestudioDotnetTest"

    fun getInstance(): DotnetTestConfigurationType = ConfigurationTypeUtil.findConfigurationType(DotnetTestConfigurationType::class.java)
  }
}
