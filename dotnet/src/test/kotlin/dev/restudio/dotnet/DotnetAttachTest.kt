package dev.restudio.dotnet

import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.process.ProcessInfo
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.redhat.devtools.lsp4ij.dap.DebugMode
import com.redhat.devtools.lsp4ij.dap.configurations.DAPCommandLineState
import dev.restudio.dotnet.debug.DotnetAttachConfiguration
import dev.restudio.dotnet.debug.DotnetAttachDebuggerProvider
import dev.restudio.dotnet.debug.NetcoredbgDescriptorFactory
import dev.restudio.dotnet.sdk.DotnetSdk
import java.nio.file.Files
import java.nio.file.Path

class DotnetAttachTest : BasePlatformTestCase() {
  private val cliHost = Path.of("../testdata/dotnet/src/Shop.Cli/bin/Debug/net10.0/Shop.Cli").toAbsolutePath().normalize()

  fun `test the dotnet host and an app host are dotnet processes`() {
    assertTrue(DotnetAttachDebuggerProvider.isDotnetProcess(ProcessInfo(1, "dotnet exec x.dll", "dotnet", "exec x.dll")))
    if (Files.isRegularFile(cliHost)) {
      assertTrue(DotnetAttachDebuggerProvider.isDotnetProcess(ProcessInfo(2, cliHost.toString(), "Shop.Cli", "", cliHost.toString())))
    }
    val java = ProcessHandle.current().info().command().orElse("/usr/bin/java")
    assertFalse(DotnetAttachDebuggerProvider.isDotnetProcess(ProcessInfo(3, java, Path.of(java).fileName.toString(), "", java)))
  }

  fun `test the attach configuration sends attach with the process ID`() {
    if (DotnetSdk.netcoredbg() == null) return
    val settings = DotnetAttachDebuggerProvider.createSettings(project, ProcessInfo(4321, "dotnet exec x.dll", "dotnet", "exec x.dll"))
    assertTrue(settings.isTemporary)
    val configuration = settings.configuration as DotnetAttachConfiguration
    assertEquals(4321, configuration.processId)
    assertEquals(NetcoredbgDescriptorFactory.SERVER_ID, configuration.serverId)
    val debugExecutor = ExecutorRegistry.getInstance().getExecutorById("Debug") ?: return
    val environment = ExecutionEnvironmentBuilder.create(debugExecutor, configuration).build()
    val state = configuration.getState(debugExecutor, environment) as DAPCommandLineState
    // LSP4IJ starts netcoredbg in LAUNCH mode; the DAP client sends attach with these parameters.
    assertEquals(DebugMode.LAUNCH, state.debugMode)
    assertEquals(mapOf("type" to "coreclr", "request" to "attach", "processId" to 4321), state.dapParameters)
  }
}
