package dev.restudio.dotnet.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.options.SettingsEditorGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationOptions
import dev.restudio.dotnet.DotnetBundle
import dev.restudio.dotnet.project.DotnetProject
import dev.restudio.dotnet.sdk.DotnetSdk
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Runs or debugs a .NET project.
 *
 * The Run executor starts `dotnet run --project`. The Debug executor starts netcoredbg through LSP4IJ,
 * after the `dotnet build` step of [DotnetBuildBeforeRunTaskProvider].
 * [launchProfile] selects a profile of `Properties/launchSettings.json`. Empty selects the first `Project` profile.
 */
class DotnetRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) : DAPRunConfiguration(project, factory, name) {
  override fun getOptions(): DotnetRunConfigurationOptions = super.getOptions() as DotnetRunConfigurationOptions

  /** The path of the `.csproj` file. */
  var projectFile: String
    get() = options.projectFile.orEmpty()
    set(value) {
      options.projectFile = value
    }

  /** The name of the `launchSettings.json` profile. Empty selects the default profile. */
  var launchProfile: String
    get() = options.launchProfile.orEmpty()
    set(value) {
      options.launchProfile = value
    }

  /** The profile that the netcoredbg launch contains now. `null` when the launch has no profile yet. */
  var appliedLaunchProfile: String?
    get() = options.appliedLaunchProfile
    set(value) {
      options.appliedLaunchProfile = value
    }

  override fun canRun(executorId: String): Boolean = executorId != DefaultRunExecutor.EXECUTOR_ID && super.canRun(executorId)

  override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
    if (executor.id == DefaultRunExecutor.EXECUTOR_ID) return DotnetRunState(environment, this)
    if (appliedLaunchProfile != launchProfile) applyLaunchProfile()
    return super.getState(executor, environment)
  }

  @Suppress("UNCHECKED_CAST")
  override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = SettingsEditorGroup<DotnetRunConfiguration>().apply {
    addEditor(DotnetBundle.message("run.editor.project.tab"), DotnetRunSettingsEditor(project))
    addEditor(DotnetBundle.message("run.editor.debugger.tab"), super.getConfigurationEditor() as SettingsEditor<DotnetRunConfiguration>)
  }

  /** Writes the netcoredbg launch again with the arguments, the environment and the URLs of [launchProfile]. */
  private fun applyLaunchProfile() {
    val file = LocalFileSystem.getInstance().findFileByPath(projectFile) ?: return
    val dotnetProject = DotnetProject.find(file) ?: return
    val profile = LaunchSettings.select(LaunchSettings.read(dotnetProject.directory), launchProfile)
    val savedFile = this.file
    DotnetLaunches.debug(dotnetProject, savedFile ?: projectFile, profile)?.applyTo(this)
    appliedLaunchProfile = launchProfile
  }
}

class DotnetRunConfigurationOptions : DAPRunConfigurationOptions() {
  var projectFile: String? by string()
  var launchProfile: String? by string()
  var appliedLaunchProfile: String? by string()
}

/** Starts `dotnet run --project` and shows its output in a console. */
class DotnetRunState(environment: ExecutionEnvironment, private val configuration: DotnetRunConfiguration) : CommandLineState(environment) {
  fun commandLine(): GeneralCommandLine {
    val dotnet = DotnetSdk.command() ?: throw ExecutionException(DotnetBundle.message("dotnet.not.found"))
    val commandLine = GeneralCommandLine(dotnet.toString(), "run", "--project", configuration.projectFile)
    if (configuration.launchProfile.isNotEmpty()) commandLine.addParameters("--launch-profile", configuration.launchProfile)
    return commandLine
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
