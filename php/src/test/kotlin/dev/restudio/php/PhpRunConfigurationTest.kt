package dev.restudio.php

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviders
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
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
import dev.restudio.php.debug.PhpDebugDescriptorFactory
import dev.restudio.php.run.PhpLineMarkerProvider
import dev.restudio.php.run.PhpScriptRunConfiguration
import dev.restudio.php.sdk.PhpSdk
import dev.restudio.php.test.PhpQnLocator
import dev.restudio.php.test.PhpUnitRunConfiguration
import dev.restudio.php.test.PhpUnitRunState
import java.nio.file.Path

class PhpRunConfigurationTest : BasePlatformTestCase() {
  private val sample = Path.of("../testdata/php").toAbsolutePath().normalize()

  private fun sampleFile(path: String): VirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sample.resolve(path))!!

  private fun markers(file: VirtualFile): List<EntryPointLineMarkerInfo> {
    val psiFile = PsiManager.getInstance(project).findFile(file)!!
    val result = mutableListOf<LineMarkerInfo<*>>()
    PhpLineMarkerProvider().collectSlowLineMarkers(SyntaxTraverser.psiTraverser(psiFile).filter { it.firstChild == null }.toList(), result)
    return result.map { it as EntryPointLineMarkerInfo }
  }

  private fun configurations(location: Location<*>): List<RunConfiguration> {
    val dataContext = SimpleDataContext.builder()
      .add(CommonDataKeys.PROJECT, project)
      .add(PlatformCoreDataKeys.MODULE, myFixture.module)
      .add(CommonDataKeys.PSI_ELEMENT, location.psiElement)
      .add(Location.DATA_KEY, location)
      .build()
    return ConfigurationContext.getFromContext(dataContext, "test").configurationsFromContext.orEmpty().map { it.configuration }
  }

  fun `test the provider is registered for the language of a PHP file`() {
    val file = PsiManager.getInstance(project).findFile(sampleFile("tests/Unit/ExampleTest.php"))!!
    assertTrue(file.language.id, LineMarkerProviders.getInstance().allForLanguage(file.language).any { it is PhpLineMarkerProvider })
  }

  fun `test scripts run, Laravel and vendor files do not`() {
    assertTrue(PhpLineMarkerProvider.isRunnable(sampleFile("scripts/probe.php")))
    assertTrue(PhpLineMarkerProvider.isRunnable(sampleFile("tests/Unit/ExampleTest.php")))
    assertFalse(PhpLineMarkerProvider.isRunnable(sampleFile("routes/web.php")))
    assertFalse(PhpLineMarkerProvider.isRunnable(sampleFile("vendor/autoload.php")))
    assertEquals(emptyList<EntryPointLineMarkerInfo>(), markers(sampleFile("routes/web.php")))
  }

  fun `test the script producer runs the file from the project view`() {
    val script = PsiManager.getInstance(project).findFile(sampleFile("scripts/probe.php"))!!
    val configuration = configurations(PsiLocation(script)).single() as PhpScriptRunConfiguration
    assertEquals("PHP: probe.php", configuration.name)
    assertEquals(sample.toString(), configuration.workingDirectory)
    assertFalse(configuration.canRun(DefaultRunExecutor.EXECUTOR_ID))
    if (PhpSdk.phpDebugAdapter() == null || PhpSdk.node() == null) return
    assertEquals(PhpDebugDescriptorFactory.SERVER_ID, configuration.serverId)
    assertTrue(configuration.launchConfiguration, configuration.launchConfiguration.contains("-dxdebug.mode=debug"))
  }

  fun `test PHPUnit on the sample fills the tree and the hint opens the method`() {
    if (PhpSdk.php() == null) return
    val marker = markers(sampleFile("tests/Unit/ExampleTest.php")).single()
    val configuration = configurations(EntryPointLocation(marker.element!!, marker.entryPoint)).single() as PhpUnitRunConfiguration
    assertEquals("test_that_true_is_true", configuration.testMethod)
    val environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
    val state = configuration.getState(DefaultRunExecutor.getRunExecutorInstance(), environment) as PhpUnitRunState
    val output = CapturingProcessHandler(state.commandLine()).runProcess(120_000)
    assertTrue(output.stdout, output.stdout.contains("##teamcity[testStarted name='test_that_true_is_true'"))
    assertTrue(output.stdout, output.stdout.contains("##teamcity[testFinished name='test_that_true_is_true'"))
    val hint = Regex("""testStarted name='test_that_true_is_true' locationHint='php_qn://([^']+)'""").find(output.stdout)!!.groupValues[1]
    val file = sampleFile("tests/Unit/ExampleTest.php")
    val location = PhpQnLocator.getLocation("php_qn", hint, project, GlobalSearchScope.filesScope(project, listOf(file))).single()
    assertEquals(String(file.contentsToByteArray()).indexOf("function test_that_true_is_true"), location.openFileDescriptor!!.offset)
  }
}
