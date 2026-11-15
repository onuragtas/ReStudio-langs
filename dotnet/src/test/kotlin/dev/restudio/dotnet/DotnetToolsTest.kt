package dev.restudio.dotnet

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.restudio.core.sdk.ToolRegistry
import dev.restudio.core.sdk.install.InstallRecipe
import dev.restudio.dotnet.sdk.DotnetTools

class DotnetToolsTest : BasePlatformTestCase() {
  fun `test the plugin registers netcoredbg with its pinned download`() {
    val tool = ToolRegistry.find("netcoredbg")
    assertSame(DotnetTools.NETCOREDBG, tool)
    val recipe = tool!!.recipe as InstallRecipe.Download
    assertEquals(InstallRecipe.Archive.ZIP, recipe.archive)
    assertEquals("netcoredbg/netcoredbg", recipe.executable)
    assertTrue(recipe.url.endsWith("/${recipe.version}/netcoredbg-osx-arm64.zip"))
  }
}
