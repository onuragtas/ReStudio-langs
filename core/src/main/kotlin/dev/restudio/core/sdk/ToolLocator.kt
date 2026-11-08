package dev.restudio.core.sdk

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Finds a command-line tool such as `gopls` or `dlv`.
 *
 * An IDE that starts from the Dock does not get the PATH of the login shell.
 * Thus the locator also looks in the usual install directories.
 */
object ToolLocator {
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

  /** Returns the absolute path of [name], or `null` when the tool is not installed. */
  fun find(name: String): Path? {
    return (pathDirectories() + extraDirectories).map { it.resolve(name) }.firstOrNull { Files.isExecutable(it) }
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

  private fun pathDirectories(): List<Path> =
    System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotEmpty() }.map { Path.of(it) }
}
