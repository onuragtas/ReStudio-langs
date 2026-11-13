package dev.restudio.dotnet

import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.util.io.NioFiles
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleTextAttributes
import dev.restudio.dotnet.sdk.DotnetSdk
import dev.restudio.dotnet.solution.SolutionModel
import dev.restudio.dotnet.solution.SolutionViewPane
import dev.restudio.dotnet.solution.SolutionViewRootNode
import dev.restudio.dotnet.solution.SolutionViewSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * Builds the Solution view tree with real MSBuild evaluations and compares it to the expected text.
 *
 * The test needs the dotnet command. Without it, the test does nothing.
 */
class SolutionViewTreeTest : BasePlatformTestCase() {
  private val sample = Path.of("../testdata/dotnet").toAbsolutePath().normalize()

  override fun tearDown() {
    try {
      SolutionViewSettings.setShowAllFiles(project, false)
    }
    finally {
      super.tearDown()
    }
  }

  private fun tree(directory: Path, showAll: Boolean = false): String {
    SolutionViewSettings.setShowAllFiles(project, showAll)
    val model = SolutionModel.getInstance(project)
    model.useDirectory(directory)
    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)!!.refresh(false, true)
    assertNotNull(model.reloadNow())
    val result = StringBuilder()
    print(SolutionViewRootNode(project, ViewSettings.DEFAULT), -1, result)
    return result.toString()
  }

  private fun print(node: AbstractTreeNode<*>, depth: Int, result: StringBuilder) {
    if (depth >= 0) {
      node.update()
      val presentation = node.presentation
      val text = presentation.coloredText.takeIf { it.isNotEmpty() }?.joinToString("") { it.text } ?: presentation.presentableText
      val gray = presentation.coloredText.any { it.attributes == SimpleTextAttributes.GRAYED_ATTRIBUTES && it.text.isNotBlank() && !it.text.startsWith("  ") }
      val bold = presentation.coloredText.any { it.attributes == SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES }
      result.append("  ".repeat(depth)).append(text?.trim())
      presentation.locationString?.let { result.append(" (").append(it).append(")") }
      if (gray) result.append(" [not in project]")
      if (bold && depth > 0) result.append(" [startup]")
      result.append('\n')
    }
    for (child in node.children.sortedWith(SolutionViewPane.COMPARATOR)) print(child, depth + 1, result)
  }

  fun `test the tree of the sample solution`() {
    if (DotnetSdk.command() == null) return
    assertEquals(
      """
      Solution 'Shop'  8 projects
        src
          Shop.Api  net10.0
            Dependencies
              Frameworks
                Microsoft.AspNetCore.App
                Microsoft.NETCore.App
              Packages
                Microsoft.AspNetCore.OpenApi (10.0.14)
              Projects
                Shop.Application
                Shop.Infrastructure
            Properties
              launchSettings.json
            appsettings.json
              appsettings.Development.json
            Program.cs
            Shop.Api.http
          Shop.Application  net10.0
            Dependencies
              Frameworks
                Microsoft.NETCore.App
              Projects
                Shop.Domain
            ProductService.cs
          Shop.Cli  net10.0
            Dependencies
              Frameworks
                Microsoft.NETCore.App
              Projects
                Shop.Application
            Program.cs
          Shop.Domain  net10.0
            Dependencies
              Frameworks
                Microsoft.NETCore.App
            Product.cs
          Shop.Infrastructure  net10.0
            Dependencies
              Frameworks
                Microsoft.NETCore.App
              Projects
                Shop.Application
            Class1.cs
          Shop.Worker  net10.0
            Dependencies
              Frameworks
                Microsoft.NETCore.App
              Packages
                Microsoft.Extensions.Hosting (10.0.14)
              Projects
                Shop.Application
            Properties
              launchSettings.json
            appsettings.json
              appsettings.Development.json
            Program.cs
            Worker.cs
        tests
          Shop.Api.Tests  net10.0
            Dependencies
              Frameworks
                Microsoft.NETCore.App
              Packages
                coverlet.collector (6.0.4)
                Microsoft.NET.Test.Sdk (17.14.1)
                xunit (2.9.3)
                xunit.runner.visualstudio (3.1.4)
              Projects
                Shop.Api
            UnitTest1.cs
          Shop.Domain.Tests  net10.0
            Dependencies
              Frameworks
                Microsoft.NETCore.App
              Packages
                coverlet.collector (6.0.4)
                Microsoft.NET.Test.Sdk (17.14.1)
                xunit (2.9.3)
                xunit.runner.visualstudio (3.1.4)
              Projects
                Shop.Domain
            ProductTests.cs

      """.trimIndent(),
      tree(sample),
    )
  }

  private fun scratchProject(): Path {
    val root = Files.createTempDirectory("solution-view").toRealPath()
    VfsRootAccess.allowRootAccess(testRootDisposable, root.toString())
    val directory = Files.createDirectories(root.resolve("Web"))
    Files.writeString(directory.resolve("Web.csproj"), """
      <Project Sdk="Microsoft.NET.Sdk">
        <PropertyGroup><TargetFramework>net10.0</TargetFramework></PropertyGroup>
        <ItemGroup>
          <Compile Remove="Skip.cs" />
          <None Include="..\shared.txt" Link="Shared\shared.txt" />
        </ItemGroup>
      </Project>
    """.trimIndent())
    for (file in listOf("Pages/Index.razor", "Pages/Index.razor.cs", "Pages/Index.razor.css", "appsettings.json", "appsettings.Development.json", "Skip.cs", "Program.cs", "bin/Web.dll")) {
      Files.createDirectories(directory.resolve(file).parent)
      Files.writeString(directory.resolve(file), "")
    }
    Files.writeString(root.resolve("shared.txt"), "")
    return root
  }

  fun `test nesting, links and Compile Remove in a project without a solution`() {
    if (DotnetSdk.command() == null) return
    val root = scratchProject()
    try {
      assertEquals(
        """
        Solution '${root.fileName}'  1 project
          Web  net10.0
            Dependencies
              Frameworks
                Microsoft.NETCore.App
            Pages
              Index.razor
                Index.razor.cs
                Index.razor.css
            Shared
              shared.txt (link)
            appsettings.json
              appsettings.Development.json
            Program.cs

        """.trimIndent(),
        tree(root),
      )
      val all = tree(root, showAll = true)
      assertTrue(all, all.contains("    bin [not in project]\n      Web.dll [not in project]\n"))
      assertTrue(all, all.contains("    Skip.cs [not in project]\n"))
      assertTrue(all, all.contains("    Web.csproj [not in project]\n"))
    }
    finally {
      NioFiles.deleteRecursively(root)
    }
  }

  fun `test file operations in the IDE update the project file only when needed`() {
    val root = Files.createTempDirectory("csproj-update").toRealPath()
    VfsRootAccess.allowRootAccess(testRootDisposable, root.toString())
    val sdk = Files.createDirectories(root.resolve("Sdk"))
    Files.writeString(sdk.resolve("Sdk.csproj"), "<Project Sdk=\"Microsoft.NET.Sdk\">\n  <ItemGroup>\n    <None Update=\"data.json\" CopyToOutputDirectory=\"Always\" />\n  </ItemGroup>\n</Project>\n")
    Files.writeString(sdk.resolve("data.json"), "{}")
    val legacy = Files.createDirectories(root.resolve("Old"))
    Files.writeString(legacy.resolve("Old.csproj"), "<Project ToolsVersion=\"15.0\">\n  <ItemGroup>\n    <Compile Include=\"Program.cs\" />\n  </ItemGroup>\n</Project>\n")
    Files.writeString(legacy.resolve("Program.cs"), "")
    try {
      val directory = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!
      directory.refresh(false, true)
      val sdkDirectory = directory.findChild("Sdk")!!
      val legacyDirectory = directory.findChild("Old")!!
      com.intellij.openapi.application.WriteAction.run<Exception> {
        sdkDirectory.createChildData(this, "New.cs")
        sdkDirectory.findChild("data.json")!!.rename(this, "config.json")
        legacyDirectory.createChildData(this, "Order.cs")
        legacyDirectory.findChild("Program.cs")!!.delete(this)
      }
      com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
      assertEquals(
        "<Project Sdk=\"Microsoft.NET.Sdk\">\n  <ItemGroup>\n    <None Update=\"config.json\" CopyToOutputDirectory=\"Always\" />\n  </ItemGroup>\n</Project>\n",
        Files.readString(sdk.resolve("Sdk.csproj")),
      )
      assertEquals("<Project ToolsVersion=\"15.0\">\n  <ItemGroup>\n    <Compile Include=\"Order.cs\" />\n  </ItemGroup>\n</Project>\n", Files.readString(legacy.resolve("Old.csproj")))
    }
    finally {
      NioFiles.deleteRecursively(root)
    }
  }
}
