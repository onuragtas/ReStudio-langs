package dev.restudio.core.lsp

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageServer
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes all the messages between the IDE and a language server to `<log directory>/restudio-lsp/<server id>.log`.
 *
 * It traces only when the `dev.restudio` log category is at the debug level (Help > Diagnostic Tools > Debug Log
 * Settings). Thus a user can send the real traffic of a problem without a debugger.
 */
open class TracingClientFeatures : LSPClientFeatures() {
  override fun <S : LanguageServer> createLauncherBuilder(): Launcher.Builder<S> {
    val builder = super.createLauncherBuilder<S>()
    if (!LOG.isDebugEnabled) return builder
    val file = Path.of(PathManager.getLogPath(), "restudio-lsp", "${serverDefinition.id}.log")
    Files.createDirectories(file.parent)
    LOG.debug("Tracing ${serverDefinition.id} to $file")
    return builder.traceMessages(PrintWriter(Files.newBufferedWriter(file), true))
  }

  private companion object {
    val LOG = logger<TracingClientFeatures>()
  }
}
