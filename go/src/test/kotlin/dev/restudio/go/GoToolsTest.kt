package dev.restudio.go

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.restudio.core.sdk.ToolRegistry
import dev.restudio.core.sdk.install.InstallRecipe
import dev.restudio.core.sdk.install.ToolInstaller
import dev.restudio.go.sdk.GoTools

class GoToolsTest : BasePlatformTestCase() {
  fun `test the plugin registers gopls with its pinned recipe`() {
    val tool = ToolRegistry.find("gopls")
    assertSame(GoTools.GOPLS, tool)
    val recipe = tool!!.recipe as InstallRecipe.GoInstall
    assertEquals("golang.org/x/tools/gopls", recipe.module)
    assertEquals("gopls", recipe.executable)
    assertTrue(ToolInstaller.versionDirectory(tool, recipe).endsWith("gopls/${recipe.version}"))
  }
}
