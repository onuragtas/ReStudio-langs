package dev.restudio.dotnet.debug

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.VirtualConfigurationType
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationOptions
import dev.restudio.dotnet.DotnetBundle

/** Attaches netcoredbg to the .NET process [processId]. "Attach to Process" creates it; the user does not. */
class DotnetAttachConfiguration(project: Project, factory: ConfigurationFactory, name: String) : DAPRunConfiguration(project, factory, name) {
  override fun getOptions(): DotnetAttachConfigurationOptions = super.getOptions() as DotnetAttachConfigurationOptions

  var processId: Int
    get() = options.processId
    set(value) {
      options.processId = value
    }

  override fun canRun(executorId: String): Boolean = executorId != DefaultRunExecutor.EXECUTOR_ID && super.canRun(executorId)

  override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
    if (executor.id == DefaultRunExecutor.EXECUTOR_ID || processId <= 0) throw ExecutionException(DotnetBundle.message("attach.debug.only"))
    val target = NetcoredbgAttachState.Target(processId)
    return NetcoredbgAttachState.create(this, options, environment) { target }
  }
}

class DotnetAttachConfigurationOptions : DAPRunConfigurationOptions() {
  var processId: Int by property(0)
}

/** Hidden from the Run/Debug Configurations dialog, because only "Attach to Process" creates such a configuration. */
class DotnetAttachConfigurationType : ConfigurationTypeBase(
  ID,
  DotnetBundle.message("attach.configuration.type.name"),
  DotnetBundle.message("attach.configuration.type.description"),
  AllIcons.Debugger.AttachToProcess,
), VirtualConfigurationType {
  init {
    addFactory(object : ConfigurationFactory(this) {
      override fun getId(): String = ID

      override fun createTemplateConfiguration(project: Project) = DotnetAttachConfiguration(project, this, "")

      override fun getOptionsClass(): Class<out BaseState> = DotnetAttachConfigurationOptions::class.java
    })
  }

  companion object {
    const val ID: String = "RestudioDotnetAttach"

    fun getInstance(): DotnetAttachConfigurationType = ConfigurationTypeUtil.findConfigurationType(DotnetAttachConfigurationType::class.java)
  }
}
