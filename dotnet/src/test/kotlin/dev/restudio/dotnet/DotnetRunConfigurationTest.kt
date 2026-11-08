package dev.restudio.dotnet

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.execution.Location
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.restudio.core.entrypoints.EntryPointLineMarkerInfo
import dev.restudio.core.entrypoints.EntryPointLocation
import dev.restudio.dotnet.debug.NetcoredbgDescriptorFactory
import dev.restudio.dotnet.lsp.RoslynWorkspace
import dev.restudio.dotnet.run.CSharpLineMarkerProvider
import dev.restudio.dotnet.run.DotnetBuildBeforeRunTask
import dev.restudio.dotnet.run.DotnetRunConfiguration
import dev.restudio.dotnet.run.DotnetRunState
import dev.restudio.dotnet.sdk.DotnetSdk
import dev.restudio.dotnet.test.DotnetTestLocator
import dev.restudio.dotnet.test.DotnetTestRunConfiguration
import dev.restudio.dotnet.test.DotnetTestRunState
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class DotnetRunConfigurationTest : BasePlatformTestCase() {
  private val sample = Path.of("../testdata/dotnet").toAbsolutePath().normalize()

  private fun addApp(): PsiFile {
    myFixture.addFileToProject("Tool/Tool.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><OutputType>Exe</OutputType><TargetFramework>net10.0</TargetFramework></PropertyGroup></Project>")
    return myFixture.addFileToProject("Tool/Program.cs", "using System;\n\nConsole.WriteLine(\"hi\");\n")
  }

  private fun addTests(): PsiFile {
    myFixture.addFileToProject("Calc.Tests/Calc.Tests.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><TargetFramework>net10.0</TargetFramework></PropertyGroup></Project>")
    return myFixture.addFileToProject("Calc.Tests/MathTests.cs", "namespace Calc.Tests;\n\npublic class MathTests\n{\n    [Fact]\n    public void Adds() { }\n}\n")
  }

  private fun markers(file: PsiFile): List<EntryPointLineMarkerInfo> {
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    val result = mutableListOf<LineMarkerInfo<*>>()
    CSharpLineMarkerProvider().collectSlowLineMarkers(SyntaxTraverser.psiTraverser(myFixture.file).filter { it.firstChild == null }.toList(), result)
    return result.map { it as EntryPointLineMarkerInfo }
  }

  private fun configurations(file: PsiFile): List<RunConfiguration> {
    val marker = markers(file).single()
    val location = EntryPointLocation(marker.element!!, marker.entryPoint)
    val dataContext = SimpleDataContext.builder()
      .add(CommonDataKeys.PROJECT, project)
      .add(PlatformCoreDataKeys.MODULE, myFixture.module)
      .add(CommonDataKeys.PSI_ELEMENT, location.psiElement)
      .add(Location.DATA_KEY, location)
      .build()
    return ConfigurationContext.getFromContext(dataContext, "test").configurationsFromContext.orEmpty().map { it.configuration }
  }

  fun `test markers on top-level statements and on a test method`() {
    assertEquals(listOf("Main" to 2), markers(addApp()).map { it.entryPoint.name to myFixture.editor.document.getLineNumber(it.startOffset) })
    assertEquals(listOf("Calc.Tests.MathTests.Adds" to 5), markers(addTests()).map { it.entryPoint.name to myFixture.editor.document.getLineNumber(it.startOffset) })
  }

  fun `test the app producer creates a netcoredbg launch after a build`() {
    val configuration = configurations(addApp()).single() as DotnetRunConfiguration
    assertEquals(".NET: Tool", configuration.name)
    assertTrue(configuration.projectFile, configuration.projectFile.endsWith("Tool/Tool.csproj"))
    assertTrue(configuration.beforeRunTasks.single() is DotnetBuildBeforeRunTask)
    assertFalse(configuration.canRun(DefaultRunExecutor.EXECUTOR_ID))
    val environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
    assertTrue(configuration.getState(DefaultRunExecutor.getRunExecutorInstance(), environment) is DotnetRunState)
    if (DotnetSdk.netcoredbg() == null) return
    assertEquals(NetcoredbgDescriptorFactory.SERVER_ID, configuration.serverId)
    assertTrue(configuration.command, configuration.command.endsWith("netcoredbg --interpreter=vscode"))
    val launch = configuration.launchConfiguration
    assertTrue(launch, launch.contains("\"type\": \"coreclr\"") && launch.contains("bin/Debug/net10.0/Tool.dll") && launch.contains("\"DOTNET_ROOT\""))
  }

  fun `test the test producer creates a filtered dotnet test`() {
    val configuration = configurations(addTests()).single() as DotnetTestRunConfiguration
    assertEquals(".NET test: Adds", configuration.name)
    assertEquals("FullyQualifiedName=Calc.Tests.MathTests.Adds", configuration.testFilter)
    assertFalse(configuration.canRun(DefaultRunExecutor.EXECUTOR_ID))
    assertFalse(configuration.canRun(DEBUG_EXECUTOR_ID))
  }

  fun `test the locator finds a test in a subdirectory`() {
    val files = mutableListOf<com.intellij.openapi.vfs.VirtualFile>()
    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sample.resolve("tests"))!!.let { com.intellij.openapi.vfs.VfsUtilCore.iterateChildrenRecursively(it, null) { file -> files += file; true } }
    val hint = DotnetTestLocator.hint(sample.resolve("tests").toString(), "Shop.Domain.Tests.ProductTests.PriceWithTaxAddsTheRate")
    val locations = DotnetTestLocator.getLocation("dotnettest", hint.substringAfter("://"), project, GlobalSearchScope.filesScope(project, files))
    val descriptor = locations.single().openFileDescriptor!!
    assertEquals("ProductTests.cs", descriptor.file.name)
    assertEquals(String(descriptor.file.contentsToByteArray()).indexOf("public void PriceWithTaxAddsTheRate"), descriptor.offset)
  }

  fun `test Roslyn opens the solution of the sample`() {
    assertEquals(RoslynWorkspace.Solution(sample.resolve("Shop.sln")), RoslynWorkspace.find(sample))
  }

  fun `test dotnet test on the sample sends the TRX results`() {
    if (DotnetSdk.command() == null) return
    val configuration = configurations(addTests()).single() as DotnetTestRunConfiguration
    configuration.projectFile = sample.resolve("tests/Shop.Domain.Tests/Shop.Domain.Tests.csproj").toString()
    configuration.workingDirectory = sample.toString()
    configuration.testFilter = "FullyQualifiedName=Shop.Domain.Tests.ProductTests.PriceWithTaxAddsTheRate"
    val environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
    val state = configuration.getState(DefaultRunExecutor.getRunExecutorInstance(), environment) as DotnetTestRunState
    val handler = state.startProcess()
    val output = StringBuilder()
    handler.addProcessListener(object : ProcessListener {
      override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        synchronized(output) { output.append(event.text) }
      }
    })
    handler.startNotify()
    assertTrue("dotnet test did not end", handler.waitFor(TimeUnit.MINUTES.toMillis(3)))
    val text = synchronized(output) { output.toString() }
    assertTrue(text, text.contains("##teamcity[testStarted name='PriceWithTaxAddsTheRate'"))
    assertTrue(text, text.contains("##teamcity[testFinished name='PriceWithTaxAddsTheRate'"))
    assertFalse(text, text.contains("##teamcity[testFailed"))
  }
}

/** The ID of the platform Debug executor. Its class is in the debugger module. */
private const val DEBUG_EXECUTOR_ID = "Debug"
