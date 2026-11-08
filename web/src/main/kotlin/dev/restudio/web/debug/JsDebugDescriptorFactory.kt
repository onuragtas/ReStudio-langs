package dev.restudio.web.debug

import dev.restudio.core.dap.FileDebugAdapterFactory

/** Registers vscode-js-debug as the LSP4IJ debug adapter for JavaScript and TypeScript files. */
class JsDebugDescriptorFactory : FileDebugAdapterFactory(setOf("js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts", "vue")) {
  companion object {
    /** The `id` of the `debugAdapterServer` entry in `plugin.xml`. */
    const val SERVER_ID: String = "restudio.js-debug"
  }
}
