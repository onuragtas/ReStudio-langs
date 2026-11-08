package dev.restudio.web.lsp

import com.google.gson.Gson
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.DocumentMatcher
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import dev.restudio.core.sdk.ToolLocator
import dev.restudio.web.sdk.NodeSdk

/**
 * Starts the ESLint language server of vscode-langservers-extracted for the projects that have ESLint installed.
 *
 * ESLint LS asks for its settings with `workspace/configuration` and an empty section. LSP4IJ answers an empty
 * section with all of [EslintLanguageClient.createSettings]. The settings come from the LSP probe (case eslint-vue-ts).
 */
class EslintServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider =
    connectionFor(project, listOf(ToolLocator.find("vscode-eslint-language-server")?.toString() ?: "vscode-eslint-language-server", "--stdio"))

  override fun createLanguageClient(project: Project): LanguageClientImpl = EslintLanguageClient(project)
}

class EslintLanguageClient(project: Project) : LanguageClientImpl(project) {
  override fun createSettings(): Any = Gson().toJsonTree(SETTINGS)

  companion object {
    /** The settings of the VS Code ESLint extension for a flat config project. */
    val SETTINGS: Map<String, Any?> = mapOf(
      "validate" to "on",
      "run" to "onType",
      "useFlatConfig" to true,
      "experimental" to emptyMap<String, Any>(),
      "problems" to emptyMap<String, Any>(),
      "rulesCustomizations" to emptyList<Any>(),
      "codeAction" to mapOf(
        "disableRuleComment" to mapOf("enable" to true, "location" to "separateLine"),
        "showDocumentation" to mapOf("enable" to true),
      ),
      "codeActionOnSave" to mapOf("enable" to false, "mode" to "all"),
      "format" to false,
      "quiet" to false,
      "onIgnoredFiles" to "off",
      "options" to emptyMap<String, Any>(),
      "nodePath" to null,
      "workingDirectory" to mapOf("mode" to "auto"),
    )
  }
}

/**
 * Matches the files with one of [extensions] in a project that has ESLint in its `node_modules`.
 * ESLint LS reads the language ID of a file, so each language ID has its own matcher and mapping.
 */
abstract class EslintFileMatcher(private val extensions: Set<String>) : DocumentMatcher {
  override fun match(file: VirtualFile, project: Project): Boolean =
    file.extension in extensions && "/node_modules/" !in file.path &&
    NodeSdk.packageRoot(file)?.findFileByRelativePath("node_modules/eslint/package.json") != null
}

class EslintTypeScriptMatcher : EslintFileMatcher(setOf("ts", "mts", "cts"))

class EslintTypeScriptReactMatcher : EslintFileMatcher(setOf("tsx"))

class EslintJavaScriptMatcher : EslintFileMatcher(setOf("js", "mjs", "cjs"))

class EslintJavaScriptReactMatcher : EslintFileMatcher(setOf("jsx"))
