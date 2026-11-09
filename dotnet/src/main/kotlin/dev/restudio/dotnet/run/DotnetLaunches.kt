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
   * [profile] is a `launchSettings.json` profile. Its arguments, environment and URLs go into the launch.
   */
  fun debug(project: DotnetProject, file: String, profile: LaunchProfile? = null): DapLaunch? {
    val targetPath = project.targetPath ?: return null
    val netcoredbg = DotnetSdk.netcoredbg() ?: return null
    val host = DotnetSdk.host() ?: return null
    val environment = DotnetSdk.environment()
    val programEnvironment = environment.filterKeys { it != "PATH" } + profile?.environment.orEmpty()
    // A profile path can hold an MSBuild macro such as $(ProjectDir). The plugin cannot expand it, so it uses the project directory.
    val cwd = profile?.workingDirectory?.takeIf { it.isNotBlank() && !it.contains("$(") }?.let { project.directory.resolve(it).normalize().toString() }
              ?: project.directory.toString()
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
        "args" to listOf(targetPath.toString()) + profile?.arguments.orEmpty(),
        "cwd" to cwd,
        "stopAtEntry" to false,
        "console" to "internalConsole",
        "env" to programEnvironment,
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
