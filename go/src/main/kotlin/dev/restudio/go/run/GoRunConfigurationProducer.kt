package dev.restudio.go.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import dev.restudio.core.entrypoints.ContextEntryPoints
import dev.restudio.core.entrypoints.EntryPoint
import dev.restudio.go.GoBundle

/**
 * Creates a [GoApplicationRunConfiguration] for `func main()`. Run starts `go build` and the program, Debug starts Delve.
 * [dev.restudio.go.test.GoTestRunConfigurationProducer] does the tests.
 */
class GoRunConfigurationProducer : LazyRunConfigurationProducer<GoApplicationRunConfiguration>() {
  override fun getConfigurationFactory(): ConfigurationFactory = GoApplicationConfigurationType.getInstance().configurationFactories.single()

  override fun setupConfigurationFromContext(
    configuration: GoApplicationRunConfiguration,
    context: ConfigurationContext,
    sourceElement: Ref<PsiElement>,
  ): Boolean {
    val file = mainFile(context) ?: return false
    val packageDirectory = file.parent
    configuration.packageDirectory = packageDirectory.path
    configuration.workingDirectory = (GoLaunches.moduleRoot(file) ?: packageDirectory).path
    configuration.file = file.path
    configuration.updateLaunch()
    configuration.name = GoBundle.message("run.configuration.main", packageDirectory.name)
    return true
  }

  override fun isConfigurationFromContext(configuration: GoApplicationRunConfiguration, context: ConfigurationContext): Boolean =
    mainFile(context)?.parent?.path == configuration.packageDirectory

  private fun mainFile(context: ConfigurationContext): VirtualFile? {
    val (file, entryPoint) = ContextEntryPoints.find(context, GoEntryPointDetector) { it.extension == "go" } ?: return null
    return if (entryPoint.kind == EntryPoint.Kind.MAIN) file else null
  }
}
