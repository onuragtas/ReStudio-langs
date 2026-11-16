package dev.restudio.php.sdk

import dev.restudio.core.sdk.Tool
import dev.restudio.core.sdk.ToolProvider
import dev.restudio.core.sdk.install.InstallRecipe
import dev.restudio.php.PhpBundle

/**
 * The PHP language servers with their pinned versions.
 *
 * The IDE downloads the Phpactor PHAR on first use. npm installs Intelephense on first use. Intelephense needs Node.js, not PHP.
 * [PHP_RUNTIME] runs Phpactor when this computer has no `php`, for example when PHP runs only in Docker.
 */
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

  /**
   * A static PHP build from static-php-cli, only for Phpactor. It has its own ID, so that a run configuration still uses
   * the `php` of the user. Phpactor only reads the code, so the PHP version and extensions of the project do not matter.
   */
  val PHP_RUNTIME: Tool = Tool(
    "php-runtime",
    PhpBundle.message("tool.php.runtime"),
    executable = "php",
    recipe = InstallRecipe.Download(
      url = "https://dl.static-php.dev/static-php-cli/common/php-8.5.8-cli-macos-aarch64.tar.gz",
      sha256 = "cba9bd8b38b51ef2c7cebf02af689e4481bbd07f65edc0b63d3a43b04e0c9db7",
      version = "8.5.8",
      executable = "php",
      archive = InstallRecipe.Archive.TAR,
    ),
  )

  val INTELEPHENSE: Tool = Tool(
    "intelephense",
    PhpBundle.message("tool.intelephense"),
    recipe = InstallRecipe.Npm("intelephense", "1.18.5", "intelephense"),
  )
}

/** Registers [PhpTools] on the ReStudio Tools settings page. */
class PhpToolProvider : ToolProvider {
  override fun tools(): List<Tool> = listOf(PhpTools.PHPACTOR, PhpTools.PHP_RUNTIME, PhpTools.INTELEPHENSE)
}
