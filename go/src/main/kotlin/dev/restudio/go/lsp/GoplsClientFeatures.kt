package dev.restudio.go.lsp

import dev.restudio.core.lsp.TracingClientFeatures
import org.eclipse.lsp4j.ServerCapabilities

/**
 * The client features of gopls.
 *
 * gopls asks for `workspace/didCreateFiles` for `*.go`. LSP4IJ sends it before `workspace/didChangeWatchedFiles`, also
 * for a file that an other program writes, for example an agent in the terminal or `git checkout`. After this pair,
 * gopls v0.24.0 does not add the new file to its package until a restart. It then reports "undefined" for each name in
 * the file. With `workspace/didChangeWatchedFiles` alone, gopls adds the file. So the client removes the request for
 * `didCreate` before LSP4IJ reads the capabilities of the file operations.
 */
class GoplsClientFeatures : TracingClientFeatures() {
  override fun setServerCapabilities(serverCapabilities: ServerCapabilities) {
    serverCapabilities.workspace?.fileOperations?.didCreate = null
    super.setServerCapabilities(serverCapabilities)
  }
}
