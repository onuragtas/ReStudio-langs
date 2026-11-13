package dev.restudio.dotnet

import dev.restudio.dotnet.project.MsbuildItem
import dev.restudio.dotnet.solution.CsprojEditor
import dev.restudio.dotnet.solution.DirEntry
import dev.restudio.dotnet.solution.FileEntry
import dev.restudio.dotnet.solution.NestingRules
import dev.restudio.dotnet.solution.ProjectContent
import dev.restudio.dotnet.solution.SolutionFile
import dev.restudio.dotnet.solution.ViewEntry
import java.nio.file.Path
import junit.framework.TestCase

/** Tests for the parts of the Solution view that do not need the IDE or the dotnet command. */
class SolutionModelTest : TestCase() {
  private val sample = Path.of("../testdata/dotnet").toAbsolutePath().normalize()

  fun `test the folders and projects of the sample solution`() {
    val structure = SolutionFile.read(sample.resolve("Shop.sln"))!!
    assertEquals("Shop", structure.name)
    assertEquals(listOf("src", "tests"), structure.root.folders.map { it.name })
    assertEquals(emptyList<Path>(), structure.root.projects)
    assertEquals(
      listOf("Shop.Api", "Shop.Application", "Shop.Domain", "Shop.Infrastructure", "Shop.Worker", "Shop.Cli"),
      structure.root.folders[0].projects.map { it.fileName.toString().removeSuffix(".csproj") },
    )
    assertEquals(listOf("Shop.Api.Tests", "Shop.Domain.Tests"), structure.root.folders[1].projects.map { it.fileName.toString().removeSuffix(".csproj") })
    assertEquals(sample.resolve("src/Shop.Api/Shop.Api.csproj"), structure.root.folders[0].projects[0])
    assertEquals(8, structure.root.allProjects.size)
  }

  fun `test solution items and nested folders of a sln file`() {
    val text = """
      Project("{2150E333-8FDC-42A3-9474-1A3956D46DE8}") = "Solution Items", "Solution Items", "{11111111-1111-1111-1111-111111111111}"
      	ProjectSection(SolutionItems) = preProject
      		Directory.Build.props = Directory.Build.props
      		docs\README.md = docs\README.md
      	EndProjectSection
      EndProject
      Project("{2150E333-8FDC-42A3-9474-1A3956D46DE8}") = "inner", "inner", "{22222222-2222-2222-2222-222222222222}"
      EndProject
      Project("{9A19103F-16F7-4668-BE54-9A1E7A4F7556}") = "App", "App\App.csproj", "{33333333-3333-3333-3333-333333333333}"
      EndProject
      Project("{E24C65DC-7377-472B-9ABA-BC803B73C61A}") = "Site", "http://localhost/site", "{44444444-4444-4444-4444-444444444444}"
      EndProject
      Global
      	GlobalSection(NestedProjects) = preSolution
      		{22222222-2222-2222-2222-222222222222} = {11111111-1111-1111-1111-111111111111}
      		{33333333-3333-3333-3333-333333333333} = {22222222-2222-2222-2222-222222222222}
      	EndGlobalSection
      EndGlobal
    """.trimIndent()
    val root = SolutionFile.parseSln(Path.of("/s/All.sln"), text).root
    assertEquals("All", root.name)
    val items = root.folders.single()
    assertEquals("Solution Items", items.name)
    assertEquals(listOf(Path.of("/s/Directory.Build.props"), Path.of("/s/docs/README.md")), items.files)
    assertEquals(listOf(Path.of("/s/App/App.csproj")), items.folders.single().projects)
    assertEquals(emptyList<Path>(), root.projects)
  }

  fun `test an slnx file`() {
    val text = """
      <Solution>
        <Folder Name="/src/">
          <Project Path="src/Api/Api.csproj" />
        </Folder>
        <Folder Name="/src/libs/">
          <Project Path="src\Lib\Lib.csproj" />
        </Folder>
        <Folder Name="/Solution Items/">
          <File Path="Directory.Packages.props" />
        </Folder>
        <Project Path="tools/Tool/Tool.csproj" />
      </Solution>
    """.trimIndent()
    val root = SolutionFile.parseSlnx(Path.of("/s/All.slnx"), text)!!.root
    assertEquals(listOf("src", "Solution Items"), root.folders.map { it.name })
    assertEquals(listOf(Path.of("/s/src/Api/Api.csproj")), root.folders[0].projects)
    assertEquals(listOf(Path.of("/s/src/Lib/Lib.csproj")), root.folders[0].folders.single { it.name == "libs" }.projects)
    assertEquals(listOf(Path.of("/s/Directory.Packages.props")), root.folders[1].files)
    assertEquals(listOf(Path.of("/s/tools/Tool/Tool.csproj")), root.projects)
  }

