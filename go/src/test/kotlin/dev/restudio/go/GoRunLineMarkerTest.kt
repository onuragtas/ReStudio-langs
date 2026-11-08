package dev.restudio.go

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration
import dev.restudio.core.entrypoints.EntryPointLineMarkerInfo
import dev.restudio.core.entrypoints.EntryPointLocation
import dev.restudio.go.run.GoLineMarkerProvider

// The tests call the provider directly. The full highlighting also runs the LSP4IJ passes, and these wait for gopls.
class GoRunLineMarkerTest : BasePlatformTestCase() {
  private val twoTests = "package greet\n\nimport \"testing\"\n\nfunc TestHello(t *testing.T) {}\n\nfunc TestBye(t *testing.T) {\n\t_ = t\n}\n"

  fun `test one marker on the line of each test function`() {
    myFixture.configureByText("greet_test.go", twoTests)
    val document = myFixture.editor.document
    assertEquals(listOf("TestHello" to 4, "TestBye" to 6), markers().map { it.entryPoint.name to document.getLineNumber(it.startOffset) })
  }

  fun `test marker on func main`() {
    myFixture.configureByText("main.go", "package main\n\nfunc main() {\n}\n")
    assertEquals(listOf("main"), markers().map { it.entryPoint.name })
  }

  fun `test no marker on a plain function`() {
    myFixture.configureByText("greet.go", "package greet\n\nfunc Hello() string { return \"\" }\n")
    assertEmpty(markers())
  }

  fun `test the gutter location creates a Delve test launch`() {
    myFixture.configureByText("greet_test.go", twoTests)
    val marker = markers().single { it.entryPoint.name == "TestBye" }
    val configuration = configurationFor(context(EntryPointLocation(marker.element!!, marker.entryPoint)))
    assertEquals("Go test: TestBye", configuration.name)
    assertTrue(configuration.command, configuration.command.endsWith("dlv dap"))
    assertTrue(configuration.launchConfiguration.contains("\"mode\": \"test\""))
    assertTrue(configuration.launchConfiguration.contains("\"^TestBye$\""))
  }

  fun `test the caret picks the enclosing test function`() {
    myFixture.configureByText("greet_test.go", twoTests.replace("\t_ = t", "\t<caret>_ = t"))
    val configuration = configurationFor(context(PsiLocation(leaves().single()), withEditor = true))
    assertEquals("Go test: TestBye", configuration.name)
  }

  private fun leaves(): List<PsiElement> = SyntaxTraverser.psiTraverser(myFixture.file).filter { it.firstChild == null }.toList()

  private fun markers(): List<EntryPointLineMarkerInfo> {
    val result = mutableListOf<LineMarkerInfo<*>>()
    GoLineMarkerProvider().collectSlowLineMarkers(leaves(), result)
    return result.map { it as EntryPointLineMarkerInfo }
  }

  private fun context(location: Location<*>, withEditor: Boolean = false): DataContext {
    val builder = SimpleDataContext.builder()
      .add(CommonDataKeys.PROJECT, project)
      .add(PlatformCoreDataKeys.MODULE, myFixture.module)
      .add(CommonDataKeys.PSI_ELEMENT, location.psiElement)
      .add(Location.DATA_KEY, location)
    if (withEditor) builder.add(CommonDataKeys.EDITOR, myFixture.editor)
    return builder.build()
  }

  private fun configurationFor(dataContext: DataContext): DAPRunConfiguration {
    val settings = ConfigurationContext.getFromContext(dataContext, "test").configuration
    assertNotNull("no run configuration from the context", settings)
    return settings!!.configuration as DAPRunConfiguration
  }
}
