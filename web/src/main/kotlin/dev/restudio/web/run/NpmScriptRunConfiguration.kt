package dev.restudio.web.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
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
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationOptions
import dev.restudio.core.entrypoints.ContextEntryPoints
import dev.restudio.core.sdk.ToolLocator
import dev.restudio.web.WebBundle
import dev.restudio.web.sdk.NodeSdk
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/** Runs a `package.json` script with the package manager of the project, or debugs it with js-debug. */
class NpmScriptRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) : DAPRunConfiguration(project, factory, name) {
  override fun getOptions(): NpmScriptRunConfigurationOptions = super.getOptions() as NpmScriptRunConfigurationOptions

  var script: String
    get() = options.script.orEmpty()
    set(value) {
      options.script = value
    }

  override fun canRun(executorId: String): Boolean = executorId != DefaultRunExecutor.EXECUTOR_ID && super.canRun(executorId)

  override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? =
    if (executor.id == DefaultRunExecutor.EXECUTOR_ID) NpmScriptRunState(environment, this) else super.getState(executor, environment)
}

class NpmScriptRunConfigurationOptions : DAPRunConfigurationOptions() {
  var script: String? by string()
}

class NpmScriptRunState(environment: ExecutionEnvironment, private val configuration: NpmScriptRunConfiguration) : CommandLineState(environment) {
  fun commandLine(): GeneralCommandLine {
    val root = Path.of(configuration.workingDirectory)
    val manager = NodeSdk.packageManager(root)
    val command = ToolLocator.find(manager) ?: throw ExecutionException(WebBundle.message("tool.not.found", manager))
    return GeneralCommandLine(command.toString(), "run", configuration.script)
      .withWorkingDirectory(root)
      .withEnvironment(NodeSdk.environment())
      .withCharset(StandardCharsets.UTF_8)
  }

  override fun startProcess(): ProcessHandler = KillableColoredProcessHandler(commandLine()).also { ProcessTerminatedListener.attach(it) }
}

class NpmScriptConfigurationType : ConfigurationTypeBase(
  ID,
  WebBundle.message("npm.configuration.type.name"),
  WebBundle.message("npm.configuration.type.description"),
  AllIcons.RunConfigurations.Application,
) {
  init {
    addFactory(object : ConfigurationFactory(this) {
      override fun getId(): String = ID

      override fun createTemplateConfiguration(project: Project) = NpmScriptRunConfiguration(project, this, "")

      override fun getOptionsClass(): Class<out BaseState> = NpmScriptRunConfigurationOptions::class.java
    })
  }

  companion object {
    const val ID: String = "RestudioNpmScript"

    fun getInstance(): NpmScriptConfigurationType = ConfigurationTypeUtil.findConfigurationType(NpmScriptConfigurationType::class.java)
  }
}

/** Creates an [NpmScriptRunConfiguration] for a script in `package.json`. */
class NpmScriptRunConfigurationProducer : LazyRunConfigurationProducer<NpmScriptRunConfiguration>() {
  override fun getConfigurationFactory(): ConfigurationFactory = NpmScriptConfigurationType.getInstance().configurationFactories.single()

  override fun setupConfigurationFromContext(configuration: NpmScriptRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
    val (file, script) = scriptFor(context) ?: return false
    val root = file.parent.path
    val manager = NodeSdk.packageManager(Path.of(root))
    val runtime = ToolLocator.find(manager)?.toString() ?: manager
    JsDebugLaunches.node(script, root, file.path, mapOf("runtimeExecutable" to runtime, "runtimeArgs" to listOf("run", script)))?.applyTo(configuration)
    configuration.script = script
    configuration.workingDirectory = root
    configuration.name = WebBundle.message("npm.configuration.name", manager, script)
    return true
  }

  override fun isConfigurationFromContext(configuration: NpmScriptRunConfiguration, context: ConfigurationContext): Boolean {
    val (file, script) = scriptFor(context) ?: return false
    return configuration.script == script && configuration.workingDirectory == file.parent.path
  }

  private fun scriptFor(context: ConfigurationContext) =
    ContextEntryPoints.find(context, NpmScriptDetector) { it.name == "package.json" && "/node_modules/" !in it.path }?.let { (file, entryPoint) -> file to entryPoint.name }
}
