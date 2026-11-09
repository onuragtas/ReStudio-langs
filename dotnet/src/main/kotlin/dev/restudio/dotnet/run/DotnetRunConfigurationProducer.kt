package dev.restudio.dotnet.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import dev.restudio.core.entrypoints.ContextEntryPoints
import dev.restudio.core.entrypoints.EntryPoint
import dev.restudio.dotnet.DotnetBundle
import dev.restudio.dotnet.project.DotnetProject

/** Creates a [DotnetRunConfiguration] for the entry point of an executable project. */
class DotnetRunConfigurationProducer : LazyRunConfigurationProducer<DotnetRunConfiguration>() {
  override fun getConfigurationFactory(): ConfigurationFactory = DotnetRunConfigurationType.getInstance().configurationFactories.single()

  override fun setupConfigurationFromContext(configuration: DotnetRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
    val (project, file) = projectFor(context) ?: return false
    DotnetLaunches.debug(project, file, LaunchSettings.select(LaunchSettings.read(project.directory), null))?.applyTo(configuration)
    configuration.launchProfile = ""
    configuration.appliedLaunchProfile = ""
    configuration.projectFile = project.file.toString()
    configuration.workingDirectory = project.directory.toString()
    configuration.name = DotnetBundle.message("run.configuration.name", project.name)
    configuration.beforeRunTasks = listOf(DotnetBuildBeforeRunTask())
    return true
  }

  override fun isConfigurationFromContext(configuration: DotnetRunConfiguration, context: ConfigurationContext): Boolean =
    projectFor(context)?.first?.file?.toString() == configuration.projectFile

  private fun projectFor(context: ConfigurationContext): Pair<DotnetProject, String>? {
    val (file, entryPoint) = ContextEntryPoints.find(context, CSharpEntryPointDetector) { it.extension == "cs" } ?: return null
    if (entryPoint.kind != EntryPoint.Kind.MAIN) return null
    val project = DotnetProject.find(file) ?: return null
    return if (project.isExecutable) project to file.path else null
  }
}
