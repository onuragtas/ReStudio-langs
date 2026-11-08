package dev.restudio.go.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration
import dev.restudio.core.dap.DapLaunch
import dev.restudio.core.entrypoints.EntryPoint

/** Creates a Delve run configuration for `func main()`. [dev.restudio.go.test.GoTestRunConfigurationProducer] does the tests. */
class GoRunConfigurationProducer : LazyRunConfigurationProducer<DAPRunConfiguration>() {
  override fun getConfigurationFactory(): ConfigurationFactory = DapLaunch.configurationFactory()

  override fun setupConfigurationFromContext(
    configuration: DAPRunConfiguration,
    context: ConfigurationContext,
    sourceElement: Ref<PsiElement>,
  ): Boolean {
    val (name, launch) = launchFor(context) ?: return false
    launch.applyTo(configuration)
    configuration.name = name
    return true
  }

  override fun isConfigurationFromContext(configuration: DAPRunConfiguration, context: ConfigurationContext): Boolean =
    launchFor(context)?.second?.matches(configuration) == true

  private fun launchFor(context: ConfigurationContext): Pair<String, DapLaunch>? {
    val (file, entryPoint) = GoContextEntryPoint.find(context) ?: return null
    if (entryPoint.kind != EntryPoint.Kind.MAIN) return null
    return GoLaunches.create(file, entryPoint)
  }
}
