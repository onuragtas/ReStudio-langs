package dev.restudio.web.sdk

import com.intellij.openapi.vfs.VirtualFile
import dev.restudio.core.sdk.ToolLocator
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

  fun environment(): Map<String, String> = mapOf("PATH" to ToolLocator.childPath())
}
