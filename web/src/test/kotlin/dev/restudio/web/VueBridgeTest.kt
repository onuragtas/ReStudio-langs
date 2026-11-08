package dev.restudio.web

import com.google.gson.Gson
import com.google.gson.JsonParser
import dev.restudio.web.lsp.VueBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The message shapes come from the LSP probe trace of tools/lsp-probe/vue-bridge.mjs. */
class VueBridgeTest {
  private val recordedRequest = """[[1, "_vue:projectInfo", {"file": "/work/web/vue/src/App.vue", "needFileNameList": false}]]"""

  @Test
  fun `a recorded request and its response`() {
    val request = VueBridge.parseRequest(JsonParser.parseString(recordedRequest))!!
    assertEquals(1, request.id.asInt)
    assertEquals("_vue:projectInfo", request.command)
    assertEquals("/work/web/vue/src/App.vue", request.arguments!!.asJsonObject["file"].asString)
    val body = JsonParser.parseString("""{"configFileName": "/work/web/vue/tsconfig.app.json"}""")
    // Vue LS takes [[id, body]] on the wire. lsp4j adds the outer array, so the argument is [id, body].
    assertEquals("""[1,{"configFileName":"/work/web/vue/tsconfig.app.json"}]""", Gson().toJson(VueBridge.response(request.id, body)))
  }

  @Test
  fun `a request without the outer array also works`() {
    assertEquals("x", VueBridge.parseRequest(JsonParser.parseString("""[7, "x", null]"""))!!.command)
    assertNull(VueBridge.parseRequest(JsonParser.parseString("""{"id": 1}""")))
  }

  @Test
  fun `the request goes to vtsls as typescript tsserverRequest`() {
    val request = VueBridge.parseRequest(JsonParser.parseString(recordedRequest))!!
    val params = VueBridge.executeCommand(request)
    assertEquals("typescript.tsserverRequest", params.command)
    assertEquals("_vue:projectInfo", params.arguments[0])
    assertEquals(mapOf("isAsync" to true, "lowPriority" to true), params.arguments[2])
    assertEquals("x", VueBridge.body(JsonParser.parseString("""{"body": "x"}""").asJsonObject)!!.asString)
  }

  @Test
  fun `vtsls loads the Vue TypeScript plugin from the Vue LS installation`() {
    val settings = VueBridge.vtslsSettings() ?: return
    val plugin = settings["vtsls"].asJsonObject["tsserver"].asJsonObject["globalPlugins"].asJsonArray[0].asJsonObject
    assertEquals("@vue/typescript-plugin", plugin["name"].asString)
    assertEquals(VueBridge.vueLanguageServerDirectory().toString(), plugin["location"].asString)
  }
}
