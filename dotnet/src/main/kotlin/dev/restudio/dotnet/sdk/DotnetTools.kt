package dev.restudio.dotnet.sdk

import dev.restudio.core.sdk.Tool
import dev.restudio.core.sdk.ToolProvider
import dev.restudio.core.sdk.install.InstallRecipe
import dev.restudio.dotnet.DotnetBundle

/** The C# language servers with their pinned versions. `dotnet tool install` installs them on first use. */
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
}

/** Registers [DotnetTools] on the ReStudio Tools settings page. */
class DotnetToolProvider : ToolProvider {
  override fun tools(): List<Tool> = listOf(DotnetTools.ROSLYN, DotnetTools.CSHARP_LS)
}
