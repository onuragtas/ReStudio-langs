package dev.restudio.dotnet.run

import com.intellij.execution.BeforeRunTask
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.util.Key
import dev.restudio.dotnet.DotnetBundle
import dev.restudio.dotnet.sdk.DotnetSdk
import java.nio.charset.StandardCharsets
import java.nio.file.Path

class DotnetBuildBeforeRunTask : BeforeRunTask<DotnetBuildBeforeRunTask>(DotnetBuildBeforeRunTaskProvider.ID) {
  init {
    isEnabled = true
  }
}

/**
 * Runs `dotnet build` before a debug session.
 *
 * netcoredbg starts the built assembly, so the build must be current. `dotnet run` builds by itself,
 * so the step does nothing for the Run executor.
 */
class DotnetBuildBeforeRunTaskProvider : BeforeRunTaskProvider<DotnetBuildBeforeRunTask>() {
  override fun getId(): Key<DotnetBuildBeforeRunTask> = ID

  override fun getName(): String = DotnetBundle.message("build.task.name")

  override fun createTask(runConfiguration: RunConfiguration): DotnetBuildBeforeRunTask? =
    if (runConfiguration is DotnetRunConfiguration) DotnetBuildBeforeRunTask() else null

  override fun executeTask(context: DataContext, configuration: RunConfiguration, environment: ExecutionEnvironment, task: DotnetBuildBeforeRunTask): Boolean {
    val runConfiguration = configuration as? DotnetRunConfiguration ?: return true
    if (environment.executor.id == DefaultRunExecutor.EXECUTOR_ID) return true
    val dotnet = DotnetSdk.command() ?: return fail(environment, DotnetBundle.message("dotnet.not.found"))
    val commandLine = GeneralCommandLine(dotnet.toString(), "build", runConfiguration.projectFile, "-nologo", "-v", "q")
      .withWorkingDirectory(Path.of(runConfiguration.workingDirectory))
      .withEnvironment(DotnetSdk.environment())
      .withCharset(StandardCharsets.UTF_8)
    val output = CapturingProcessHandler(commandLine).runProcess(BUILD_TIMEOUT_MS)
    if (output.isTimeout) return fail(environment, DotnetBundle.message("build.timeout"))
    if (output.exitCode != 0) return fail(environment, (output.stdout + output.stderr).lines().filter { it.contains("error") }.take(10).joinToString("\n"))
    return true
  }

  private fun fail(environment: ExecutionEnvironment, details: String): Boolean {
    NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
      .createNotification(DotnetBundle.message("build.failed"), details, NotificationType.ERROR)
      .notify(environment.project)
    return false
  }

  companion object {
    val ID: Key<DotnetBuildBeforeRunTask> = Key.create("RestudioDotnetBuild")
    const val NOTIFICATION_GROUP: String = "ReStudio .NET"
    private const val BUILD_TIMEOUT_MS = 10 * 60 * 1000
  }
}
