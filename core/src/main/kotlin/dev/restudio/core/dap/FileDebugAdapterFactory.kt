package dev.restudio.core.dap

import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptorFactory
import com.redhat.devtools.lsp4ij.dap.descriptors.DefaultDebugAdapterDescriptor

/**
 * Registers a debug adapter for the files with one of [extensions].
 *
 * LSP4IJ asks a debug adapter about a file two times:
 * - The editor lets the user put a breakpoint only when [isDebuggableFile] is `true`.
 * - A debug session sends a breakpoint to the adapter only when its descriptor accepts the file.
 *   The descriptor comes from this factory when the run configuration has the `serverId` of this adapter.
 *
 * The adapter command still comes from each run configuration. The LSP4IJ run configuration producer must not
 * make a second configuration next to the language plugin one. Thus [prepareConfiguration] returns `false`.
 */
abstract class FileDebugAdapterFactory(private val extensions: Set<String>) : DebugAdapterDescriptorFactory() {
  override fun isDebuggableFile(file: VirtualFile, project: Project): Boolean = accepts(file)

  override fun prepareConfiguration(configuration: RunConfiguration, file: VirtualFile, project: Project): Boolean = false

  override fun createDebugAdapterDescriptor(options: RunConfigurationOptions, environment: ExecutionEnvironment): DebugAdapterDescriptor =
    object : DefaultDebugAdapterDescriptor(options, environment, serverDefinition) {
      override fun isDebuggableFile(file: VirtualFile, project: Project): Boolean = accepts(file)
    }

  private fun accepts(file: VirtualFile) = file.extension in extensions
}
