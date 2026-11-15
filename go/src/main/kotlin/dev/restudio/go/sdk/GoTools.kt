package dev.restudio.go.sdk

import dev.restudio.core.sdk.Tool
import dev.restudio.core.sdk.ToolProvider
import dev.restudio.core.sdk.install.InstallRecipe
import dev.restudio.go.GoBundle

/** The Go language server with its pinned version. `go install` installs it on first use. */
object GoTools {
  val GOPLS: Tool = Tool(
    "gopls",
    GoBundle.message("tool.gopls"),
    recipe = InstallRecipe.GoInstall("golang.org/x/tools/gopls", "v0.24.0"),
  )
}

/** Registers [GoTools] on the ReStudio Tools settings page. */
class GoToolProvider : ToolProvider {
  override fun tools(): List<Tool> = listOf(GoTools.GOPLS)
}
