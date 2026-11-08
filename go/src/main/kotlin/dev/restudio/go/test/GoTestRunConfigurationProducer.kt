package dev.restudio.go.test

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import dev.restudio.core.entrypoints.EntryPoint
import dev.restudio.go.run.GoContextEntryPoint
import dev.restudio.go.run.GoLaunches

/** Creates a [GoTestRunConfiguration] for one Go test function. */
class GoTestRunConfigurationProducer : LazyRunConfigurationProducer<GoTestRunConfiguration>() {
  override fun getConfigurationFactory(): ConfigurationFactory = GoTestConfigurationType.getInstance().configurationFactories.single()

  override fun setupConfigurationFromContext(
    configuration: GoTestRunConfiguration,
    context: ConfigurationContext,
    sourceElement: Ref<PsiElement>,
  ): Boolean {
    val test = testFor(context) ?: return false
    val (name, launch) = test.launch
    launch.applyTo(configuration)
    configuration.testPattern = test.pattern
    configuration.name = name
    return true
  }

  override fun isConfigurationFromContext(configuration: GoTestRunConfiguration, context: ConfigurationContext): Boolean {
    val test = testFor(context) ?: return false
    return configuration.testPattern == test.pattern && test.launch.second.matches(configuration)
  }

  private fun testFor(context: ConfigurationContext): GoTest? {
    val (file, entryPoint) = GoContextEntryPoint.find(context) ?: return null
    if (entryPoint.kind != EntryPoint.Kind.TEST) return null
    return GoTest(GoLaunches.testPattern(entryPoint), GoLaunches.create(file, entryPoint))
  }

  private class GoTest(val pattern: String, val launch: Pair<String, dev.restudio.core.dap.DapLaunch>)
}
