package dev.restudio.web.lsp

import com.google.gson.Gson
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.DocumentMatcher
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.ServerStatus
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import dev.restudio.core.lsp.TracingClientFeatures

/*
 * The HTML, CSS and JSON language servers of vscode-langservers-extracted. Each one runs with `--stdio`.
 * The LSP probe recorded the HTML and CSS servers with the default settings (cases html and css).
 * The servers do not format: the default initialization options turn the formatter off.
 */

/** Starts `vscode-html-language-server`. It also serves the CSS and the JavaScript in an HTML file. */
class HtmlServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider =
    connectionFor(project, listOf(toolCommand(project, "vscode-html-language-server"), "--stdio"))

  override fun createClientFeatures(): LSPClientFeatures = TracingClientFeatures()
}

/** Starts `vscode-css-language-server` for CSS, SCSS and Less files. */
class CssServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider =
    connectionFor(project, listOf(toolCommand(project, "vscode-css-language-server"), "--stdio"))

  override fun createClientFeatures(): LSPClientFeatures = TracingClientFeatures()
}

/** Starts `vscode-json-language-server`. Its client gives the schemas of [JsonSchemas]. */
class JsonServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider =
    connectionFor(project, listOf(toolCommand(project, "vscode-json-language-server"), "--stdio"))

  override fun createLanguageClient(project: Project): LanguageClientImpl = JsonLanguageClient(project)

  override fun createClientFeatures(): LSPClientFeatures = TracingClientFeatures()
}

/**
 * Sends the JSON settings with `workspace/didChangeConfiguration` when the server starts.
 *
 * The JSON server reads `json.schemas` and `json.validate.enable` only from this notification (`jsonServer.js`).
 * LSP4IJ sends the notification only after a settings change, so the client sends it itself.
 */
class JsonLanguageClient(project: Project) : LanguageClientImpl(project) {
  override fun createSettings(): Any = Gson().toJsonTree(JsonSchemas.settings())

  override fun handleServerStatusChanged(serverStatus: ServerStatus) {
    super.handleServerStatusChanged(serverStatus)
    if (serverStatus == ServerStatus.started) triggerChangeConfiguration()
  }
}

/**
 * The JSON Schema Store schemas for the usual files of a JavaScript project. The JSON server downloads an `https`
 * schema itself.
 */
object JsonSchemas {
  private val SCHEMAS: Map<String, List<String>> = mapOf(
    "https://json.schemastore.org/package.json" to listOf("package.json"),
    "https://json.schemastore.org/tsconfig.json" to listOf("tsconfig.json", "tsconfig.*.json"),
    "https://json.schemastore.org/jsconfig.json" to listOf("jsconfig.json", "jsconfig.*.json"),
    "https://json.schemastore.org/eslintrc.json" to listOf(".eslintrc", ".eslintrc.json"),
    "https://json.schemastore.org/prettierrc.json" to listOf(".prettierrc", ".prettierrc.json"),
    "https://json.schemastore.org/babelrc.json" to listOf(".babelrc", ".babelrc.json", "babel.config.json"),
    "https://json.schemastore.org/lerna.json" to listOf("lerna.json"),
    "https://json.schemastore.org/turbo.json" to listOf("turbo.json"),
    "https://json.schemastore.org/vercel.json" to listOf("vercel.json"),
    "https://json.schemastore.org/composer.json" to listOf("composer.json"),
  )

  fun settings(): Map<String, Any> = mapOf(
    "json" to mapOf(
      "validate" to mapOf("enable" to true),
      "schemas" to SCHEMAS.map { (url, fileMatch) -> mapOf("url" to url, "fileMatch" to fileMatch) },
    ),
  )

  /** Returns `true` for a JSON file that allows comments, for example `tsconfig.json`. */
  fun isJsonWithComments(fileName: String): Boolean =
    fileName.endsWith(".jsonc") || JSONC_NAMES.matches(fileName)

  private val JSONC_NAMES = Regex("""(tsconfig|jsconfig)(\..*)?\.json|\.eslintrc(\.json)?|\.babelrc(\.json)?|devcontainer\.json""")
}

/** A JSON file outside `node_modules` that does not allow comments. */
class JsonFileMatcher : DocumentMatcher {
  override fun match(file: VirtualFile, project: Project): Boolean =
    "/node_modules/" !in file.path && !JsonSchemas.isJsonWithComments(file.name)
}

/** A JSON file outside `node_modules` that allows comments: the language ID is `jsonc`. */
class JsonWithCommentsFileMatcher : DocumentMatcher {
  override fun match(file: VirtualFile, project: Project): Boolean =
    "/node_modules/" !in file.path && JsonSchemas.isJsonWithComments(file.name)
}
