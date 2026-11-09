package dev.restudio.core.sdk

import dev.restudio.core.CoreBundle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/** The SDK tools that more than one language plugin needs: `go`, `dotnet`, `php`, `node`, `npm` and `flutter`. */
object SdkTools {
  private val home: Path = Path.of(System.getProperty("user.home"))

  val GO: Tool = Tool("go", CoreBundle.message("tool.go"), fallbacks = { listOf(Path.of("/usr/local/go/bin/go")) })
  val DOTNET: Tool = Tool(
    "dotnet",
    CoreBundle.message("tool.dotnet"),
    preferred = { listOfNotNull(System.getenv("DOTNET_ROOT")?.let { Path.of(it, "dotnet") }) },
    fallbacks = { listOf(home.resolve(".dotnet/dotnet"), Path.of("/usr/local/share/dotnet/dotnet")) },
  )
  val PHP: Tool = Tool("php", CoreBundle.message("tool.php"))
  val NODE: Tool = Tool("node", CoreBundle.message("tool.node"))
  val NPM: Tool = Tool("npm", CoreBundle.message("tool.npm"))

  /**
   * The `flutter` command. `FLUTTER_ROOT` wins over the PATH.
   * After the PATH, it tries `~/flutter`, `~/development/flutter` and the Homebrew cask.
   */
  val FLUTTER: Tool = Tool(
    "flutter",
    CoreBundle.message("tool.flutter"),
    preferred = { listOfNotNull(System.getenv("FLUTTER_ROOT")?.takeIf { it.isNotBlank() }?.let { Path.of(it, "bin", "flutter") }) },
    fallbacks = {
      listOf(home.resolve("flutter/bin/flutter"), home.resolve("development/flutter/bin/flutter")) +
        homebrewCasks("flutter").map { it.resolve("flutter/bin/flutter") }
    },
  )

  val ALL: List<Tool> = listOf(GO, DOTNET, PHP, NODE, NPM, FLUTTER)

  /**
   * Returns the root directory of the Flutter SDK, or `null`.
   *
   * The `flutter` command can be a symbolic link, for example in `/opt/homebrew/bin`. Thus the function follows the link.
   */
  fun flutterSdkRoot(): Path? {
    val flutter = ToolLocator.find(FLUTTER) ?: return null
    val real = runCatching { flutter.toRealPath() }.getOrDefault(flutter)
    return real.parent?.parent?.takeIf { Files.isDirectory(it.resolve("packages")) || Files.isDirectory(it.resolve("bin/cache")) }
  }

  private fun homebrewCasks(name: String): List<Path> =
    listOf(Path.of("/opt/homebrew/Caskroom", name), Path.of("/usr/local/Caskroom", name))
      .filter { it.isDirectory() }
      .flatMap { it.listDirectoryEntries() }
      .filter { it.isDirectory() }
}

/** Registers [SdkTools] on the ReStudio Tools settings page. */
class SdkToolProvider : ToolProvider {
  override fun tools(): List<Tool> = SdkTools.ALL
}
