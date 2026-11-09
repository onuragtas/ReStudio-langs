package dev.restudio.dotnet.sdk

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/** Runs a short `dotnet` command and returns its output. Call it from a background thread only. */
object DotnetCli {
  /** Returns `null` when the `dotnet` command is not installed. */
  fun run(directory: Path, arguments: List<String>, timeoutMs: Int = DEFAULT_TIMEOUT_MS): ProcessOutput? {
    val dotnet = DotnetSdk.command() ?: return null
    val commandLine = GeneralCommandLine(listOf(dotnet.toString()) + arguments)
      .withWorkingDirectory(directory)
      .withEnvironment(DotnetSdk.environment())
      .withCharset(StandardCharsets.UTF_8)
    return CapturingProcessHandler(commandLine).runProcess(timeoutMs)
  }

  /** The first error lines of [output], for a message to the user. */
  fun errorSummary(output: ProcessOutput): String {
    val lines = (output.stdout + "\n" + output.stderr).lines().map { it.trim() }.filter { it.isNotEmpty() }
    val errors = lines.filter { it.contains("error", ignoreCase = true) }
    return (errors.ifEmpty { lines.takeLast(5) }).take(10).joinToString("\n")
  }

  private const val DEFAULT_TIMEOUT_MS = 2 * 60 * 1000
}
