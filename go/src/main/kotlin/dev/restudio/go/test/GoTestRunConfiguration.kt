package dev.restudio.go.test

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationOptions
import dev.restudio.go.GoBundle

/**
 * Runs Go tests.
 *
 * The Run executor starts `go test -json` and shows the results in the test tree.
 * The Debug executor starts Delve through LSP4IJ, with the DAP settings of the parent class.
 */
class GoTestRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
  DAPRunConfiguration(project, factory, name) {
  override fun getOptions(): GoTestRunConfigurationOptions = super.getOptions() as GoTestRunConfigurationOptions

  /** The `-run` pattern, for example `^TestHello$`. Empty runs all the tests of the package. */
  var testPattern: String
    get() = options.testPattern.orEmpty()
    set(value) {
      options.testPattern = value
    }

  /** The LSP4IJ debug runner asks this. Return `false` for Run, so that the default runner uses [getState]. */
  override fun canRun(executorId: String): Boolean = executorId != DefaultRunExecutor.EXECUTOR_ID && super.canRun(executorId)

  override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? =
    if (executor.id == DefaultRunExecutor.EXECUTOR_ID) GoTestRunState(environment, this) else super.getState(executor, environment)
}

class GoTestRunConfigurationOptions : DAPRunConfigurationOptions() {
  var testPattern: String? by string()
}

class GoTestConfigurationType : ConfigurationTypeBase(
  ID,
  GoBundle.message("test.configuration.name"),
  GoBundle.message("test.configuration.description"),
  AllIcons.Nodes.Test,
) {
  init {
    addFactory(object : ConfigurationFactory(this) {
      override fun getId(): String = ID

      override fun createTemplateConfiguration(project: Project) = GoTestRunConfiguration(project, this, "")

      override fun getOptionsClass(): Class<out BaseState> = GoTestRunConfigurationOptions::class.java
    })
  }

  companion object {
    const val ID: String = "RestudioGoTest"

    fun getInstance(): GoTestConfigurationType = ConfigurationTypeUtil.findConfigurationType(GoTestConfigurationType::class.java)
  }
}
