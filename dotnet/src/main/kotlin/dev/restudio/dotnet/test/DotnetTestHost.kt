package dev.restudio.dotnet.test

import com.intellij.execution.BeforeRunTask
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.util.Key
import dev.restudio.dotnet.DotnetBundle
import dev.restudio.dotnet.run.DotnetBuildBeforeRunTaskProvider
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A `dotnet test` process whose test host waits for a debugger.
 *
 * [output] keeps all the text of the process, so that the debug console can show the text from before it opened.
 */
class DotnetTestHost(val processId: Int, val handler: KillableColoredProcessHandler, val output: StringBuffer) {
  companion object {
    val KEY: Key<DotnetTestHost> = Key.create("RestudioDotnetTestHost")

    /** The test host prints `Process Id: 45825, Name: dotnet` when `VSTEST_HOST_DEBUG=1`. */
    private val PROCESS_ID = Regex("""Process Id:\s*(\d+)""")

    fun processId(text: CharSequence): Int? = PROCESS_ID.find(text)?.groupValues?.get(1)?.toIntOrNull()
  }
}

class DotnetTestHostBeforeRunTask : BeforeRunTask<DotnetTestHostBeforeRunTask>(DotnetTestHostBeforeRunTaskProvider.ID) {
  init {
    isEnabled = true
  }
}

/**
 * Starts `dotnet test` with `VSTEST_HOST_DEBUG=1` before a debug session, and waits for the test host process ID.
 *
 * The step runs on a background thread, so it can wait for the build. It does nothing for the Run executor.
 */
class DotnetTestHostBeforeRunTaskProvider : BeforeRunTaskProvider<DotnetTestHostBeforeRunTask>() {
  override fun getId(): Key<DotnetTestHostBeforeRunTask> = ID

  override fun getName(): String = DotnetBundle.message("test.host.task.name")

  override fun createTask(runConfiguration: RunConfiguration): DotnetTestHostBeforeRunTask? =
    if (runConfiguration is DotnetTestRunConfiguration) DotnetTestHostBeforeRunTask() else null

  override fun executeTask(context: DataContext, configuration: RunConfiguration, environment: ExecutionEnvironment, task: DotnetTestHostBeforeRunTask): Boolean {
    val testConfiguration = configuration as? DotnetTestRunConfiguration ?: return true
    if (environment.executor.id == DefaultRunExecutor.EXECUTOR_ID) return true
    val resultsDirectory = Files.createTempDirectory("restudio-trx")
    val handler = KillableColoredProcessHandler(testConfiguration.testCommandLine(resultsDirectory, hostDebug = true))
    val output = StringBuffer()
    val processId = CompletableFuture<Int?>()
    handler.addProcessListener(object : ProcessListener {
      override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        output.append(event.text)
        if (!processId.isDone) DotnetTestHost.processId(output)?.let { processId.complete(it) }
      }

      override fun processTerminated(event: ProcessEvent) {
        processId.complete(null)
        resultsDirectory.toFile().deleteRecursively()
      }
    })
    handler.startNotify()
    val id = try {
      processId.get(START_TIMEOUT_MINUTES, TimeUnit.MINUTES)
    }
    catch (_: TimeoutException) {
      null
    }
    if (id == null) {
      handler.destroyProcess()
      val details = output.lines().filter { it.contains("error", ignoreCase = true) }.take(10).joinToString("\n").ifEmpty { output.takeLast(2000).toString() }
      NotificationGroupManager.getInstance().getNotificationGroup(DotnetBuildBeforeRunTaskProvider.NOTIFICATION_GROUP)
        .createNotification(DotnetBundle.message("test.host.failed"), details, NotificationType.ERROR)
        .notify(environment.project)
      return false
    }
    environment.putUserData(DotnetTestHost.KEY, DotnetTestHost(id, handler, output))
    return true
  }

  companion object {
    val ID: Key<DotnetTestHostBeforeRunTask> = Key.create("RestudioDotnetTestHost")
    private const val START_TIMEOUT_MINUTES = 10L
  }
}
