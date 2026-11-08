package dev.restudio.go.debug

import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptorFactory

/**
 * Registers Delve as an LSP4IJ debug adapter for Go files.
 *
 * LSP4IJ lets the user put a breakpoint in a file only when a registered debug adapter accepts the file.
 * The Go run configurations do not use this definition to start Delve. They give the command directly.
 * The LSP4IJ run configuration producer must not make a second configuration for a Go file. Thus
 * [prepareConfiguration] returns `false`.
 */
class DelveDescriptorFactory : DebugAdapterDescriptorFactory() {
  override fun isDebuggableFile(file: VirtualFile, project: Project): Boolean = file.extension == "go"

  override fun prepareConfiguration(configuration: RunConfiguration, file: VirtualFile, project: Project): Boolean = false
}
