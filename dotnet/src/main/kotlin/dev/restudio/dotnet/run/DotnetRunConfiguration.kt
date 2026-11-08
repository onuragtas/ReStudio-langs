package dev.restudio.dotnet.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationOptions
import dev.restudio.dotnet.DotnetBundle
import dev.restudio.dotnet.sdk.DotnetSdk
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Runs or debugs a .NET project.
 *
 * The Run executor starts `dotnet run --project`. The Debug executor starts netcoredbg through LSP4IJ,
 * after the `dotnet build` step of [DotnetBuildBeforeRunTaskProvider].
 */
class DotnetRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) : DAPRunConfiguration(project, factory, name) {
  override fun getOptions(): DotnetRunConfigurationOptions = super.getOptions() as DotnetRunConfigurationOptions

  /** The path of the `.csproj` file. */
  var projectFile: String
    get() = options.projectFile.orEmpty()
    set(value) {
      options.projectFile = value
    }

  override fun canRun(executorId: String): Boolean = executorId != DefaultRunExecutor.EXECUTOR_ID && super.canRun(executorId)

  override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? =
    if (executor.id == DefaultRunExecutor.EXECUTOR_ID) DotnetRunState(environment, this) else super.getState(executor, environment)
}

class DotnetRunConfigurationOptions : DAPRunConfigurationOptions() {
  var projectFile: String? by string()
}

/** Starts `dotnet run --project` and shows its output in a console. */
class DotnetRunState(environment: ExecutionEnvironment, private val configuration: DotnetRunConfiguration) : CommandLineState(environment) {
  fun commandLine(): GeneralCommandLine {
    val dotnet = DotnetSdk.command() ?: throw com.intellij.execution.ExecutionException(DotnetBundle.message("dotnet.not.found"))
    return GeneralCommandLine(dotnet.toString(), "run", "--project", configuration.projectFile)
      .withWorkingDirectory(Path.of(configuration.workingDirectory))
      .withEnvironment(DotnetSdk.environment())
      .withCharset(StandardCharsets.UTF_8)
  }

  override fun startProcess(): ProcessHandler = KillableColoredProcessHandler(commandLine()).also { ProcessTerminatedListener.attach(it) }
}

class DotnetRunConfigurationType : ConfigurationTypeBase(
  ID,
  DotnetBundle.message("run.configuration.type.name"),
  DotnetBundle.message("run.configuration.type.description"),
  AllIcons.RunConfigurations.Application,
) {
  init {
    addFactory(object : ConfigurationFactory(this) {
      override fun getId(): String = ID

      override fun createTemplateConfiguration(project: Project) = DotnetRunConfiguration(project, this, "")

      override fun getOptionsClass(): Class<out BaseState> = DotnetRunConfigurationOptions::class.java
    })
  }

  companion object {
    const val ID: String = "RestudioDotnetRun"

    fun getInstance(): DotnetRunConfigurationType = ConfigurationTypeUtil.findConfigurationType(DotnetRunConfigurationType::class.java)
  }
}
