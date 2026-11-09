package dev.restudio.php.sdk

import com.intellij.openapi.vfs.VirtualFile
import dev.restudio.core.sdk.ToolLocator
import java.nio.file.Files
import java.nio.file.Path

/** Finds PHP and the PHP tools: the language servers, the php-debug adapter, Node.js for it and the Composer tools. */
object PhpSdk {
  private val tools: Path = Path.of(System.getProperty("user.home"), ".restudio-dev/tools")

  fun php(): Path? = ToolLocator.find("php")

  fun node(): Path? = ToolLocator.find("node")

  /** `phpactor` on the PATH, or else the newest `~/.restudio-dev/tools/phpactor/<version>/phpactor.phar`. */
  fun phpactor(): List<String>? {
    ToolLocator.find("phpactor")?.let { return listOf(it.toString()) }
    val phar = newest("phpactor", "phpactor.phar") ?: return null
    val php = php() ?: return null
    return listOf(php.toString(), phar.toString())
  }

  /** `intelephense` on the PATH. Install it with `npm install -g intelephense`. */
  fun intelephense(): Path? = ToolLocator.find("intelephense")

  /** `composer` on the PATH. */
  fun composer(): Path? = ToolLocator.find("composer")

  /** The script `vendor/bin/<name>` of the Composer project in [root], or `null` when the package is not installed. */
  fun vendorBinary(root: VirtualFile, name: String): VirtualFile? = root.findFileByRelativePath("vendor/bin/$name")?.takeIf { !it.isDirectory }

  /** The vscode-php-debug adapter script in the newest `~/.restudio-dev/tools/php-debug/<version>`. */
  fun phpDebugAdapter(): Path? = newest("php-debug", "vsix/extension/out/phpDebug.js")

  /** The nearest directory with `composer.json` at or above [file]. */
  fun composerRoot(file: VirtualFile): VirtualFile? =
    generateSequence(if (file.isDirectory) file else file.parent) { it.parent }.firstOrNull { it.findChild("composer.json") != null }

  /** The child PATH, so that a PHP script finds `composer` and Node.js finds itself when the IDE starts from the Dock. */
  fun environment(): Map<String, String> = mapOf("PATH" to ToolLocator.childPath())

  private fun newest(tool: String, file: String): Path? {
    val directory = tools.resolve(tool)
    if (!Files.isDirectory(directory)) return null
    return Files.list(directory).use { versions ->
      versions.sorted(Comparator.reverseOrder()).map { it.resolve(file) }.filter { Files.isRegularFile(it) }.findFirst().orElse(null)
    }
  }
}
