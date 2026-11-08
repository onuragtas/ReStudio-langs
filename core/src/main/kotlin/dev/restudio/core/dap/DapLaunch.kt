package dev.restudio.core.dap

import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.redhat.devtools.lsp4ij.dap.DebugMode
import com.redhat.devtools.lsp4ij.dap.DebugServerWaitStrategy
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration

/**
 * The parts of an LSP4IJ debug adapter run configuration that a language plugin fills in.
 *
 * [readyPattern] is the adapter output that tells LSP4IJ the address and the port.
 * [launch] is the JSON of the DAP `launch` request.
 * [serverId] is the ID of a registered LSP4IJ debug adapter. LSP4IJ asks it which files take breakpoints.
 * When it is `null`, LSP4IJ sends no breakpoint to the adapter.
 */
data class DapLaunch(
  val adapterName: String,
  val command: String,
  val readyPattern: String,
  val workingDirectory: String,
  val file: String,
  val launch: String,
  val environment: Map<String, String> = emptyMap(),
  val serverId: String? = null,
) {
  fun applyTo(configuration: DAPRunConfiguration) {
    configuration.serverId = serverId
    configuration.serverName = adapterName
    configuration.command = command
    configuration.debugServerWaitStrategy = DebugServerWaitStrategy.TRACE
    configuration.debugServerReadyPattern = readyPattern
    configuration.debugMode = DebugMode.LAUNCH
    configuration.workingDirectory = workingDirectory
    configuration.file = file
    configuration.launchConfiguration = launch
    configuration.envData = EnvironmentVariablesData.create(environment, true)
  }

  /** Returns `true` when [configuration] already starts this launch. */
  fun matches(configuration: DAPRunConfiguration): Boolean =
    configuration.command == command && configuration.launchConfiguration == launch && configuration.serverId == serverId

  companion object {
    /** The ID of the LSP4IJ "Debug Adapter Protocol" run configuration type. */
    private const val DAP_CONFIGURATION_TYPE_ID = "DAPConfiguration"

    fun configurationFactory(): ConfigurationFactory =
      ConfigurationTypeUtil.findConfigurationType(DAP_CONFIGURATION_TYPE_ID)!!.configurationFactories.single()
  }
}
