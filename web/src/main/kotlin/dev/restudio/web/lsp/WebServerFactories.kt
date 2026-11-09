package dev.restudio.web.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.DocumentMatcher
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import dev.restudio.core.lsp.TracingClientFeatures
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import dev.restudio.core.sdk.ToolLocator
import dev.restudio.web.sdk.NodeSdk
import org.eclipse.lsp4j.services.LanguageServer
import java.nio.file.Path

internal fun connectionFor(project: Project, command: List<String>): StreamConnectionProvider = connection(project, command)

/** Finds [name] in `node_modules/.bin` of the project, then on the PATH. */
internal fun toolCommand(project: Project, name: String): String = NodeSdk.command(name, project.basePath)

private fun connection(project: Project, command: List<String>): StreamConnectionProvider {
  val root = project.basePath?.let { Path.of(it) }
  val commandLine = GeneralCommandLine(command).withEnvironment(NodeSdk.environment(root))
  root?.let { commandLine.withWorkingDirectory(it) }
  return OSProcessStreamConnectionProvider(commandLine)
}

/**
 * Starts the TypeScript server for TypeScript, JavaScript and the script part of Vue files.
 *
 * In a project with TypeScript 7 or later, the server is the `tsc --lsp` of the project (see [TypeScriptServer]).
 * Else it is vtsls. vtsls brings its own TypeScript. Its client loads `@vue/typescript-plugin` (see [VueBridge]).
 */
class VtslsServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider =
    connection(project, TypeScriptServer.projectTsc(project)?.let { listOf(it.toString(), "--lsp", "--stdio") }
                        ?: listOf(toolCommand(project, "vtsls"), "--stdio"))

  override fun createLanguageClient(project: Project): LanguageClientImpl =
    if (TypeScriptServer.projectTsc(project) != null) LanguageClientImpl(project) else VtslsLanguageClient(project)

  override fun createClientFeatures(): LSPClientFeatures = TracingClientFeatures()
}

/**
 * Selects the TypeScript server of a project.
 *
 * TypeScript 7 has a language server mode: `tsc --lsp --stdio` (LSP probe case tsgo-react). The mode has no tsserver
 * plugins. Thus a project with Vue keeps vtsls, because Vue needs `@vue/typescript-plugin`.
 */
object TypeScriptServer {
  /** The `tsc` of the project when the project has TypeScript 7 or later and no Vue, else `null`. */
  fun projectTsc(project: Project): Path? = project.basePath?.let { projectTsc(Path.of(it)) }

  fun projectTsc(root: Path): Path? {
    if (java.nio.file.Files.isDirectory(root.resolve("node_modules/vue"))) return null
    val major = NodeSdk.typeScriptMajorVersion(root) ?: return null
    if (major < 7) return null
    return NodeSdk.binDirectory(root).resolve("tsc").takeIf { java.nio.file.Files.isExecutable(it) }
  }
}

/** Starts the Vue language server. It sends its TypeScript work to vtsls through [VueLanguageClient]. */
class VueServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider {
    val command = listOf(ToolLocator.find("vue-language-server")?.toString() ?: "vue-language-server", "--stdio")
    return connection(project, StdioTee.wrap("restudio.vue", command))
  }

  override fun createLanguageClient(project: Project): LanguageClientImpl = VueLanguageClient(project)

  override fun createClientFeatures(): LSPClientFeatures = TracingClientFeatures()

  override fun getServerInterface(): Class<out LanguageServer> = VueLanguageServer::class.java
}

/**
 * Starts the Angular language server for the templates of an Angular project.
 *
 * `ngserver` looks for `@angular/language-service` in the `--ngProbeLocations` directories. The package is in the
 * server installation, so the list has its `node_modules` and the project `node_modules` (see the plan, phase 0).
 */
class AngularServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider {
    val ngserver = NodeSdk.findTool("ngserver", project.basePath?.let { Path.of(it) })
    val serverModules = ngserver?.toRealPath()?.parent?.parent?.resolve("node_modules")?.toString()
    val projectModules = project.basePath?.let { Path.of(it, "node_modules").toString() }
    return connection(project, listOf(
      ngserver?.toString() ?: "ngserver", "--stdio",
      "--tsProbeLocations", listOfNotNull(projectModules, serverModules).joinToString(","),
      "--ngProbeLocations", listOfNotNull(serverModules, projectModules).joinToString(","),
    ))
  }
}

/** Starts the Tailwind CSS language server. It finds the Tailwind 4 entry CSS file by itself. */
class TailwindServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider =
    connection(project, listOf(toolCommand(project, "tailwindcss-language-server"), "--stdio"))
}

/** Matches the files of a project that has [marker] in its root, for example `angular.json`. */
abstract class ProjectMarkerMatcher(private val extensions: Set<String>, private val marker: (VirtualFile) -> Boolean) : DocumentMatcher {
  override fun match(file: VirtualFile, project: Project): Boolean =
    file.extension in extensions && generateSequence(file.parent) { it.parent }.any(marker)
}

/** The HTML templates of an Angular project. */
class AngularTemplateMatcher : ProjectMarkerMatcher(setOf("html"), { it.findChild("angular.json") != null })

/** Returns `true` when the `package.json` of [directory] has `tailwindcss`. */
private fun hasTailwind(directory: VirtualFile): Boolean =
  directory.findChild("package.json")?.let { String(it.contentsToByteArray()).contains("\"tailwindcss\"") } == true

/** The HTML and CSS files of a project with `tailwindcss` in its `package.json`. */
class TailwindFileMatcher : ProjectMarkerMatcher(setOf("html", "css"), ::hasTailwind)

/**
 * The files of a Tailwind project that TextMate colors, each with its own LSP language ID. Tailwind LS reads the
 * language ID to find the class attributes (`className` in JSX, `class` in Vue).
 */
class TailwindTypeScriptReactMatcher : ProjectMarkerMatcher(setOf("tsx"), ::hasTailwind)

class TailwindJavaScriptReactMatcher : ProjectMarkerMatcher(setOf("jsx"), ::hasTailwind)

class TailwindVueMatcher : ProjectMarkerMatcher(setOf("vue"), ::hasTailwind)

/**
 * At the debug log level of `dev.restudio`, runs a server through `stdio-tee.mjs`. The script writes the raw bytes of
 * both directions to `<log directory>/restudio-lsp/<server id>.raw`. Else it returns [command] as it is.
 */
object StdioTee {
  private val LOG = com.intellij.openapi.diagnostic.logger<StdioTee>()

  fun wrap(serverId: String, command: List<String>): List<String> {
    if (!LOG.isDebugEnabled) return command
    val node = NodeSdk.node() ?: return command
    val directory = Path.of(com.intellij.openapi.application.PathManager.getLogPath(), "restudio-lsp")
    java.nio.file.Files.createDirectories(directory)
    val script = directory.resolve("stdio-tee.mjs")
    java.nio.file.Files.write(script, StdioTee::class.java.getResourceAsStream("/lsp/stdio-tee.mjs")!!.use { it.readBytes() })
    return listOf(node.toString(), script.toString(), directory.resolve("$serverId.raw").toString()) + command
  }
}
