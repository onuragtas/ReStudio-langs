package dev.restudio.go.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.KillableProcess
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.NioFiles
import com.intellij.util.execution.ParametersListUtil
import dev.restudio.core.sdk.ToolLocator
import dev.restudio.go.GoBundle
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Builds the `main` package with `go build -o` in the package directory, then starts the program in its working directory.
 *
 * The console makes each `file.go:line:col` of the output a link.
 */
class GoApplicationRunState(environment: ExecutionEnvironment, private val configuration: GoApplicationRunConfiguration) :
  CommandLineState(environment) {
  init {
    addConsoleFilters(GoFileLineFilter(environment.project, configuration.packageDirectory))
  }

  /** Returns the `go build` command line that writes the program to [output]. */
  fun buildCommandLine(output: Path): GeneralCommandLine {
    val go = ToolLocator.find("go") ?: throw ExecutionException(GoBundle.message("test.go.not.found"))
    return GeneralCommandLine(go.toString(), "build", "-o", output.toString(), ".")
      .withWorkingDirectory(Path.of(configuration.packageDirectory))
      .withEnvironment(GoLaunches.childEnvironment(configuration.programEnvironment))
      .withCharset(StandardCharsets.UTF_8)
  }

  /** Returns the command line that starts the program in [output]. */
  fun programCommandLine(output: Path): GeneralCommandLine =
    GeneralCommandLine(output.toString())
      .withParameters(ParametersListUtil.parse(configuration.programArguments))
      .withWorkingDirectory(Path.of(configuration.programWorkingDirectory))
      .withEnvironment(GoLaunches.childEnvironment(configuration.programEnvironment))
      .withCharset(StandardCharsets.UTF_8)

  override fun startProcess(): ProcessHandler {
    if (configuration.packageDirectory.isEmpty()) throw ExecutionException(GoBundle.message("application.no.package"))
    val directory = Files.createTempDirectory("restudio-go-run")
    val name = Path.of(configuration.packageDirectory).fileName?.toString() ?: "main"
    val output = directory.resolve(if (SystemInfo.isWindows) "$name.exe" else name)
    val handler = BuildAndRunProcessHandler(buildCommandLine(output), programCommandLine(output)) {
      try {
        NioFiles.deleteRecursively(directory)
      }
      catch (_: java.io.IOException) {
        // The temporary directory stays. The OS removes it later.
      }
    }
    ProcessTerminatedListener.attach(handler)
    return handler
  }
}

/**
 * Starts [build]. When it ends with the exit code 0, starts [program].
 *
 * The handler shows the output of both processes and ends with the exit code of the last one. [cleanUp] runs at the end.
 */
private class BuildAndRunProcessHandler(
  private val build: GeneralCommandLine,
  private val program: GeneralCommandLine,
  private val cleanUp: () -> Unit,
) : ProcessHandler(), KillableProcess {
  @Volatile
  private var current: KillableColoredProcessHandler? = null

  @Volatile
  private var stopped = false

  private val input = object : OutputStream() {
    override fun write(b: Int) {
      current?.processInput?.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
      current?.processInput?.write(b, off, len)
    }

    override fun flush() {
      current?.processInput?.flush()
    }
  }

  override fun startNotify() {
    super.startNotify()
    start(build) { exitCode ->
      if (exitCode != 0 || stopped) finish(exitCode) else start(program, ::finish)
    }
  }

  private fun start(commandLine: GeneralCommandLine, onEnd: (Int) -> Unit) {
    val handler = try {
      KillableColoredProcessHandler(commandLine)
    }
    catch (e: ExecutionException) {
      notifyTextAvailable("${e.message}\n", ProcessOutputTypes.STDERR)
      finish(1)
      return
    }
    current = handler
    handler.addProcessListener(object : ProcessListener {
      override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        notifyTextAvailable(event.text, outputType)
      }

      override fun processTerminated(event: ProcessEvent) {
        onEnd(event.exitCode)
      }
    })
    handler.startNotify()
  }

  private fun finish(exitCode: Int) {
    current = null
    try {
      cleanUp()
    }
    finally {
      notifyProcessTerminated(exitCode)
    }
  }

  override fun destroyProcessImpl() {
    stopped = true
    val handler = current
    if (handler == null) notifyProcessTerminated(0) else handler.destroyProcess()
  }

  override fun detachProcessImpl() {
    stopped = true
    current?.detachProcess()
    notifyProcessDetached()
  }

  override fun detachIsDefault(): Boolean = false

  override fun getProcessInput(): OutputStream = input

  override fun canKillProcess(): Boolean = current?.canKillProcess() == true

  override fun killProcess() {
    current?.killProcess()
  }
}
