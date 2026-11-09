package dev.restudio.php.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.RunManager
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.execution.ParametersListUtil
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationOptions
import dev.restudio.php.PhpBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import javax.swing.JComponent

/**
 * Runs a Laravel `artisan` command: `php artisan <command> [arguments]` in the Laravel project directory.
 *
 * Debug runs the command under Xdebug through php-debug. `artisan serve` starts the web server in a child process
 * without the Xdebug settings. To debug the web requests, use [PhpServerRunConfiguration].
 */
class ArtisanRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) : DAPRunConfiguration(project, factory, name) {
  override fun getOptions(): ArtisanRunConfigurationOptions = super.getOptions() as ArtisanRunConfigurationOptions

  var artisanCommand: String
    get() = options.artisanCommand.orEmpty()
    set(value) {
      options.artisanCommand = value
    }

  /** The arguments after the command, in the command-line syntax. */
  var arguments: String
    get() = options.arguments.orEmpty()
    set(value) {
      options.arguments = value
    }

  fun artisanScript(): String = Path.of(workingDirectory.orEmpty(), ARTISAN).toString()

  fun artisanArguments(): List<String> = listOf(artisanCommand) + ParametersListUtil.parse(arguments)

  override fun canRun(executorId: String): Boolean = executorId != DefaultRunExecutor.EXECUTOR_ID && super.canRun(executorId)

  override fun checkConfiguration() {
    if (workingDirectory.isNullOrEmpty()) throw RuntimeConfigurationError(PhpBundle.message("configuration.no.working.directory"))
    if (artisanCommand.isBlank()) throw RuntimeConfigurationError(PhpBundle.message("artisan.configuration.no.command"))
  }

  override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
    if (executor.id == DefaultRunExecutor.EXECUTOR_ID) return PhpProcessState(environment, workingDirectory.orEmpty()) { listOf(ARTISAN) + artisanArguments() }
    val launch = PhpLaunches.debug(artisanScript(), artisanArguments(), workingDirectory.orEmpty(), artisanScript(), name)
                 ?: throw ExecutionException(PhpBundle.message("debug.adapter.not.found"))
    launch.applyTo(this)
    return super.getState(executor, environment)
  }

  override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = ArtisanSettingsEditor(project)

  companion object {
    const val ARTISAN: String = "artisan"
    const val DEFAULT_COMMAND: String = "serve"
  }
}

class ArtisanRunConfigurationOptions : DAPRunConfigurationOptions() {
  var artisanCommand: String? by string(ArtisanRunConfiguration.DEFAULT_COMMAND)
  var arguments: String? by string()
}

private class ArtisanSettingsEditor(private val project: Project) : SettingsEditor<ArtisanRunConfiguration>() {
  private lateinit var command: JBTextField
  private lateinit var arguments: JBTextField
  private lateinit var workingDirectory: TextFieldWithBrowseButton

  override fun createEditor(): JComponent = panel {
    row(PhpBundle.message("artisan.configuration.command")) { command = textField().component }
    row(PhpBundle.message("artisan.configuration.arguments")) { arguments = textField().align(AlignX.FILL).component }
    row(PhpBundle.message("configuration.working.directory")) {
      workingDirectory = textFieldWithBrowseButton(FileChooserDescriptorFactory.singleDir(), project).align(AlignX.FILL).component
    }
  }

  override fun resetEditorFrom(configuration: ArtisanRunConfiguration) {
    command.text = configuration.artisanCommand
    arguments.text = configuration.arguments
    workingDirectory.text = configuration.workingDirectory.orEmpty()
  }

  override fun applyEditorTo(configuration: ArtisanRunConfiguration) {
    configuration.artisanCommand = command.text.trim()
    configuration.arguments = arguments.text.trim()
    configuration.workingDirectory = workingDirectory.text.trim()
  }
}

class ArtisanConfigurationType : ConfigurationTypeBase(
  ID,
  PhpBundle.message("artisan.configuration.type.name"),
  PhpBundle.message("artisan.configuration.type.description"),
  AllIcons.RunConfigurations.Application,
) {
  init {
    addFactory(object : ConfigurationFactory(this) {
      override fun getId(): String = ID

      override fun createTemplateConfiguration(project: Project) =
        ArtisanRunConfiguration(project, this, "").also { configuration -> project.basePath?.let { configuration.workingDirectory = it } }

      override fun getOptionsClass(): Class<out BaseState> = ArtisanRunConfigurationOptions::class.java
    })
  }

  companion object {
    const val ID: String = "RestudioArtisan"

    fun getInstance(): ArtisanConfigurationType = ConfigurationTypeUtil.findConfigurationType(ArtisanConfigurationType::class.java)
  }
}

/** Creates an `artisan serve` configuration from the `artisan` file of a Laravel project. */
class ArtisanRunConfigurationProducer : LazyRunConfigurationProducer<ArtisanRunConfiguration>() {
  override fun getConfigurationFactory(): ConfigurationFactory = ArtisanConfigurationType.getInstance().configurationFactories.single()

  override fun setupConfigurationFromContext(configuration: ArtisanRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
    val root = laravelRootFor(context) ?: return false
    configuration.workingDirectory = root.path
    configuration.artisanCommand = ArtisanRunConfiguration.DEFAULT_COMMAND
    configuration.name = PhpBundle.message("artisan.configuration.name", ArtisanRunConfiguration.DEFAULT_COMMAND)
    return true
  }

  override fun isConfigurationFromContext(configuration: ArtisanRunConfiguration, context: ConfigurationContext): Boolean =
    laravelRootFor(context)?.path == configuration.workingDirectory && configuration.artisanCommand == ArtisanRunConfiguration.DEFAULT_COMMAND

  private fun laravelRootFor(context: ConfigurationContext): VirtualFile? {
    val file = context.location?.virtualFile ?: return null
    if (file.name != ArtisanRunConfiguration.ARTISAN || file.isDirectory) return null
    return file.parent?.takeIf { isLaravelRoot(it) }
  }

  companion object {
    fun isLaravelRoot(directory: VirtualFile): Boolean =
      directory.findChild(ArtisanRunConfiguration.ARTISAN)?.isDirectory == false && directory.findChild("composer.json") != null
  }
}

/**
 * Adds one `artisan serve` configuration when the project opens and has a Laravel project in a content root.
 * It does this only once for each project, so that the user can delete the configuration.
 */
class ArtisanDetectionActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    val properties = PropertiesComponent.getInstance(project)
    if (properties.getBoolean(DETECTED_KEY)) return
    val root = readAction { ProjectRootManager.getInstance(project).contentRoots.firstOrNull(ArtisanRunConfigurationProducer::isLaravelRoot) } ?: return
    withContext(Dispatchers.EDT) {
      properties.setValue(DETECTED_KEY, true)
      val runManager = RunManager.getInstance(project)
      val type = ArtisanConfigurationType.getInstance()
      if (runManager.getConfigurationsList(type).isNotEmpty()) return@withContext
      val settings = runManager.createConfiguration(PhpBundle.message("artisan.configuration.name", ArtisanRunConfiguration.DEFAULT_COMMAND), type.configurationFactories.single())
      (settings.configuration as ArtisanRunConfiguration).apply {
        workingDirectory = root.path
        artisanCommand = ArtisanRunConfiguration.DEFAULT_COMMAND
      }
      runManager.addConfiguration(settings)
      if (runManager.selectedConfiguration == null) runManager.selectedConfiguration = settings
    }
  }

  private companion object {
    const val DETECTED_KEY = "restudio.php.artisan.detected"
  }
}
