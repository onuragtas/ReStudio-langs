package dev.restudio.web.lsp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import dev.restudio.core.sdk.ToolLocator
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.services.LanguageServer
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The bridge between the Vue language server and vtsls.
 *
 * Vue LS 3 does no TypeScript work itself. It sends `tsserver/request` with `[id, command, arguments]` and waits for
 * `tsserver/response` with `[id, body]`. vtsls runs the request with `@vue/typescript-plugin` loaded: the command
 * `typescript.tsserverRequest`. The LSP probe recorded the flow (tools/lsp-probe/vue-bridge.mjs).
 */
object VueBridge {
  const val VTSLS_SERVER_ID: String = "restudio.vtsls"

  /** The installation directory of Vue LS. vtsls resolves `@vue/typescript-plugin` from it. */
  fun vueLanguageServerDirectory(): Path? = ToolLocator.find("vue-language-server")?.toRealPath()?.parent?.parent

  /** The vtsls settings that load `@vue/typescript-plugin` into tsserver for `.vue` files, or `null` without Vue LS. */
  fun vtslsSettings(): JsonObject? {
    val location = vueLanguageServerDirectory() ?: return null
    val plugin = mapOf(
      "name" to "@vue/typescript-plugin",
      "location" to location.toString(),
      "languages" to listOf("vue"),
      "configNamespace" to "typescript",
      "enableForWorkspaceTypeScriptVersions" to true,
    )
    return Gson().toJsonTree(mapOf("vtsls" to mapOf("tsserver" to mapOf("globalPlugins" to listOf(plugin))))).asJsonObject
  }

  /** A request of Vue LS. The JSON-RPC library of Vue LS wraps the array in one more array. */
  data class Request(val id: JsonElement, val command: String, val arguments: JsonElement?)

  fun parseRequest(params: JsonElement): Request? {
    var array = params as? JsonArray ?: return null
    if (array.size() == 1 && array[0].isJsonArray) array = array[0].asJsonArray
    if (array.size() < 2) return null
    return Request(array[0], array[1].asString, array.get(2).takeIf { array.size() > 2 })
  }

  /** The `tsserver/response` parameters: `[[id, body]]`. */
  fun response(id: JsonElement, body: JsonElement?): List<Any?> = listOf(listOf(id, body))

  /** The arguments of `typescript.tsserverRequest`, as the Vue extension of VS Code sends them. */
  fun executeCommand(request: Request): ExecuteCommandParams =
    ExecuteCommandParams("typescript.tsserverRequest", listOf(request.command, request.arguments, mapOf("isAsync" to true, "lowPriority" to true)))

  /** Returns the `body` of a tsserver answer. */
  fun body(result: Any?): JsonElement? = when (result) {
    is JsonObject -> result.get("body")
    null -> null
    else -> Gson().toJsonTree(result).takeIf { it.isJsonObject }?.asJsonObject?.get("body")
  }
}

/** The Vue LS messages outside the LSP standard. */
interface VueLanguageServer : LanguageServer {
  @JsonNotification("tsserver/response")
  fun tsserverResponse(params: List<Any?>)
}

/** Forwards the `tsserver/request` notifications of Vue LS to the vtsls of the project. */
class VueLanguageClient(project: Project) : LanguageClientImpl(project) {
  @JsonNotification("tsserver/request")
  fun tsserverRequest(params: JsonElement) {
    val request = VueBridge.parseRequest(params) ?: return
    vtsls()
      .thenCompose { server -> server?.workspaceService?.executeCommand(VueBridge.executeCommand(request)) ?: CompletableFuture.completedFuture(null) }
      .exceptionally { error -> LOG.info("vtsls did not answer the Vue request ${request.command}", error); null }
      .thenAccept { result -> (languageServer as? VueLanguageServer)?.tsserverResponse(VueBridge.response(request.id, VueBridge.body(result))) }
  }

  /** The started vtsls of the project. It starts with the first `.vue` or TypeScript file, so wait a short time for it. */
  private fun vtsls(): CompletableFuture<LanguageServer?> = CompletableFuture.supplyAsync {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(VTSLS_WAIT_SECONDS)
    while (System.nanoTime() < deadline) {
      val wrapper = LanguageServiceAccessor.getInstance(project).startedServers.firstOrNull { it.serverDefinition.id == VueBridge.VTSLS_SERVER_ID }
      if (wrapper != null) return@supplyAsync wrapper.initializedServer.get(VTSLS_WAIT_SECONDS, TimeUnit.SECONDS)
      Thread.sleep(200)
    }
    null
  }

  private companion object {
    const val VTSLS_WAIT_SECONDS = 30L
    val LOG = logger<VueLanguageClient>()
  }
}

/** Gives vtsls the settings of [VueBridge.vtslsSettings]. */
class VtslsLanguageClient(project: Project) : LanguageClientImpl(project) {
  override fun createSettings(): Any? = VueBridge.vtslsSettings() ?: super.createSettings()
}
