package dev.restudio.php.run

import dev.restudio.core.dap.DapLaunch
import dev.restudio.core.dap.jsonObject
import dev.restudio.php.debug.PhpDebugDescriptorFactory
import dev.restudio.php.sdk.PhpSdk

/**
 * Builds the vscode-php-debug launches.
 *
 * php-debug listens for Xdebug on [XDEBUG_PORT] and starts PHP. It does not turn Xdebug on by itself, so the launch
 * gives the Xdebug settings as PHP arguments (recorded with the DAP probe, case php-debug).
 * The adapter speaks DAP on its standard streams, so the launch has no ready pattern.
 */
object PhpLaunches {
  const val XDEBUG_PORT: Int = 9003

  /**
   * Debugs `php [phpArgs] [program] [args]` in [workingDirectory]. [file] is the source file of the configuration.
   *
   * php-debug accepts a launch without a program. It puts the runtime arguments first, then the program, then the
   * program arguments (read in `phpDebug.js` of version 1.40.2). Thus the built-in web server gives `-S` in [phpArgs].
   */
  fun debug(program: String?, args: List<String>, workingDirectory: String, file: String, name: String, phpArgs: List<String> = emptyList()): DapLaunch? {
    val adapter = PhpSdk.phpDebugAdapter() ?: return null
    val node = PhpSdk.node() ?: return null
    val php = PhpSdk.php() ?: return null
    return DapLaunch(
      adapterName = ADAPTER_NAME,
      command = "$node $adapter",
      readyPattern = null,
      workingDirectory = workingDirectory,
      file = file,
      launch = jsonObject(
        *listOfNotNull(
          "type" to "php",
          "name" to name,
          "request" to "launch",
          program?.let { "program" to it },
          "args" to args,
          "cwd" to workingDirectory,
          "port" to XDEBUG_PORT,
          "runtimeExecutable" to php.toString(),
          "runtimeArgs" to listOf("-dxdebug.mode=debug", "-dxdebug.start_with_request=yes", "-dxdebug.client_port=$XDEBUG_PORT") + phpArgs,
          "externalConsole" to false,
        ).toTypedArray()
      ),
      environment = PhpSdk.environment(),
      serverId = PhpDebugDescriptorFactory.SERVER_ID,
    )
  }

  private const val ADAPTER_NAME = "php-debug"
}
