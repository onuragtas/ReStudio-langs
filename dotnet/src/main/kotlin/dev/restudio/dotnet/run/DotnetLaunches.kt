package dev.restudio.dotnet.run

import dev.restudio.core.dap.DapLaunch
import dev.restudio.core.dap.jsonObject
import dev.restudio.dotnet.debug.NetcoredbgDescriptorFactory
import dev.restudio.dotnet.project.DotnetProject
import dev.restudio.dotnet.sdk.DotnetSdk

/** Builds the netcoredbg launch for a .NET program. */
object DotnetLaunches {
  /**
   * Debugs the Debug build output of [project] with netcoredbg.
   *
   * netcoredbg must start the real `dotnet` host with the assembly as its argument. With the Homebrew script it fails.
   * netcoredbg speaks DAP on its standard streams, so the launch has no ready pattern.
   */
  fun debug(project: DotnetProject, file: String): DapLaunch? {
    val targetPath = project.targetPath ?: return null
    val netcoredbg = DotnetSdk.netcoredbg() ?: return null
    val host = DotnetSdk.host() ?: return null
    val environment = DotnetSdk.environment()
    return DapLaunch(
      adapterName = ADAPTER_NAME,
      command = "$netcoredbg --interpreter=vscode",
      readyPattern = null,
      workingDirectory = project.directory.toString(),
      file = file,
      launch = jsonObject(
        "type" to "coreclr",
        "name" to project.name,
        "request" to "launch",
        "program" to host.toString(),
        "args" to listOf(targetPath.toString()),
        "cwd" to project.directory.toString(),
        "stopAtEntry" to false,
        "console" to "internalConsole",
        "env" to environment.filterKeys { it != "PATH" },
      ),
      environment = environment,
      serverId = NetcoredbgDescriptorFactory.SERVER_ID,
    )
  }

  /**
   * The netcoredbg settings for a test configuration. The attach target is known only at run time,
   * so `DotnetTestDebugState` gives the process ID.
   */
  fun testAttach(project: DotnetProject, file: String): DapLaunch? {
    val netcoredbg = DotnetSdk.netcoredbg() ?: return null
    return DapLaunch(
      adapterName = ADAPTER_NAME,
      command = "$netcoredbg --interpreter=vscode",
      readyPattern = null,
      workingDirectory = project.directory.toString(),
      file = file,
      launch = jsonObject("type" to "coreclr", "request" to "attach"),
      environment = DotnetSdk.environment(),
      serverId = NetcoredbgDescriptorFactory.SERVER_ID,
    )
  }

  private const val ADAPTER_NAME = "netcoredbg"
}