  fun `test the nesting rules`() {
    val names = setOf(
      "Index.razor", "Index.razor.cs", "Index.razor.css", "Form1.cs", "Form1.Designer.cs", "Resources.resx", "Resources.Designer.cs",
      "appsettings.json", "appsettings.Development.json", "Web.config", "Web.Release.config", "Product.cs", "Product.Tests.cs", "Other.Designer.cs",
    )
    assertEquals("Index.razor", NestingRules.parent("Index.razor.cs", names))
    assertEquals("Index.razor", NestingRules.parent("Index.razor.css", names))
    assertEquals("Form1.cs", NestingRules.parent("Form1.Designer.cs", names))
    assertEquals("Resources.resx", NestingRules.parent("Resources.Designer.cs", names))
    assertEquals("appsettings.json", NestingRules.parent("appsettings.Development.json", names))
    assertEquals("Web.config", NestingRules.parent("Web.Release.config", names))
    assertNull(NestingRules.parent("Product.Tests.cs", names))
    assertNull(NestingRules.parent("Other.Designer.cs", names))
    assertNull(NestingRules.parent("appsettings.json", names))
  }

  private fun render(entries: List<ViewEntry>, indent: String = ""): String = entries.joinToString("") { entry ->
    val mark = if (entry.inProject) "" else " (not in project)"
    when (entry) {
      is DirEntry -> "$indent${entry.name}/$mark${if (entry.realPath == null) " (virtual)" else ""}\n"
      is FileEntry -> "$indent${entry.name}$mark${if (entry.isLink) " -> ${entry.realPath.fileName}" else ""}\n" + render(entry.nested, "$indent  ")
    }
  }

  fun `test MSBuild items decide what the project shows`() {
    val directory = Path.of("/p")
    val items = listOf(
      MsbuildItem("Compile", directory.resolve("A.cs")),
      MsbuildItem("Compile", directory.resolve("Form1.cs")),
      MsbuildItem("Compile", directory.resolve("Form1.Designer.cs"), dependentUpon = "Form1.cs"),
      MsbuildItem("EmbeddedResource", directory.resolve("Form1.resx"), dependentUpon = "Form1.cs"),
      MsbuildItem("Compile", directory.resolve("Sub/B.cs")),
      MsbuildItem("None", Path.of("/outside.txt"), link = "Linked/outside.txt"),
      MsbuildItem("None", Path.of("/shared/Shared.cs")),
      MsbuildItem("Folder", directory.resolve("Empty")),
      MsbuildItem("None", Path.of("/nuget/adapter.dll"), link = "adapter.dll", visible = false),
    )
    val content = ProjectContent(directory, items)
    val listing = listOf("A.cs" to false, "Form1.cs" to false, "Form1.Designer.cs" to false, "Form1.resx" to false, "Sub" to true,
                         "Empty" to true, "bin" to true, "obj" to true, "notes.md" to false, "P.csproj" to false)
    assertEquals(
      """
      A.cs
      Form1.cs
        Form1.Designer.cs
        Form1.resx
      Sub/
      Empty/
      Shared.cs -> Shared.cs
      Linked/ (virtual)

      """.trimIndent(),
      render(content.children(directory, listing, showAll = false)),
    )
    assertEquals("B.cs\n", render(content.children(directory.resolve("Sub"), listOf("B.cs" to false, "Skip.cs" to false), showAll = false)))
    assertEquals("B.cs\nSkip.cs (not in project)\n", render(content.children(directory.resolve("Sub"), listOf("B.cs" to false, "Skip.cs" to false), showAll = true)))
    assertEquals("outside.txt -> outside.txt\n", render(content.children(directory.resolve("Linked"), emptyList(), showAll = false)))
    val all = render(content.children(directory, listing, showAll = true))
    assertTrue(all, all.contains("bin/ (not in project)\n") && all.contains("notes.md (not in project)\n") && all.contains("P.csproj (not in project)\n"))
  }

  fun `test the default globs before MSBuild evaluates the project`() {
    val directory = Path.of("/p")
    val content = ProjectContent(directory, null)
    val listing = listOf("A.cs" to false, "bin" to true, "obj" to true, ".vs" to true, "P.csproj" to false, "appsettings.json" to false, "appsettings.Development.json" to false)
    assertEquals("A.cs\nappsettings.json\n  appsettings.Development.json\n", render(content.children(directory, listing, showAll = false)))
  }

  fun `test an SDK-style project needs no item for a new file`() {
    val sdk = "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><TargetFramework>net10.0</TargetFramework></PropertyGroup></Project>"
    assertTrue(CsprojEditor.hasDefaultItems(sdk, "A.cs"))
    assertFalse(CsprojEditor.hasDefaultItems(sdk.replace("<TargetFramework>", "<EnableDefaultCompileItems>false</EnableDefaultCompileItems><TargetFramework>"), "A.cs"))
    assertTrue(CsprojEditor.hasDefaultItems(sdk.replace("<TargetFramework>", "<EnableDefaultCompileItems>false</EnableDefaultCompileItems><TargetFramework>"), "a.json"))
    assertFalse(CsprojEditor.hasDefaultItems("<Project ToolsVersion=\"15.0\"><Import Project=\"\$(MSBuildToolsPath)\\Microsoft.CSharp.targets\" /></Project>", "A.cs"))
  }

