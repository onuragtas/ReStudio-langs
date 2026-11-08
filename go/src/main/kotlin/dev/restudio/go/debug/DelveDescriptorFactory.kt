package dev.restudio.go.debug

import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptorFactory
import com.redhat.devtools.lsp4ij.dap.descriptors.DefaultDebugAdapterDescriptor

/**
 * Registers Delve as an LSP4IJ debug adapter for Go files.
 *
 * LSP4IJ asks a debug adapter about a file two times:
 * - The editor lets the user put a breakpoint only when [isDebuggableFile] is `true`.
 * - A debug session sends a breakpoint to Delve only when its descriptor accepts the file.
 *   The descriptor comes from this factory, because the Go launches set [SERVER_ID].
 *
 * The Delve command still comes from each run configuration. The LSP4IJ run configuration producer must not
 * make a second configuration for a Go file. Thus [prepareConfiguration] returns `false`.
 */
class DelveDescriptorFactory : DebugAdapterDescriptorFactory() {
  override fun isDebuggableFile(file: VirtualFile, project: Project): Boolean = isGoFile(file)

  override fun prepareConfiguration(configuration: RunConfiguration, file: VirtualFile, project: Project): Boolean = false

  override fun createDebugAdapterDescriptor(options: RunConfigurationOptions, environment: ExecutionEnvironment): DebugAdapterDescriptor =
    object : DefaultDebugAdapterDescriptor(options, environment, serverDefinition) {
      override fun isDebuggableFile(file: VirtualFile, project: Project): Boolean = isGoFile(file)
    }

  companion object {
    /** The `id` of the `debugAdapterServer` entry in `plugin.xml`. */
    const val SERVER_ID: String = "restudio.delve"

    private fun isGoFile(file: VirtualFile) = file.extension == "go"
  }
}
