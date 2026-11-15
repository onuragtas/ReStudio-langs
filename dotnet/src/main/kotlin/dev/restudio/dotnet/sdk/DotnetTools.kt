package dev.restudio.dotnet.sdk

import dev.restudio.core.sdk.Tool
import dev.restudio.core.sdk.ToolProvider
import dev.restudio.core.sdk.install.InstallRecipe
import dev.restudio.dotnet.DotnetBundle

/**
 * The C# language servers and the netcoredbg debugger with their pinned versions.
 *
 * `dotnet tool install` installs the servers on first use. The IDE downloads the netcoredbg release for macOS on Apple silicon.
 */
object DotnetTools {
  val ROSLYN: Tool = Tool(
    "roslyn-language-server",
    DotnetBundle.message("tool.roslyn"),
    recipe = InstallRecipe.DotnetTool("roslyn-language-server", "5.13.0-1.26514.10", "roslyn-language-server"),
  )

  val CSHARP_LS: Tool = Tool(
    "csharp-ls",
    DotnetBundle.message("tool.csharp.ls"),
    recipe = InstallRecipe.DotnetTool("csharp-ls", "0.28.0", "csharp-ls"),
  )

  val NETCOREDBG: Tool = Tool(
    "netcoredbg",
    DotnetBundle.message("tool.netcoredbg"),
    recipe = InstallRecipe.Download(
      url = "https://github.com/Samsung/netcoredbg/releases/download/3.2.0-1092/netcoredbg-osx-arm64.zip",
      sha256 = "f4fa33b3ff874910cc184b4bb3b9c56d0abdf5c6521cee0b144d7c6e4a6e59ea",
      version = "3.2.0-1092",
      executable = "netcoredbg/netcoredbg",
      archive = InstallRecipe.Archive.ZIP,
    ),
  )
}

/** Registers [DotnetTools] on the ReStudio Tools settings page. */
class DotnetToolProvider : ToolProvider {
  override fun tools(): List<Tool> = listOf(DotnetTools.ROSLYN, DotnetTools.CSHARP_LS, DotnetTools.NETCOREDBG)
}
