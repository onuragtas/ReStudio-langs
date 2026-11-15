package dev.restudio.dotnet.sdk

import com.intellij.openapi.project.Project
import dev.restudio.core.sdk.ToolLocator
import dev.restudio.core.sdk.install.ToolInstaller
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

  /**
   * Finds netcoredbg as [ToolLocator] does. When the pinned version is missing, it offers to install it.
   *
   * [project] is the project for the notification. With `null`, the notification goes to the active window.
   */
  fun netcoredbg(project: Project? = null): Path? = ToolInstaller.findOrOffer(project, DotnetTools.NETCOREDBG)

}
