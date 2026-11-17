package dev.restudio.php

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.restudio.core.lsp.FilteringLauncherBuilder
import dev.restudio.core.lsp.TracingClientFeatures
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.lang.reflect.Proxy
import java.util.Collections

class MessageFilterTest : BasePlatformTestCase() {
  fun `test the filter stays when LSP4IJ sets its own wrapper`() {
    val seen = Collections.synchronizedList(mutableListOf<String>())
    val features = object : TracingClientFeatures() {
      override fun filterMessage(message: Message): Message {
        if (message is NotificationMessage) seen += "filter:${message.method}"
        return message
      }
    }
    val output = ByteArrayOutputStream()
    val input = PipedInputStream(PipedOutputStream())
    val client = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(LanguageClient::class.java)) { _, _, _ -> null }
    // LSP4IJ calls wrapMessages after it gets the builder. The test does the same.
    // The test makes the builder directly. createLauncherBuilder also traces in the tests and needs a server definition.
    val launcher = FilteringLauncherBuilder<LanguageServer>(features)
      .setLocalService(client)
      .setRemoteInterface(LanguageServer::class.java)
      .setInput(input)
      .setOutput(output)
      .wrapMessages { consumer -> MessageConsumer { message -> seen += "lsp4ij"; consumer.consume(message) } }
      .create()

    launcher.remoteProxy.initialized(InitializedParams())

    assertEquals(listOf("lsp4ij", "filter:initialized"), seen.toList())
    assertTrue(output.toString(Charsets.UTF_8).contains("\"method\":\"initialized\""))
    input.close()
  }
}
