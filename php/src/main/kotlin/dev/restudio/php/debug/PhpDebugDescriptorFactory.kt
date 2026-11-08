package dev.restudio.php.debug

import dev.restudio.core.dap.FileDebugAdapterFactory

/** Registers vscode-php-debug as the LSP4IJ debug adapter for PHP files. The PHP launches set [SERVER_ID]. */
class PhpDebugDescriptorFactory : FileDebugAdapterFactory(setOf("php")) {
  companion object {
    /** The `id` of the `debugAdapterServer` entry in `plugin.xml`. */
    const val SERVER_ID: String = "restudio.php-debug"
  }
}
