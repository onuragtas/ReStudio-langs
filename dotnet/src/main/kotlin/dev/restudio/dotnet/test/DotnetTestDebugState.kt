package dev.restudio.dotnet.test

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.dap.DAPDebugProcess
import com.redhat.devtools.lsp4ij.dap.DebugAdapterManager
import com.redhat.devtools.lsp4ij.dap.DebugMode
import com.redhat.devtools.lsp4ij.dap.client.DAPClient
import com.redhat.devtools.lsp4ij.dap.configurations.DAPCommandLineState
import com.redhat.devtools.lsp4ij.dap.descriptors.DefaultDebugAdapterDescriptor
import com.redhat.devtools.lsp4ij.settings.ServerTrace
import dev.restudio.dotnet.DotnetBundle

/**
 * Attaches netcoredbg to a waiting test host.
 *
 * LSP4IJ has no mode for "start the adapter, then attach": its ATTACH mode connects to an adapter that already runs.
 * Thus the descriptor reports LAUNCH, so that LSP4IJ starts netcoredbg, but gives ATTACH to the DAP client,
 * so that the client sends `attach` with the process ID.
 */
object DotnetTestDebugState {
  fun create(configuration: DotnetTestRunConfiguration, options: RunConfigurationOptions, environment: ExecutionEnvironment, testHost: DotnetTestHost): DAPCommandLineState {
    val definition = DebugAdapterManager.getInstance().getDebugAdapterServerById(configuration.serverId)
                     ?: throw ExecutionException(DotnetBundle.message("netcoredbg.not.registered"))
    val descriptor = object : DefaultDebugAdapterDescriptor(options, environment, definition) {
      override fun getDapParameters(): Map<String, Any> = mapOf("type" to "coreclr", "request" to "attach", "processId" to testHost.processId)

      override fun getDebugMode(): DebugMode = DebugMode.LAUNCH

      override fun createClient(
        debugProcess: DAPDebugProcess,
        dapParameters: Map<String, Any>,
        isDebug: Boolean,
        debugMode: DebugMode,
        serverTrace: ServerTrace,
        parentClient: DAPClient?,
      ): DAPClient = super.createClient(debugProcess, dapParameters, isDebug, DebugMode.ATTACH, serverTrace, parentClient)

      override fun isDebuggableFile(file: VirtualFile, project: Project): Boolean = file.extension == "cs"
    }
    return object : DAPCommandLineState(descriptor, options, environment) {
      override fun startProcess(): ProcessHandler {
        val adapter = super.startProcess()
        // When the debug session ends, the test host must not wait for a debugger any longer.
        adapter.addProcessListener(object : ProcessListener {
          override fun processTerminated(event: ProcessEvent) {
            if (!testHost.handler.isProcessTerminated) testHost.handler.destroyProcess()
          }
        })
        return adapter
      }

      override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val result = super.execute(executor, runner)
        (result.executionConsole as? ConsoleView)?.let { console -> mirrorTestOutput(console, testHost) }
        return result
      }
    }
  }

  /**
   * Shows the `dotnet test` text in the debug console: first the text so far, then each new part.
   *
   * It prints from [DotnetTestHost.output] up to its current end. Thus no text is lost or printed two times,
   * also when new text comes while the listener is added.
   */
  private fun mirrorTestOutput(console: ConsoleView, testHost: DotnetTestHost) {
    var printed = 0
    fun flush() {
      val text = synchronized(testHost.output) {
        testHost.output.substring(printed).also { printed = testHost.output.length }
      }
      if (text.isNotEmpty()) console.print(text, ConsoleViewContentType.NORMAL_OUTPUT)
    }
    testHost.handler.addProcessListener(object : ProcessListener {
      override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) = flush()

      override fun processTerminated(event: ProcessEvent) = flush()
    })
    flush()
  }
}
