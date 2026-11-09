package dev.restudio.web.run

import dev.restudio.core.dap.DapLaunch
import dev.restudio.core.dap.jsonObject
import dev.restudio.web.debug.JsDebugDescriptorFactory
import dev.restudio.web.sdk.NodeSdk
import java.nio.file.Path

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
      environment = NodeSdk.environment(Path.of(workingDirectory)),
      serverId = JsDebugDescriptorFactory.SERVER_ID,
    )
  }

  /**
   * Debugs a `pwa-chrome` launch: js-debug starts Chrome with a new profile and opens [url]. [webRoot] maps the
   * script URLs to the source files. The DAP probe recorded the launch with a `file://` page: one child session,
   * a verified breakpoint, the variables, evaluate and step over work.
   */
  fun chrome(name: String, url: String, webRoot: String): DapLaunch? {
    val server = NodeSdk.jsDebugServer() ?: return null
    val node = NodeSdk.node() ?: return null
    val entries = listOf("type" to "pwa-chrome", "name" to name, "request" to "launch", "url" to url, "webRoot" to webRoot)
    return DapLaunch(
      adapterName = ADAPTER_NAME,
      command = "$node $server \${port} 127.0.0.1",
      readyPattern = READY_PATTERN,
      workingDirectory = webRoot,
      file = webRoot,
      launch = jsonObject(*entries.toTypedArray()),
      environment = NodeSdk.environment(),
      serverId = JsDebugDescriptorFactory.SERVER_ID,
    )
  }

  private const val ADAPTER_NAME = "js-debug"
}
