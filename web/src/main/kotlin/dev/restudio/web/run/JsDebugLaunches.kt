package dev.restudio.web.run

import dev.restudio.core.dap.DapLaunch
import dev.restudio.core.dap.jsonObject
import dev.restudio.web.debug.JsDebugDescriptorFactory
import dev.restudio.web.sdk.NodeSdk

/**
 * Builds the vscode-js-debug launches.
 *
 * js-debug runs as a TCP server. LSP4IJ puts a free port for `${port}` and waits for the ready line.
 * js-debug opens each Node process, also a child process of npm or a Vitest worker, as a child session with the
 * `startDebugging` request. The DAP probe recorded both cases (js-debug-npm and js-debug-vitest).
 */
object JsDebugLaunches {
  private const val READY_PATTERN = "Debug server listening at "

  /** Debugs a `pwa-node` launch. [launch] holds the target: a program, or a runtime with its arguments. */
  fun node(name: String, workingDirectory: String, file: String, launch: Map<String, Any>): DapLaunch? {
    val server = NodeSdk.jsDebugServer() ?: return null
    val node = NodeSdk.node() ?: return null
    val entries = mapOf("type" to "pwa-node", "name" to name, "request" to "launch", "cwd" to workingDirectory, "console" to "internalConsole") + launch
    return DapLaunch(
      adapterName = ADAPTER_NAME,
      command = "$node $server \${port} 127.0.0.1",
      readyPattern = READY_PATTERN,
      workingDirectory = workingDirectory,
      file = file,
      launch = jsonObject(*entries.toList().toTypedArray()),
      environment = NodeSdk.environment(),
      serverId = JsDebugDescriptorFactory.SERVER_ID,
    )
  }

  private const val ADAPTER_NAME = "js-debug"
}
