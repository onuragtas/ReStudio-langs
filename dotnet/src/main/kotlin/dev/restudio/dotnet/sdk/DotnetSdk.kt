package dev.restudio.dotnet.sdk

import dev.restudio.core.sdk.ToolLocator
import java.nio.file.Files
import java.nio.file.Path

/**
 * Finds the .NET host and the tools around it.
 *
 * Homebrew installs `dotnet` as a shell script that starts `libexec/dotnet`. netcoredbg cannot debug through the
 * script: the launch fails with 0x80004005. Thus [host] returns the real host, and [root] its directory.
 */
object DotnetSdk {
  /** The `dotnet` command for build, run and test. */
  fun command(): Path? = ToolLocator.find("dotnet")

  /** The real `dotnet` host executable. */
  fun host(): Path? {
    val command = command()?.toRealPath() ?: return null
    val libexecHost = command.parent?.parent?.resolve("libexec/dotnet")
    return if (libexecHost != null && Files.isExecutable(libexecHost)) libexecHost else command
  }

  /** The value for `DOTNET_ROOT`: the directory of the real host. */
  fun root(): Path? = host()?.parent

  /**
   * The environment for each .NET child process.
   *
   * The CLI writes its messages in the system language. The plugin reads some of them, so it asks for English.
   */
  fun environment(): Map<String, String> = buildMap {
    put("PATH", ToolLocator.childPath())
    put("DOTNET_CLI_UI_LANGUAGE", "en")
    put("DOTNET_NOLOGO", "1")
    root()?.let { put("DOTNET_ROOT", it.toString()) }
  }

  /** Finds netcoredbg on the PATH, or else in the newest `~/.restudio-dev/tools/netcoredbg/<version>` directory. */
  fun netcoredbg(): Path? {
    ToolLocator.find("netcoredbg")?.let { return it }
    val tools = Path.of(System.getProperty("user.home"), ".restudio-dev/tools/netcoredbg")
    if (!Files.isDirectory(tools)) return null
    return Files.list(tools).use { versions ->
      versions.sorted(Comparator.reverseOrder()).map { it.resolve("netcoredbg/netcoredbg") }.filter { Files.isExecutable(it) }.findFirst().orElse(null)
    }
  }
}
