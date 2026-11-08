package dev.restudio.dotnet.debug

import dev.restudio.core.dap.FileDebugAdapterFactory

/** Registers netcoredbg as the LSP4IJ debug adapter for C# files. The .NET launches set [SERVER_ID]. */
class NetcoredbgDescriptorFactory : FileDebugAdapterFactory(setOf("cs")) {
  companion object {
    /** The `id` of the `debugAdapterServer` entry in `plugin.xml`. */
    const val SERVER_ID: String = "restudio.netcoredbg"
  }
}
