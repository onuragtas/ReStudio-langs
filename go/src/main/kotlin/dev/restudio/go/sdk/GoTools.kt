package dev.restudio.go.sdk

import dev.restudio.core.sdk.Tool
import dev.restudio.core.sdk.ToolProvider
import dev.restudio.core.sdk.install.InstallRecipe
import dev.restudio.go.GoBundle

/** The Go language server and the Delve debugger with their pinned versions. `go install` installs them on first use. */
object GoTools {
  val GOPLS: Tool = Tool(
    "gopls",
    GoBundle.message("tool.gopls"),
    recipe = InstallRecipe.GoInstall("golang.org/x/tools/gopls", "v0.24.0"),
  )

  val DLV: Tool = Tool(
    "dlv",
    GoBundle.message("tool.dlv"),
    recipe = InstallRecipe.GoInstall("github.com/go-delve/delve/cmd/dlv", "v1.27.2"),
  )
}

/** Registers [GoTools] on the ReStudio Tools settings page. */
class GoToolProvider : ToolProvider {
  override fun tools(): List<Tool> = listOf(GoTools.GOPLS, GoTools.DLV)
}
