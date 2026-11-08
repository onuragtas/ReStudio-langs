package dev.restudio.dotnet

import com.google.gson.JsonParser
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.restudio.dotnet.lsp.DotnetTestRunCommandAction
import dev.restudio.dotnet.lsp.DotnetTestRunCommandAction.TestRunRequest
import dev.restudio.dotnet.lsp.RoslynPeekReferencesCommandAction
import dev.restudio.dotnet.run.DotnetRunConfiguration
import dev.restudio.dotnet.test.DotnetTestRunConfiguration
import java.nio.file.Path

class RoslynCommandTest : BasePlatformTestCase() {
  private val productTests = Path.of("../testdata/dotnet/tests/Shop.Domain.Tests/ProductTests.cs").toAbsolutePath().normalize()

  /** The argument of the "Run Test" code lens, as the LSP probe recorded it (case roslyn-codelens). */
  private fun argument(line: Int, debug: Boolean) = JsonParser.parseString(
    """{"textDocument":{"uri":"${productTests.toUri()}"},"range":{"start":{"line":$line,"character":16},"end":{"line":$line,"character":39}},"attachDebugger":$debug,"runSettingsPath":null}"""
  ).asJsonObject

  fun `test the code lens commands have actions`() {
    assertTrue(ActionManager.getInstance().getAction("dotnet.test.run") is DotnetTestRunCommandAction)
    assertTrue(ActionManager.getInstance().getAction("roslyn.client.peekReferences") is RoslynPeekReferencesCommandAction)
  }

  fun `test Run Test on a method line runs that test`() {
    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(productTests)
    val request = TestRunRequest.parse(argument(line = 7, debug = true))!!
    assertTrue(request.debug)
    val configuration = request.configuration(project)!!.configuration as DotnetTestRunConfiguration
    assertEquals("FullyQualifiedName=Shop.Domain.Tests.ProductTests.PriceWithTaxAddsTheRate", configuration.testFilter)
  }

  fun `test Run All Tests on a class line runs the classes of the file`() {
    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(productTests)
    val request = TestRunRequest.parse(argument(line = 4, debug = false))!!
    val configuration = request.configuration(project)!!.configuration as DotnetTestRunConfiguration
    assertEquals("FullyQualifiedName~Shop.Domain.Tests.ProductTests.", configuration.testFilter)
  }

  fun `test a worker program runs from the project view`() {
    myFixture.addFileToProject("Svc/Svc.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Worker\"><PropertyGroup><TargetFramework>net10.0</TargetFramework></PropertyGroup></Project>")
    val program = myFixture.addFileToProject("Svc/Program.cs", "using Svc;\n\nvar builder = Host.CreateApplicationBuilder(args);\nbuilder.Build().Run();\n")
    // The project view gives the file and no editor.
    val dataContext = SimpleDataContext.builder()
      .add(CommonDataKeys.PROJECT, project)
      .add(PlatformCoreDataKeys.MODULE, myFixture.module)
      .add(CommonDataKeys.PSI_ELEMENT, program)
      .add(Location.DATA_KEY, PsiLocation(program))
      .build()
    val configurations = ConfigurationContext.getFromContext(dataContext, "test").configurationsFromContext.orEmpty().map { it.configuration }
    assertEquals(".NET: Svc", (configurations.single() as DotnetRunConfiguration).name)
  }
}
