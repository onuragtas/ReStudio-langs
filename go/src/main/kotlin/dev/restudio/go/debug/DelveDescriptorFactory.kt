package dev.restudio.go.debug

import dev.restudio.core.dap.FileDebugAdapterFactory

/** Registers Delve as the LSP4IJ debug adapter for Go files. The Go launches set [SERVER_ID]. */
class DelveDescriptorFactory : FileDebugAdapterFactory(setOf("go")) {
  companion object {
    /** The `id` of the `debugAdapterServer` entry in `plugin.xml`. */
    const val SERVER_ID: String = "restudio.delve"
  }
}
