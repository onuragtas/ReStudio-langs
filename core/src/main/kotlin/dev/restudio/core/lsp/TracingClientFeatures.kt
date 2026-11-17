package dev.restudio.core.lsp

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.DefaultLauncherBuilder
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.services.LanguageServer
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Function

/**
 * Writes all the messages between the IDE and a language server to `<log directory>/restudio-lsp/<server id>.log`.
 *
 * It traces only when the `dev.restudio` log category is at the debug level (Help > Diagnostic Tools > Debug Log
 * Settings). Thus a user can send the real traffic of a problem without a debugger.
 *
 * A subclass can also change the messages in [filterMessage].
 */
open class TracingClientFeatures : LSPClientFeatures() {
  override fun <S : LanguageServer> createLauncherBuilder(): Launcher.Builder<S> {
    val builder = FilteringLauncherBuilder<S>(this)
    if (!LOG.isDebugEnabled) return builder
    val file = traceFile(serverDefinition.id)
    Files.createDirectories(file.parent)
    LOG.debug("Tracing ${serverDefinition.id} to $file")
    return builder.traceMessages(PrintWriter(Files.newBufferedWriter(file), true))
  }

  /**
   * Returns the message that goes on. The method sees the messages in both directions.
   *
   * It runs on the threads of the connection. It can change [message] in place.
   */
  open fun filterMessage(message: Message): Message = message

  companion object {
    private val LOG = logger<TracingClientFeatures>()

    /** The directory of the trace files. */
    fun traceDirectory(): Path = Path.of(PathManager.getLogPath(), "restudio-lsp")

    /** The trace file of the language server [serverId]. */
    fun traceFile(serverId: String): Path = traceDirectory().resolve("$serverId.log")
  }
}

/**
 * Applies [TracingClientFeatures.filterMessage] to all the messages.
 *
 * LSP4IJ sets its own message wrapper with [wrapMessages] after it gets the builder. A builder keeps only one wrapper,
 * so LSP4IJ would remove the filter. Thus this builder joins the two wrappers.
 */
class FilteringLauncherBuilder<S : LanguageServer>(features: TracingClientFeatures) : DefaultLauncherBuilder<S>(features) {
  private val filter = Function<MessageConsumer, MessageConsumer> { consumer ->
    MessageConsumer { message -> consumer.consume(features.filterMessage(message)) }
  }

  init {
    super.wrapMessages(filter)
  }

  override fun wrapMessages(wrapper: Function<MessageConsumer, MessageConsumer>): Launcher.Builder<S> =
    super.wrapMessages { consumer -> wrapper.apply(filter.apply(consumer)) }
}
