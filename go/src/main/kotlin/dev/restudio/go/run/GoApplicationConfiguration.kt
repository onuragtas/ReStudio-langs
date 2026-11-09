package dev.restudio.go.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationOptions
import dev.restudio.go.GoBundle
import java.nio.file.Path

/**
 * Runs a Go `main` package.
 *
 * The Run executor builds the package with `go build` and starts the program in the Run console.
 * The Debug executor starts Delve through LSP4IJ, with the DAP settings of the parent class.
 * [updateLaunch] writes the Delve launch from the Go settings, thus both executors start the same program.
 */
class GoApplicationRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
  DAPRunConfiguration(project, factory, name) {
  override fun getOptions(): GoApplicationRunConfigurationOptions = super.getOptions() as GoApplicationRunConfigurationOptions

  /** The directory of the `main` package. */
  var packageDirectory: String
    get() = options.packageDirectory.orEmpty()
    set(value) {
      options.packageDirectory = value
    }

  /** The program arguments as one command line. */
  var programArguments: String
    get() = options.programArguments.orEmpty()
    set(value) {
      options.programArguments = value
    }

  /** The environment variables of the program. The IDE adds a PATH that finds `go` when the user does not set PATH. */
  var programEnvironment: Map<String, String>
    get() = options.programEnvironment.toMap()
    set(value) {
      options.programEnvironment.clear()
      options.programEnvironment.putAll(value)
    }

  /** The working directory of the program. An empty value means the package directory. */
  val programWorkingDirectory: String
    get() = workingDirectory.orEmpty().ifEmpty { packageDirectory }

  /** Writes the Delve launch from [packageDirectory], [programArguments], [programEnvironment] and the working directory. */
  fun updateLaunch() {
    GoLaunches.application(
      name = Path.of(packageDirectory).fileName?.toString() ?: name,
      file = file ?: packageDirectory,
      packageDirectory = packageDirectory,
      workingDirectory = programWorkingDirectory,
      arguments = ParametersListUtil.parse(programArguments),
      environment = programEnvironment,
    ).applyTo(this)
  }

  /** The LSP4IJ debug runner asks this. Return `false` for Run, so that the default runner uses [getState]. */
  override fun canRun(executorId: String): Boolean = executorId != DefaultRunExecutor.EXECUTOR_ID && super.canRun(executorId)

  override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? =
    if (executor.id == DefaultRunExecutor.EXECUTOR_ID) GoApplicationRunState(environment, this) else super.getState(executor, environment)

  override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = GoApplicationSettingsEditor(project)
}

class GoApplicationRunConfigurationOptions : DAPRunConfigurationOptions() {
  var packageDirectory: String? by string()
  var programArguments: String? by string()
  val programEnvironment: MutableMap<String, String> by map()
}

class GoApplicationConfigurationType : ConfigurationTypeBase(
  ID,
  GoBundle.message("application.configuration.name"),
  GoBundle.message("application.configuration.description"),
  AllIcons.RunConfigurations.Application,
) {
  init {
    addFactory(object : ConfigurationFactory(this) {
      override fun getId(): String = ID

      override fun createTemplateConfiguration(project: Project): RunConfiguration = GoApplicationRunConfiguration(project, this, "")

      override fun getOptionsClass(): Class<out BaseState> = GoApplicationRunConfigurationOptions::class.java
    })
  }

  companion object {
    const val ID: String = "RestudioGoApplication"

    fun getInstance(): GoApplicationConfigurationType =
      ConfigurationTypeUtil.findConfigurationType(GoApplicationConfigurationType::class.java)
  }
}
