package dev.restudio.php.run

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
import dev.restudio.core.entrypoints.EntryPoint
import dev.restudio.php.PhpBundle
import dev.restudio.php.sdk.PhpSdk
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/** Runs a PHP script with `php`, or debugs it with Xdebug through php-debug. */
class PhpScriptRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) : DAPRunConfiguration(project, factory, name) {
  override fun getOptions(): PhpScriptRunConfigurationOptions = super.getOptions() as PhpScriptRunConfigurationOptions

  var scriptFile: String
    get() = options.scriptFile.orEmpty()
    set(value) {
      options.scriptFile = value
    }

  override fun canRun(executorId: String): Boolean = executorId != DefaultRunExecutor.EXECUTOR_ID && super.canRun(executorId)

  override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? =
    if (executor.id == DefaultRunExecutor.EXECUTOR_ID) PhpScriptRunState(environment, this) else super.getState(executor, environment)
}

class PhpScriptRunConfigurationOptions : DAPRunConfigurationOptions() {
  var scriptFile: String? by string()
}

class PhpScriptRunState(environment: ExecutionEnvironment, private val configuration: PhpScriptRunConfiguration) : CommandLineState(environment) {
  fun commandLine(): GeneralCommandLine {
    val php = PhpSdk.php() ?: throw ExecutionException(PhpBundle.message("php.not.found"))
    return GeneralCommandLine(php.toString(), configuration.scriptFile)
      .withWorkingDirectory(Path.of(configuration.workingDirectory))
      .withEnvironment(PhpSdk.environment())
      .withCharset(StandardCharsets.UTF_8)
  }

  override fun startProcess(): ProcessHandler = KillableColoredProcessHandler(commandLine()).also { ProcessTerminatedListener.attach(it) }
}

class PhpScriptConfigurationType : ConfigurationTypeBase(
  ID,
  PhpBundle.message("script.configuration.type.name"),
  PhpBundle.message("script.configuration.type.description"),
  AllIcons.RunConfigurations.Application,
) {
  init {
    addFactory(object : ConfigurationFactory(this) {
      override fun getId(): String = ID

      override fun createTemplateConfiguration(project: Project) = PhpScriptRunConfiguration(project, this, "")

      override fun getOptionsClass(): Class<out BaseState> = PhpScriptRunConfigurationOptions::class.java
    })
  }

  companion object {
    const val ID: String = "RestudioPhpScript"

    fun getInstance(): PhpScriptConfigurationType = ConfigurationTypeUtil.findConfigurationType(PhpScriptConfigurationType::class.java)
  }
}

/** Creates a [PhpScriptRunConfiguration] for a PHP script. */
class PhpScriptRunConfigurationProducer : LazyRunConfigurationProducer<PhpScriptRunConfiguration>() {
  override fun getConfigurationFactory(): ConfigurationFactory = PhpScriptConfigurationType.getInstance().configurationFactories.single()

  override fun setupConfigurationFromContext(configuration: PhpScriptRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
    val script = scriptFor(context) ?: return false
    val directory = (PhpSdk.composerRoot(script) ?: script.parent).path
    PhpLaunches.debug(script.path, emptyList(), directory, script.path, script.name)?.applyTo(configuration)
    configuration.scriptFile = script.path
    configuration.workingDirectory = directory
    configuration.name = PhpBundle.message("script.configuration.name", script.name)
    return true
  }

  override fun isConfigurationFromContext(configuration: PhpScriptRunConfiguration, context: ConfigurationContext): Boolean =
    scriptFor(context)?.path == configuration.scriptFile

  private fun scriptFor(context: ConfigurationContext) =
    ContextEntryPoints.find(context, PhpEntryPointDetector, PhpLineMarkerProvider::isRunnable)
      ?.takeIf { it.second.kind == EntryPoint.Kind.MAIN }?.first
}
