package dev.restudio.php.sdk

import dev.restudio.core.sdk.Tool
import dev.restudio.core.sdk.ToolProvider
import dev.restudio.core.sdk.install.InstallRecipe
import dev.restudio.php.PhpBundle

/** The PHP language server with its pinned version. The IDE downloads the Phpactor PHAR on first use. */
object PhpTools {
  val PHPACTOR: Tool = Tool(
    "phpactor",
    PhpBundle.message("tool.phpactor"),
    recipe = InstallRecipe.Download(
      url = "https://github.com/phpactor/phpactor/releases/download/2026.10.07.0/phpactor.phar",
      sha256 = "b28bf6166e016d539fc2f2aec18ef918b12590e436a6f1ccbdf30b5bbdbdb16b",
      version = "2026.10.07.0",
    ),
  )
}

/** Registers [PhpTools] on the ReStudio Tools settings page. */
class PhpToolProvider : ToolProvider {
  override fun tools(): List<Tool> = listOf(PhpTools.PHPACTOR)
}
