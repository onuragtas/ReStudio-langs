package dev.restudio.web.sdk

import com.intellij.openapi.vfs.VirtualFile
import dev.restudio.core.sdk.ToolLocator
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Finds Node.js, the package manager of a project and the frontend tools. */
object NodeSdk {
  private val tools: Path = Path.of(System.getProperty("user.home"), ".restudio-dev/tools")

  fun node(): Path? = ToolLocator.find("node")

  /** The nearest directory with `package.json` at or above [file]. */
  fun packageRoot(file: VirtualFile): VirtualFile? =
    generateSequence(if (file.isDirectory) file else file.parent) { it.parent }.firstOrNull { it.findChild("package.json") != null }

  /** The package manager of [root], chosen by its lock file. The default is npm. */
  fun packageManager(root: Path): String = when {
    Files.exists(root.resolve("pnpm-lock.yaml")) -> "pnpm"
    Files.exists(root.resolve("yarn.lock")) -> "yarn"
    Files.exists(root.resolve("bun.lockb")) || Files.exists(root.resolve("bun.lock")) -> "bun"
    else -> "npm"
  }

  /** The vscode-js-debug DAP server script in the newest `~/.restudio-dev/tools/js-debug/<version>`. */
  fun jsDebugServer(): Path? {
    val directory = tools.resolve("js-debug")
    if (!Files.isDirectory(directory)) return null
    return Files.list(directory).use { versions ->
      versions.sorted(Comparator.reverseOrder()).map { it.resolve("js-debug/src/dapDebugServer.js") }.filter { Files.isRegularFile(it) }.findFirst().orElse(null)
    }
  }

  /** The `node_modules/.bin` directory of [root]. The package managers install the project tools there. */
  fun binDirectory(root: Path): Path = root.resolve("node_modules/.bin")

  /** Finds [name] first in `node_modules/.bin` of [root], then with [ToolLocator]. */
  fun findTool(name: String, root: Path?): Path? =
    root?.let { binDirectory(it).resolve(name) }?.takeIf { Files.isExecutable(it) } ?: ToolLocator.find(name)

  /** Finds [name] like [findTool]. Returns the bare name when the tool is not installed. */
  fun command(name: String, root: String?): String = findTool(name, root?.let { Path.of(it) })?.toString() ?: name

  /** The environment of a child process. When [root] is set, `node_modules/.bin` of [root] comes first in the PATH. */
  fun environment(root: Path? = null): Map<String, String> {
    val path = ToolLocator.childPath()
    val bin = root?.let { binDirectory(it) }?.takeIf { Files.isDirectory(it) } ?: return mapOf("PATH" to path)
    return mapOf("PATH" to bin.toString() + File.pathSeparator + path)
  }

  /** The major version of the `typescript` package in `node_modules` of [root], or `null` when it is not installed. */
  fun typeScriptMajorVersion(root: Path): Int? {
    val manifest = root.resolve("node_modules/typescript/package.json")
    if (!Files.isRegularFile(manifest)) return null
    val version = VERSION.find(Files.readString(manifest))?.groupValues?.get(1) ?: return null
    return version.toIntOrNull()
  }

  private val VERSION = Regex(""""version"\s*:\s*"(\d+)\.""")
}
