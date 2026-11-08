package dev.restudio.dotnet.debug

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.process.ProcessInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.xdebugger.attach.LocalAttachHost
import com.intellij.xdebugger.attach.XAttachDebugger
import com.intellij.xdebugger.attach.XAttachDebuggerProvider
import com.intellij.xdebugger.attach.XAttachHost
import dev.restudio.core.dap.DapLaunch
import dev.restudio.core.dap.jsonObject
import dev.restudio.dotnet.DotnetBundle
import dev.restudio.dotnet.sdk.DotnetSdk
import java.nio.file.Files
import java.nio.file.Path

/** Offers netcoredbg in "Attach to Process" for the local .NET processes. */
class DotnetAttachDebuggerProvider : XAttachDebuggerProvider {
  override fun isAttachHostApplicable(attachHost: XAttachHost): Boolean = attachHost is LocalAttachHost

  override fun getAvailableDebuggers(project: Project, attachHost: XAttachHost, processInfo: ProcessInfo, contextHolder: UserDataHolder): List<XAttachDebugger> =
    if (isDotnetProcess(processInfo)) listOf(NetcoredbgAttachDebugger) else emptyList()

  companion object {
    private val HOST_NAMES = setOf("dotnet", "dotnet.exe")

    /** The ID of the platform Debug executor. */
    private const val DEBUG_EXECUTOR_ID = "Debug"

    /**
     * A process runs .NET when its executable is the `dotnet` host, or an app host: an executable with
     * `<name>.dll` and `<name>.runtimeconfig.json` next to it. The platform calls this for each process, so it is fast.
     */
    fun isDotnetProcess(info: ProcessInfo): Boolean {
      if (info.executableName in HOST_NAMES) return true
      val executable = info.executableCannonicalPath.orElse(null) ?: return false
      val base = executable.removeSuffix(".exe")
      return Files.isRegularFile(Path.of("$base.dll")) && Files.isRegularFile(Path.of("$base.runtimeconfig.json"))
    }

    /** A temporary configuration that attaches to [info]. */
    fun createSettings(project: Project, info: ProcessInfo): RunnerAndConfigurationSettings {
      val netcoredbg = DotnetSdk.netcoredbg() ?: throw ExecutionException(DotnetBundle.message("netcoredbg.not.found"))
      val name = DotnetBundle.message("attach.configuration.name", info.executableDisplayName, info.pid)
      val settings = RunManager.getInstance(project).createConfiguration(name, DotnetAttachConfigurationType.getInstance().configurationFactories.single())
      val configuration = settings.configuration as DotnetAttachConfiguration
      DapLaunch(
        adapterName = "netcoredbg",
        command = "$netcoredbg --interpreter=vscode",
        readyPattern = null,
        workingDirectory = project.basePath ?: System.getProperty("user.home"),
        file = "",
        launch = jsonObject("type" to "coreclr", "request" to "attach", "processId" to info.pid),
        environment = DotnetSdk.environment(),
        serverId = NetcoredbgDescriptorFactory.SERVER_ID,
      ).applyTo(configuration)
      configuration.processId = info.pid
      settings.isTemporary = true
      return settings
    }
  }

  private object NetcoredbgAttachDebugger : XAttachDebugger {
    override fun getDebuggerDisplayName(): String = DotnetBundle.message("attach.debugger.name")

    override fun attachDebugSession(project: Project, attachHost: XAttachHost, processInfo: ProcessInfo) {
      val executor = ExecutorRegistry.getInstance().getExecutorById(DEBUG_EXECUTOR_ID) ?: throw ExecutionException(DotnetBundle.message("attach.debug.only"))
      ProgramRunnerUtil.executeConfiguration(createSettings(project, processInfo), executor)
    }
  }
}
