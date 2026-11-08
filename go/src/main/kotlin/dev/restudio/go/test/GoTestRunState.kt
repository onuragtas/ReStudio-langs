package dev.restudio.go.test

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import dev.restudio.core.sdk.ToolLocator
import dev.restudio.core.testing.TestConsoles
import dev.restudio.go.GoBundle
import java.nio.charset.StandardCharsets

/** Starts `go test -json` in the package directory and shows the results in the test tree. */
class GoTestRunState(environment: ExecutionEnvironment, private val configuration: GoTestRunConfiguration) :
  CommandLineState(environment) {
  fun commandLine(): GeneralCommandLine {
    val go = ToolLocator.find("go") ?: throw ExecutionException(GoBundle.message("test.go.not.found"))
    val commandLine = GeneralCommandLine(go.toString(), "test", "-json")
    if (configuration.testPattern.isNotEmpty()) commandLine.addParameters("-run", configuration.testPattern)
    commandLine.addParameter(".")
    return commandLine
      .withWorkingDirectory(java.nio.file.Path.of(configuration.workingDirectory))
      .withEnvironment("PATH", ToolLocator.childPath())
      .withCharset(StandardCharsets.UTF_8)
  }

  override fun startProcess(): ProcessHandler {
    val handler = KillableColoredProcessHandler(commandLine())
    ProcessTerminatedListener.attach(handler)
    return handler
  }

  override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
    val handler = startProcess()
    val console = TestConsoles.attach(FRAMEWORK_NAME, configuration, executor, handler) { GoTestJsonTranslator() }
    return DefaultExecutionResult(console, handler, *createActions(console, handler, executor))
  }

  private companion object {
    const val FRAMEWORK_NAME = "GoTest"
  }
}
