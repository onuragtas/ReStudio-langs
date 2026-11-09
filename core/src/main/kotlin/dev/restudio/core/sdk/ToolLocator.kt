package dev.restudio.core.sdk

import com.intellij.openapi.util.text.StringUtil
import dev.restudio.core.sdk.install.ToolInstaller
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Finds a command-line tool such as `gopls` or `dlv`.
 *
 * An IDE that starts from the Dock does not get the PATH of the login shell.
 * Thus the locator also looks in the usual install directories.
 *
 * The search order for a [Tool]:
 * 1. The path that the user sets in [ToolSettings].
 * 2. The pinned version that [ToolInstaller] installs.
 * 3. [Tool.preferred], the PATH, the usual install directories and [Tool.fallbacks].
 * 4. The newest version under `~/.restudio-dev/tools/<tool id>/`.
 */
object ToolLocator {
  /** The root of the tools that [ToolInstaller] installs: `<root>/<tool id>/<version>/`. */
  val TOOLS_DIRECTORY: Path = Path.of(System.getProperty("user.home"), ".restudio-dev", "tools")

  private val extraDirectories: List<Path> = run {
    val home = Path.of(System.getProperty("user.home"))
    listOf(
      home.resolve("go/bin"),
      home.resolve(".dotnet/tools"),
      home.resolve("flutter/bin"),
      Path.of("/opt/homebrew/bin"),
      Path.of("/usr/local/bin"),
      Path.of("/usr/local/go/bin"),
    )
  }

  /**
   * Returns the absolute path of [name], or `null` when the tool is not installed.
   *
   * When a plugin registers a [Tool] with the ID or the executable [name], the full search order applies.
   */
  fun find(name: String): Path? = find(ToolRegistry.find(name) ?: Tool(name, name))

  /** Returns the absolute path of [tool], or `null` when the tool is not installed. */
  fun find(tool: Tool): Path? {
    ToolSettings.getInstanceOrNull()?.override(tool.id)?.let { if (Files.isRegularFile(it)) return it }
    return detect(tool)
  }

  /** Returns the path of [tool] without the user override. The settings page shows it. */
  fun detect(tool: Tool): Path? {
    tool.recipe?.let { recipe -> ToolInstaller.installedPath(tool, recipe)?.let { return it } }
    tool.preferred().firstOrNull { Files.isExecutable(it) }?.let { return it }
    (pathDirectories() + extraDirectories).map { it.resolve(tool.executable) }.firstOrNull { Files.isExecutable(it) }?.let { return it }
    tool.fallbacks().firstOrNull { Files.isExecutable(it) }?.let { return it }
    return newestManaged(tool)
  }

  /**
   * Returns the PATH value for a child process: the IDE PATH plus the usual install directories.
   *
   * gopls needs this to find `go` when the IDE starts from the Dock.
   */
  fun childPath(): String {
    val current = pathDirectories()
    return (current + extraDirectories.filter { it !in current }).joinToString(File.pathSeparator)
  }

  /** Returns the version directories of [toolId] under [TOOLS_DIRECTORY], the newest first. */
  fun managedVersions(toolId: String): List<Path> {
    val directory = TOOLS_DIRECTORY.resolve(toolId)
    if (!directory.isDirectory()) return emptyList()
    return directory.listDirectoryEntries()
      .filter { it.isDirectory() && !it.name.startsWith(".") && !ToolInstaller.isInstalling(it) }
      .sortedWith { a, b -> StringUtil.compareVersionNumbers(b.name, a.name) }
  }

  private fun newestManaged(tool: Tool): Path? {
    val names = listOfNotNull(tool.recipe?.executable, tool.executable).distinct()
    return managedVersions(tool.id).asSequence()
      .flatMap { version -> names.asSequence().map { version.resolve(it) } }
      .firstOrNull { Files.isRegularFile(it) }
  }

  private fun pathDirectories(): List<Path> =
    System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotEmpty() }.map { Path.of(it) }
}
