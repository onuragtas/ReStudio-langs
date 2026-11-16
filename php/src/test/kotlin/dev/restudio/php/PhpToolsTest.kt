package dev.restudio.php

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.restudio.core.sdk.SdkTools
import dev.restudio.core.sdk.ToolRegistry
import dev.restudio.core.sdk.install.InstallRecipe
import dev.restudio.php.sdk.PhpSdk
import dev.restudio.php.sdk.PhpTools
import java.nio.file.Path

class PhpToolsTest : BasePlatformTestCase() {
  fun `test the php ID stays the php of the user`() {
    assertSame(SdkTools.PHP, ToolRegistry.find("php"))
    assertSame(PhpTools.PHP_RUNTIME, ToolRegistry.find("php-runtime"))
  }

  fun `test the static PHP comes as a pinned archive`() {
    val recipe = PhpTools.PHP_RUNTIME.recipe as InstallRecipe.Download
    assertEquals(InstallRecipe.Archive.TAR, recipe.archive)
    assertEquals("php", recipe.executable)
    assertTrue(recipe.url.endsWith("/php-${recipe.version}-cli-macos-aarch64.tar.gz"))
  }

  fun `test the given PHP runs the Phpactor PHAR`() {
    val phar = Path.of("/tools/phpactor/phpactor.phar")
    val php = Path.of("/tools/php-runtime/8.5.8/php")
    assertEquals(listOf(php.toString(), phar.toString()), PhpSdk.phpactorCommand(phar, php))
  }
}