  private val legacy = """
    <Project ToolsVersion="15.0">
      <ItemGroup>
        <Reference Include="System" />
      </ItemGroup>
      <ItemGroup>
        <Compile Include="Program.cs" />
        <Compile Include="Forms\Form1.cs" />
        <Compile Include="Forms\Form1.Designer.cs">
          <DependentUpon>Form1.cs</DependentUpon>
        </Compile>
      </ItemGroup>
      <ItemGroup>
        <None Include="App.config" />
      </ItemGroup>
    </Project>
  """.trimIndent() + "\n"

  fun `test add an item to the group of the same type`() {
    val result = CsprojEditor.addItem(legacy, "Compile", "Models/Order.cs")!!
    assertTrue(result, result.contains("    <Compile Include=\"Forms\\Form1.Designer.cs\">\n      <DependentUpon>Form1.cs</DependentUpon>\n    </Compile>\n    <Compile Include=\"Models\\Order.cs\" />\n  </ItemGroup>"))
    assertNull(CsprojEditor.addItem(legacy, "Compile", "Program.cs"))
    val noGroup = CsprojEditor.addItem("<Project>\n  <PropertyGroup />\n</Project>\n", "None", "a.json")!!
    assertEquals("<Project>\n  <PropertyGroup />\n  <ItemGroup>\n    <None Include=\"a.json\" />\n  </ItemGroup>\n</Project>\n", noGroup)
  }

  fun `test remove the items of a deleted file or directory`() {
    val file = CsprojEditor.removeItems(legacy, "Program.cs", directory = false)!!
    assertFalse(file.contains("Program.cs"))
    assertTrue(file.contains("Forms\\Form1.cs"))
    val directory = CsprojEditor.removeItems(legacy, "Forms", directory = true)!!
    assertFalse(directory, directory.contains("Form1"))
    assertTrue(directory.contains("<Compile Include=\"Program.cs\" />"))
    val group = CsprojEditor.removeItems(legacy, "App.config", directory = false)!!
    assertFalse(group, group.contains("App.config"))
    assertEquals(3, Regex("<ItemGroup>").findAll(legacy).count())
    assertEquals(2, Regex("<ItemGroup>").findAll(group).count())
    assertNull(CsprojEditor.removeItems(legacy, "Missing.cs", directory = false))
    val sdk = "<Project Sdk=\"Microsoft.NET.Sdk\">\n  <ItemGroup>\n    <Compile Remove=\"Old.cs\" />\n    <None Update=\"data.json\" CopyToOutputDirectory=\"Always\" />\n  </ItemGroup>\n</Project>\n"
    assertEquals("<Project Sdk=\"Microsoft.NET.Sdk\">\n  <ItemGroup>\n    <Compile Remove=\"Old.cs\" />\n  </ItemGroup>\n</Project>\n", CsprojEditor.removeItems(sdk, "data.json", directory = false))
  }

  fun `test rename and move change the paths and DependentUpon`() {
    val renamed = CsprojEditor.renamePath(legacy, "Forms/Form1.cs", "Forms/MainForm.cs")!!
    assertTrue(renamed.contains("<Compile Include=\"Forms\\MainForm.cs\" />"))
    assertTrue(renamed.contains("<DependentUpon>MainForm.cs</DependentUpon>"))
    assertTrue(renamed.contains("<Compile Include=\"Forms\\Form1.Designer.cs\">"))
    val moved = CsprojEditor.renamePath(legacy, "Forms", "UI/Forms")!!
    assertTrue(moved.contains("<Compile Include=\"UI\\Forms\\Form1.cs\" />") && moved.contains("<Compile Include=\"UI\\Forms\\Form1.Designer.cs\">"))
    assertTrue(moved.contains("<DependentUpon>Form1.cs</DependentUpon>"))
    val sdk = "<Project Sdk=\"Microsoft.NET.Sdk\"><ItemGroup><None Update=\"appsettings.json\" CopyToOutputDirectory=\"Always\" /><Compile Remove=\"Gen/**\" /></ItemGroup></Project>"
    assertEquals(sdk.replace("appsettings.json", "settings.json"), CsprojEditor.renamePath(sdk, "appsettings.json", "settings.json"))
    assertEquals(sdk.replace("Gen/**", "Generated/**"), CsprojEditor.renamePath(sdk, "Gen", "Generated"))
    assertNull(CsprojEditor.renamePath(sdk, "Other.cs", "Renamed.cs"))
  }
}
