package dev.restudio.dotnet.test

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import dev.restudio.core.entrypoints.ContextEntryPoints
import dev.restudio.core.entrypoints.EntryPoint
import dev.restudio.dotnet.DotnetBundle
import dev.restudio.dotnet.project.DotnetProject
import dev.restudio.dotnet.run.CSharpEntryPointDetector
import dev.restudio.dotnet.run.DotnetLaunches

/** Creates a [DotnetTestRunConfiguration] for one C# test method. */
class DotnetTestRunConfigurationProducer : LazyRunConfigurationProducer<DotnetTestRunConfiguration>() {
  override fun getConfigurationFactory(): ConfigurationFactory = DotnetTestConfigurationType.getInstance().configurationFactories.single()

  override fun setupConfigurationFromContext(configuration: DotnetTestRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
    val (project, test, file) = testFor(context) ?: return false
    DotnetLaunches.testAttach(project, file)?.applyTo(configuration)
    configuration.projectFile = project.file.toString()
    configuration.workingDirectory = project.directory.toString()
    configuration.testFilter = filter(test)
    configuration.name = DotnetBundle.message("test.configuration.name", test.substringAfterLast('.'))
    configuration.beforeRunTasks = listOf(DotnetTestHostBeforeRunTask())
    return true
  }

  override fun isConfigurationFromContext(configuration: DotnetTestRunConfiguration, context: ConfigurationContext): Boolean {
    val (project, test) = testFor(context) ?: return false
    return configuration.projectFile == project.file.toString() && configuration.testFilter == filter(test)
  }

  private fun testFor(context: ConfigurationContext): Triple<DotnetProject, String, String>? {
    val (file, entryPoint) = ContextEntryPoints.find(context, CSharpEntryPointDetector) { it.extension == "cs" } ?: return null
    if (entryPoint.kind != EntryPoint.Kind.TEST) return null
    val project = DotnetProject.find(file) ?: return null
    return Triple(project, entryPoint.name, file.path)
  }

  private fun filter(qualifiedName: String) = "FullyQualifiedName=$qualifiedName"
}
