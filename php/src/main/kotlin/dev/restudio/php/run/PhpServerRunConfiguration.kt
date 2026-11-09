package dev.restudio.php.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
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
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationOptions
import dev.restudio.php.PhpBundle
import dev.restudio.php.sdk.PhpSdk
import java.nio.file.Path
import javax.swing.JComponent

/**
 * Runs the PHP built-in web server: `php -S <host>:<port> -t <document root> [router]`.
 *
 * Debug starts the same server under Xdebug through php-debug. Each request then stops at the breakpoints.
 * The debug launch is made again before each start, so that it always uses the current settings.
 */
class PhpServerRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) : DAPRunConfiguration(project, factory, name) {
  override fun getOptions(): PhpServerRunConfigurationOptions = super.getOptions() as PhpServerRunConfigurationOptions

  var host: String
    get() = options.host ?: DEFAULT_HOST
    set(value) {
      options.host = value
    }

  var port: Int
    get() = options.port
    set(value) {
      options.port = value
    }

  /** The document root. A relative path is relative to the working directory. */
  var documentRoot: String
    get() = options.documentRoot.orEmpty()
    set(value) {
      options.documentRoot = value
    }

  /** The router script, or empty for none. */
  var router: String
    get() = options.router.orEmpty()
    set(value) {
      options.router = value
    }

  /** The PHP arguments that start the server, without the router script. */
  fun serverArguments(): List<String> = listOf("-S", "$host:$port", "-t", documentRoot.ifEmpty { "." })

  fun arguments(): List<String> = serverArguments() + listOfNotNull(router.ifEmpty { null })

  override fun canRun(executorId: String): Boolean = executorId != DefaultRunExecutor.EXECUTOR_ID && super.canRun(executorId)

  override fun checkConfiguration() {
    if (workingDirectory.isNullOrEmpty()) throw RuntimeConfigurationError(PhpBundle.message("configuration.no.working.directory"))
    if (port !in 1..65535) throw RuntimeConfigurationError(PhpBundle.message("server.configuration.bad.port"))
  }

  override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
    if (executor.id == DefaultRunExecutor.EXECUTOR_ID) return PhpProcessState(environment, workingDirectory.orEmpty(), ::arguments)
    val file = router.ifEmpty { Path.of(workingDirectory.orEmpty(), documentRoot, "index.php").toString() }
    val launch = PhpLaunches.debug(router.ifEmpty { null }, emptyList(), workingDirectory.orEmpty(), file, name, serverArguments())
                 ?: throw ExecutionException(PhpBundle.message("debug.adapter.not.found"))
    launch.applyTo(this)
    return super.getState(executor, environment)
  }

  override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = PhpServerSettingsEditor(project)

  companion object {
    const val DEFAULT_HOST: String = "localhost"
    const val DEFAULT_PORT: Int = 8000
  }
}

class PhpServerRunConfigurationOptions : DAPRunConfigurationOptions() {
  var host: String? by string(PhpServerRunConfiguration.DEFAULT_HOST)
  var port: Int by property(PhpServerRunConfiguration.DEFAULT_PORT)
  var documentRoot: String? by string()
  var router: String? by string()
}

private class PhpServerSettingsEditor(private val project: Project) : SettingsEditor<PhpServerRunConfiguration>() {
  private lateinit var host: JBTextField
  private lateinit var port: JBTextField
  private lateinit var documentRoot: TextFieldWithBrowseButton
  private lateinit var router: TextFieldWithBrowseButton
  private lateinit var workingDirectory: TextFieldWithBrowseButton

  override fun createEditor(): JComponent = panel {
    row(PhpBundle.message("server.configuration.host")) { host = textField().component }
    row(PhpBundle.message("server.configuration.port")) { port = intTextField(1..65535).component }
    row(PhpBundle.message("server.configuration.document.root")) {
      documentRoot = textFieldWithBrowseButton(FileChooserDescriptorFactory.singleDir(), project).align(AlignX.FILL).component
    }
    row(PhpBundle.message("server.configuration.router")) {
      router = textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile(), project).align(AlignX.FILL).component
    }
    row(PhpBundle.message("configuration.working.directory")) {
      workingDirectory = textFieldWithBrowseButton(FileChooserDescriptorFactory.singleDir(), project).align(AlignX.FILL).component
    }
  }

  override fun resetEditorFrom(configuration: PhpServerRunConfiguration) {
    host.text = configuration.host
    port.text = configuration.port.toString()
    documentRoot.text = configuration.documentRoot
    router.text = configuration.router
    workingDirectory.text = configuration.workingDirectory.orEmpty()
  }

  override fun applyEditorTo(configuration: PhpServerRunConfiguration) {
    configuration.host = host.text.trim().ifEmpty { PhpServerRunConfiguration.DEFAULT_HOST }
    configuration.port = port.text.trim().toIntOrNull() ?: PhpServerRunConfiguration.DEFAULT_PORT
    configuration.documentRoot = documentRoot.text.trim()
    configuration.router = router.text.trim()
    configuration.workingDirectory = workingDirectory.text.trim()
  }
}

class PhpServerConfigurationType : ConfigurationTypeBase(
  ID,
  PhpBundle.message("server.configuration.type.name"),
  PhpBundle.message("server.configuration.type.description"),
  AllIcons.RunConfigurations.Web_app,
) {
  init {
    addFactory(object : ConfigurationFactory(this) {
      override fun getId(): String = ID

      override fun createTemplateConfiguration(project: Project) =
        PhpServerRunConfiguration(project, this, "").also { configuration -> project.basePath?.let { configuration.workingDirectory = it } }

      override fun getOptionsClass(): Class<out BaseState> = PhpServerRunConfigurationOptions::class.java
    })
  }

  companion object {
    const val ID: String = "RestudioPhpServer"

    fun getInstance(): PhpServerConfigurationType = ConfigurationTypeUtil.findConfigurationType(PhpServerConfigurationType::class.java)
  }
}

/**
 * Creates a [PhpServerRunConfiguration] for a directory with `index.php`, for example Laravel `public/`.
 * The working directory is the Composer project of the directory, or else the directory itself.
 */
class PhpServerRunConfigurationProducer : LazyRunConfigurationProducer<PhpServerRunConfiguration>() {
  override fun getConfigurationFactory(): ConfigurationFactory = PhpServerConfigurationType.getInstance().configurationFactories.single()

  override fun setupConfigurationFromContext(configuration: PhpServerRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
    val directory = documentRootFor(context) ?: return false
    val root = PhpSdk.composerRoot(directory) ?: directory
    configuration.workingDirectory = root.path
    configuration.documentRoot = Path.of(root.path).relativize(Path.of(directory.path)).toString().ifEmpty { "." }
    configuration.name = PhpBundle.message("server.configuration.name", directory.name)
    return true
  }

  override fun isConfigurationFromContext(configuration: PhpServerRunConfiguration, context: ConfigurationContext): Boolean {
    val directory = documentRootFor(context) ?: return false
    return Path.of(configuration.workingDirectory.orEmpty()).resolve(configuration.documentRoot).normalize() == Path.of(directory.path)
  }

  private fun documentRootFor(context: ConfigurationContext): VirtualFile? {
    val directory = (context.psiLocation as? PsiDirectory)?.virtualFile ?: return null
    if ("/vendor/" in directory.path + "/" || directory.findChild("index.php") == null) return null
    return directory
  }
}
