package dev.restudio.php.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import dev.restudio.php.PhpBundle
import dev.restudio.php.sdk.PhpSdk
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/** Runs `php [arguments]` in [workingDirectory] for the Run executor. */
class PhpProcessState(environment: ExecutionEnvironment, private val workingDirectory: String, private val arguments: () -> List<String>) :
  CommandLineState(environment) {
  fun commandLine(): GeneralCommandLine {
    val php = PhpSdk.php() ?: throw ExecutionException(PhpBundle.message("php.not.found"))
    return GeneralCommandLine(listOf(php.toString()) + arguments())
      .withWorkingDirectory(Path.of(workingDirectory))
      .withEnvironment(PhpSdk.environment())
      .withCharset(StandardCharsets.UTF_8)
  }

  override fun startProcess(): ProcessHandler = KillableColoredProcessHandler(commandLine()).also { ProcessTerminatedListener.attach(it) }
}
