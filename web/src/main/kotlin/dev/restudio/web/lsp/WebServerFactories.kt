package dev.restudio.web.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.DocumentMatcher
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import dev.restudio.core.sdk.ToolLocator
import dev.restudio.web.sdk.NodeSdk
import org.eclipse.lsp4j.services.LanguageServer
import java.nio.file.Path

private fun connection(project: Project, command: List<String>): StreamConnectionProvider {
  val commandLine = GeneralCommandLine(command).withEnvironment(NodeSdk.environment())
  project.basePath?.let { commandLine.withWorkingDirectory(Path.of(it)) }
  return OSProcessStreamConnectionProvider(commandLine)
}

/**
 * Starts vtsls for TypeScript, JavaScript and the script part of Vue files. vtsls brings its own TypeScript when the
 * project has TypeScript 7. Its client loads `@vue/typescript-plugin` (see [VueBridge]).
 */
class VtslsServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider =
    connection(project, listOf(ToolLocator.find("vtsls")?.toString() ?: "vtsls", "--stdio"))

  override fun createLanguageClient(project: Project): LanguageClientImpl = VtslsLanguageClient(project)
}

/** Starts the Vue language server. It sends its TypeScript work to vtsls through [VueLanguageClient]. */
class VueServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider =
    connection(project, listOf(ToolLocator.find("vue-language-server")?.toString() ?: "vue-language-server", "--stdio"))

  override fun createLanguageClient(project: Project): LanguageClientImpl = VueLanguageClient(project)

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
    val ngserver = ToolLocator.find("ngserver")
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
    connection(project, listOf(ToolLocator.find("tailwindcss-language-server")?.toString() ?: "tailwindcss-language-server", "--stdio"))
}

/** Matches the files of a project that has [marker] in its root, for example `angular.json`. */
abstract class ProjectMarkerMatcher(private val extensions: Set<String>, private val marker: (VirtualFile) -> Boolean) : DocumentMatcher {
  override fun match(file: VirtualFile, project: Project): Boolean =
    file.extension in extensions && generateSequence(file.parent) { it.parent }.any(marker)
}

/** The HTML templates of an Angular project. */
class AngularTemplateMatcher : ProjectMarkerMatcher(setOf("html"), { it.findChild("angular.json") != null })

/** The HTML and CSS files of a project with `tailwindcss` in its `package.json`. */
class TailwindFileMatcher : ProjectMarkerMatcher(setOf("html", "css"), { directory ->
  directory.findChild("package.json")?.let { String(it.contentsToByteArray()).contains("\"tailwindcss\"") } == true
})

