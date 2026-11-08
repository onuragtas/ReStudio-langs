package dev.restudio.web

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.daemon.LineMarkerProviders
import com.intellij.execution.Location
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.restudio.core.entrypoints.EntryPointLineMarkerInfo
import dev.restudio.core.entrypoints.EntryPointLocation
import dev.restudio.core.testing.FileLineLocator
import dev.restudio.web.lsp.AngularTemplateMatcher
import dev.restudio.web.lsp.TailwindFileMatcher
import dev.restudio.web.run.NpmScriptLineMarkerProvider
import dev.restudio.web.run.NpmScriptRunConfiguration
import dev.restudio.web.run.VitestLineMarkerProvider
import dev.restudio.web.sdk.NodeSdk
import dev.restudio.web.test.VitestRunConfiguration
import dev.restudio.web.test.VitestRunState
import java.nio.file.Path

class WebRunConfigurationTest : BasePlatformTestCase() {
  private val web = Path.of("../testdata/web").toAbsolutePath().normalize()

  private fun sampleFile(path: String): VirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(web.resolve(path))!!

  private fun markers(file: VirtualFile, provider: LineMarkerProvider): List<EntryPointLineMarkerInfo> {
    val psiFile = PsiManager.getInstance(project).findFile(file)!!
    val result = mutableListOf<LineMarkerInfo<*>>()
    provider.collectSlowLineMarkers(SyntaxTraverser.psiTraverser(psiFile).filter { it.firstChild == null }.toList(), result)
    return result.map { it as EntryPointLineMarkerInfo }
  }

  private fun configuration(marker: EntryPointLineMarkerInfo): RunConfiguration {
    val location = EntryPointLocation(marker.element!!, marker.entryPoint)
    val dataContext = SimpleDataContext.builder()
      .add(CommonDataKeys.PROJECT, project)
      .add(PlatformCoreDataKeys.MODULE, myFixture.module)
      .add(CommonDataKeys.PSI_ELEMENT, location.psiElement)
      .add(Location.DATA_KEY, location)
      .build()
    return ConfigurationContext.getFromContext(dataContext, "test").configurationsFromContext.orEmpty().map { it.configuration }.single()
  }

  private fun registered(file: VirtualFile, type: Class<*>): Boolean {
    val language = PsiManager.getInstance(project).findFile(file)!!.language
    return LineMarkerProviders.getInstance().allForLanguage(language).any { type.isInstance(it) }
  }

  fun `test the providers are registered for the languages of the files`() {
    assertTrue(registered(sampleFile("react/package.json"), NpmScriptLineMarkerProvider::class.java))
    assertTrue(registered(sampleFile("vue/src/lib/total.test.ts"), VitestLineMarkerProvider::class.java))
    val jsTest = myFixture.addFileToProject("sum.test.js", "test('a', () => {})\n").virtualFile
    assertTrue(registered(jsTest, VitestLineMarkerProvider::class.java))
  }

  fun `test an npm script runs with its package manager and debugs with js-debug`() {
    val marker = markers(sampleFile("react/package.json"), NpmScriptLineMarkerProvider()).first { it.entryPoint.name == "dev" }
    val configuration = configuration(marker) as NpmScriptRunConfiguration
    assertEquals("npm run dev", configuration.name)
    assertEquals(web.resolve("react").toString(), configuration.workingDirectory)
    if (NodeSdk.jsDebugServer() == null) return
    assertTrue(configuration.command, configuration.command.endsWith("dapDebugServer.js \${port} 127.0.0.1"))
    assertTrue(configuration.launchConfiguration, configuration.launchConfiguration.contains("\"runtimeArgs\": [\"run\", \"dev\"]"))
  }

  fun `test Vitest runs one test through the reporter and the hint opens it`() {
    val file = sampleFile("vue/src/lib/total.test.ts")
    val marker = markers(file, VitestLineMarkerProvider()).first { it.entryPoint.name == "total > adds the prices" }
    val configuration = configuration(marker) as VitestRunConfiguration
    assertEquals(listOf("run", file.path, "-t", "^total > adds the prices($| > )"), configuration.vitestArguments())
    if (NodeSdk.node() == null) return
    val environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
    val state = configuration.getState(DefaultRunExecutor.getRunExecutorInstance(), environment) as VitestRunState
    val output = CapturingProcessHandler(state.commandLine()).runProcess(180_000).stdout
    assertTrue(output, output.contains("##teamcity[testFinished name='adds the prices'"))
    assertFalse(output, output.contains("##teamcity[testIgnored name='adds the prices'"))
    assertTrue(output, output.contains("##teamcity[testIgnored name='returns zero for no prices'"))
    val hint = Regex("""testStarted name='adds the prices' nodeId='[^']*' parentNodeId='[^']*' locationHint='file://([^']+)'""").find(output)!!.groupValues[1]
    val location = FileLineLocator.getLocation("file", hint, project, GlobalSearchScope.filesScope(project, listOf(file))).single()
    val text = String(file.contentsToByteArray())
    assertEquals(text.indexOf("it('adds the prices'"), location.openFileDescriptor!!.offset)
  }

  fun `test Angular and Tailwind start only in their projects`() {
    assertTrue(AngularTemplateMatcher().match(sampleFile("angular/src/app/app.html"), project))
    assertFalse(AngularTemplateMatcher().match(sampleFile("react/index.html"), project))
    assertTrue(TailwindFileMatcher().match(sampleFile("tailwind/index.html"), project))
    assertFalse(TailwindFileMatcher().match(sampleFile("react/index.html"), project))
  }
}
