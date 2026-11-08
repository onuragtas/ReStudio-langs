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
import dev.restudio.dotnet.DotnetBundle
import dev.restudio.dotnet.debug.NetcoredbgAttachState
import dev.restudio.dotnet.project.DotnetProject
import dev.restudio.dotnet.run.DotnetLaunches
import dev.restudio.dotnet.run.CSharpEntryPointDetector
import dev.restudio.dotnet.sdk.DotnetSdk
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs .NET tests with `dotnet test` and shows the TRX results in the test tree.
 *
 * Debug starts `dotnet test` with `VSTEST_HOST_DEBUG=1` in [DotnetTestHostBeforeRunTaskProvider], and attaches
 * netcoredbg to the waiting test host through LSP4IJ (see [NetcoredbgAttachState]).
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

  /** The LSP4IJ debug runner asks this. Return `false` for Run, so that the default runner uses [getState]. */
  override fun canRun(executorId: String): Boolean = executorId != DefaultRunExecutor.EXECUTOR_ID && super.canRun(executorId)

  override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
    if (executor.id == DefaultRunExecutor.EXECUTOR_ID) return DotnetTestRunState(environment, this)
    val testHost = DotnetTestHost.take(environment) ?: throw ExecutionException(DotnetBundle.message("test.host.not.started"))
    return NetcoredbgAttachState.create(this, options, environment, testHost.processId, testHost.handler, testHost.output)
  }

  /**
   * Fills in the configuration for the tests that [filter] selects in [project]. [file] is the source of the tests.
   * The Debug executor needs the netcoredbg settings and the test host step; both are set here.
   */
  fun setUp(project: DotnetProject, file: String, filter: String, name: String) {
    DotnetLaunches.testAttach(project, file)?.applyTo(this)
    projectFile = project.file.toString()
    workingDirectory = project.directory.toString()
    testFilter = filter
    this.name = name
    beforeRunTasks = listOf(DotnetTestHostBeforeRunTask())
  }

  /**
   * The `dotnet test` command. It writes the TRX file `restudio.trx` to [resultsDirectory].
   * With [hostDebug], the test host waits for a debugger and prints its process ID.
   */
  fun testCommandLine(resultsDirectory: Path, hostDebug: Boolean = false): GeneralCommandLine {
    val dotnet = DotnetSdk.command() ?: throw ExecutionException(DotnetBundle.message("dotnet.not.found"))
    // The console logger gives a result row for each test as the run goes. The TRX file is the fallback.
    val commandLine = GeneralCommandLine(
      dotnet.toString(), "test", projectFile,
      "--logger", "console;verbosity=normal",
      "--logger", "trx;LogFileName=$TRX_NAME",
      "--results-directory", resultsDirectory.toString(),
    )
    if (testFilter.isNotEmpty()) commandLine.addParameters("--filter", testFilter)
    val environment = DotnetSdk.environment().toMutableMap()
    if (hostDebug) environment["VSTEST_HOST_DEBUG"] = "1"
    return commandLine
      .withWorkingDirectory(Path.of(workingDirectory))
      .withEnvironment(environment)
      .withCharset(StandardCharsets.UTF_8)
  }

  companion object {
    const val TRX_NAME: String = "restudio.trx"
  }
}

class DotnetTestRunConfigurationOptions : DAPRunConfigurationOptions() {
  var projectFile: String? by string()
  var testFilter: String? by string()
}

/** Finds a C# test from the hint `dotnettest://<project directory>#<Namespace.Class.Method>`. */
val DotnetTestLocator: DirectoryTestLocator = DirectoryTestLocator("dotnettest", CSharpEntryPointDetector, recursive = true) { it.endsWith(".cs") }

/**
 * Starts `dotnet test` and fills the test tree from the console result rows (see [DotnetConsoleTranslator]).
 * When the console has no result row, for example in another language, the TRX results fill the tree at the end.
 */
class DotnetTestRunState(environment: ExecutionEnvironment, private val configuration: DotnetTestRunConfiguration) : CommandLineState(environment) {
  fun commandLine(resultsDirectory: Path): GeneralCommandLine = configuration.testCommandLine(resultsDirectory)

  private val projectDirectory = Path.of(configuration.projectFile).parent.toString()

  /** The test console feeds each output line to it. */
  val translator: DotnetConsoleTranslator = DotnetConsoleTranslator { className, method -> DotnetTestLocator.hint(projectDirectory, "$className.$method") }

  public override fun startProcess(): KillableColoredProcessHandler {
    val resultsDirectory = Files.createTempDirectory("restudio-trx")
    val handler = object : KillableColoredProcessHandler(commandLine(resultsDirectory)) {
      override fun onOSProcessTerminated(exitCode: Int) {
        try {
          val trx = resultsDirectory.resolve(DotnetTestRunConfiguration.TRX_NAME)
          if (!translator.sawResults && Files.isRegularFile(trx)) {
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
    val console = TestConsoles.attach(FRAMEWORK_NAME, configuration, executor, handler, DotnetTestLocator) { translator }
    return DefaultExecutionResult(console, handler, *createActions(console, handler, executor))
  }

  private companion object {
    const val FRAMEWORK_NAME = "DotnetTest"
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
