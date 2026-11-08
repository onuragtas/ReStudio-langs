package dev.restudio.dotnet.debug

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
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration
import com.redhat.devtools.lsp4ij.dap.descriptors.DefaultDebugAdapterDescriptor
import com.redhat.devtools.lsp4ij.settings.ServerTrace
import dev.restudio.dotnet.DotnetBundle

/**
 * Attaches netcoredbg to a running .NET process.
 *
 * LSP4IJ has no mode for "start the adapter, then attach": its ATTACH mode connects to an adapter that already runs.
 * Thus the descriptor reports LAUNCH, so that LSP4IJ starts netcoredbg, but gives ATTACH to the DAP client,
 * so that the client sends `attach` with the process ID.
 */
object NetcoredbgAttachState {
  /**
   * The process to attach to. [companion] is an optional process that belongs to the session, for example the
   * `dotnet test` process of a waiting test host. The debug console shows its [companionOutput], and the end of the
   * session stops it.
   */
  class Target(val processId: Int, val companion: ProcessHandler? = null, val companionOutput: StringBuffer? = null)

  /**
   * The platform asks for the state before it runs the before-run steps (`AsyncProgramRunner.execute`).
   * A test host starts in such a step, so the state reads [target] only when the session starts:
   * for the DAP parameters, the adapter process and the console. [target] is called one time.
   */
  fun create(
    configuration: DAPRunConfiguration,
    options: RunConfigurationOptions,
    environment: ExecutionEnvironment,
    target: () -> Target,
  ): DAPCommandLineState {
    val resolved = lazy(target)
    val definition = DebugAdapterManager.getInstance().getDebugAdapterServerById(configuration.serverId)
                     ?: throw ExecutionException(DotnetBundle.message("netcoredbg.not.registered"))
    val descriptor = object : DefaultDebugAdapterDescriptor(options, environment, definition) {
      override fun getDapParameters(): Map<String, Any> = mapOf("type" to "coreclr", "request" to "attach", "processId" to resolved.value.processId)

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
        val companion = resolved.value.companion
        val adapter = super.startProcess()
        // When the debug session ends, a waiting test host must not wait any longer.
        if (companion != null) {
          adapter.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
              if (!companion.isProcessTerminated) companion.destroyProcess()
            }
          })
        }
        return adapter
      }

      override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val result = super.execute(executor, runner)
        val console = result.executionConsole as? ConsoleView
        val (companion, output) = resolved.value.let { it.companion to it.companionOutput }
        if (console != null && companion != null && output != null) mirrorOutput(console, companion, output)
        return result
      }
    }
  }

  /**
   * Shows the text of [companion] in the debug console: first the text so far, then each new part.
   *
   * It prints from [output] up to its current end. Thus no text is lost or printed two times,
   * also when new text comes while the listener is added.
   */
  private fun mirrorOutput(console: ConsoleView, companion: ProcessHandler, output: StringBuffer) {
    var printed = 0
    fun flush() {
      val text = synchronized(output) {
        output.substring(printed).also { printed = output.length }
      }
      if (text.isNotEmpty()) console.print(text, ConsoleViewContentType.NORMAL_OUTPUT)
    }
    companion.addProcessListener(object : ProcessListener {
      override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) = flush()

      override fun processTerminated(event: ProcessEvent) = flush()
    })
    flush()
  }
}
