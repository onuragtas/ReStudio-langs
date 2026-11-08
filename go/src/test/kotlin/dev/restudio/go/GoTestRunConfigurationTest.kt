package dev.restudio.go

import com.intellij.execution.Location
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.psi.SyntaxTraverser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.restudio.core.entrypoints.EntryPoint
import dev.restudio.core.entrypoints.EntryPointLocation
import dev.restudio.core.sdk.ToolLocator
import dev.restudio.go.test.GoTestJsonTranslator
import dev.restudio.go.test.GoTestRunConfiguration
import dev.restudio.go.test.GoTestRunState
import java.nio.file.Path

class GoTestRunConfigurationTest : BasePlatformTestCase() {
  private fun configurationForTest(name: String): GoTestRunConfiguration {
    myFixture.configureByText("greet_test.go", "package greet\n\nimport \"testing\"\n\nfunc $name(t *testing.T) {}\n")
    val leaf = SyntaxTraverser.psiTraverser(myFixture.file).filter { it.firstChild == null }.single()
    val location = EntryPointLocation(leaf, EntryPoint(EntryPoint.Kind.TEST, name, myFixture.file.text.indexOf("func $name")))
    val dataContext = SimpleDataContext.builder()
      .add(CommonDataKeys.PROJECT, project)
      .add(PlatformCoreDataKeys.MODULE, myFixture.module)
      .add(CommonDataKeys.PSI_ELEMENT, leaf)
      .add(Location.DATA_KEY, location)
      .build()
    val settings = ConfigurationContext.getFromContext(dataContext, "test").configuration
    assertNotNull("no run configuration from the context", settings)
    return settings!!.configuration as GoTestRunConfiguration
  }

  fun `test the producer creates a Go test configuration`() {
    val configuration = configurationForTest("TestBye")
    assertEquals("Go test: TestBye", configuration.name)
    assertEquals("^TestBye$", configuration.testPattern)
    assertTrue(configuration.launchConfiguration.contains("\"mode\": \"test\""))
  }

  fun `test Run goes to the test tree and Debug goes to Delve`() {
    val configuration = configurationForTest("TestBye")
    assertFalse(configuration.canRun(DefaultRunExecutor.EXECUTOR_ID))
    assertTrue(configuration.canRun(DEBUG_EXECUTOR_ID))
    val environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
    val state = configuration.getState(DefaultRunExecutor.getRunExecutorInstance(), environment) as GoTestRunState
    val commandLine = state.commandLine()
    assertEquals(listOf("test", "-json", "-run", "^TestBye$", "."), commandLine.parametersList.list)
    assertEquals(Path.of(configuration.workingDirectory), commandLine.workingDirectory)
  }

  fun `test go test output on the sample project fills the tree`() {
    if (ToolLocator.find("go") == null) return
    val configuration = configurationForTest("TestHello")
    configuration.workingDirectory = Path.of("../testdata/go/lib/greet").toAbsolutePath().normalize().toString()
    val environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
    val state = configuration.getState(DefaultRunExecutor.getRunExecutorInstance(), environment) as GoTestRunState
    val output = CapturingProcessHandler(state.commandLine()).runProcess(120_000)
    assertFalse("go test timed out", output.isTimeout)
    val tree = TestTree(GoTestJsonTranslator()).feed(output.stdoutLines.asSequence())
    assertEquals(emptyList<String>(), tree.problems)
    assertEquals("example.com/shop/lib/greet: done\n  TestHello: done", tree.render())
  }
}

/** The ID of the platform Debug executor. Its class is in the debugger module. */
private const val DEBUG_EXECUTOR_ID = "Debug"
