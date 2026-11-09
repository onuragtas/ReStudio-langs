package dev.restudio.web.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationOptions
import dev.restudio.web.WebBundle
import dev.restudio.web.sdk.NodeSdk
import javax.swing.JComponent

/** Debugs a web page in Chrome with js-debug (`pwa-chrome`). The user sets the page URL and the web root. */
class ChromeDebugRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) : DAPRunConfiguration(project, factory, name) {
  override fun getOptions(): ChromeDebugRunConfigurationOptions = super.getOptions() as ChromeDebugRunConfigurationOptions

  var url: String
    get() = options.url.orEmpty()
    set(value) {
      options.url = value
    }

  /** The directory that the page URLs map to, for example the project root of a Vite application. */
  var webRoot: String
    get() = options.webRoot.orEmpty()
    set(value) {
      options.webRoot = value
    }

  /** Writes the js-debug launch for [url] and [webRoot]. Returns `false` when js-debug or Node is not installed. */
  fun updateLaunch(): Boolean {
    val launch = JsDebugLaunches.chrome(name, url, webRoot) ?: return false
    launch.applyTo(this)
    return true
  }

  override fun checkConfiguration() {
    if (url.isBlank()) throw RuntimeConfigurationError(WebBundle.message("chrome.url.empty"))
    if (NodeSdk.jsDebugServer() == null) throw RuntimeConfigurationError(WebBundle.message("js.debug.not.found"))
    // The DAP fields come from [updateLaunch] when the configuration starts, so the DAP check does not apply.
  }

  override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
    if (!updateLaunch()) throw ExecutionException(WebBundle.message("js.debug.not.found"))
    return super.getState(executor, environment)
  }

  override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = ChromeDebugSettingsEditor()
}

class ChromeDebugRunConfigurationOptions : DAPRunConfigurationOptions() {
  var url: String? by string()
  var webRoot: String? by string()
}

/** Edits the URL and the web root of a [ChromeDebugRunConfiguration]. */
class ChromeDebugSettingsEditor : SettingsEditor<ChromeDebugRunConfiguration>() {
  private val url = JBTextField()
  private val webRoot = JBTextField()

  override fun resetEditorFrom(configuration: ChromeDebugRunConfiguration) {
    url.text = configuration.url
    webRoot.text = configuration.webRoot
  }

  override fun applyEditorTo(configuration: ChromeDebugRunConfiguration) {
    configuration.url = url.text.trim()
    configuration.webRoot = webRoot.text.trim()
    configuration.updateLaunch()
  }

  override fun createEditor(): JComponent = panel {
    row(WebBundle.message("chrome.url")) { cell(url).align(AlignX.FILL) }
    row(WebBundle.message("chrome.web.root")) { cell(webRoot).align(AlignX.FILL) }
  }
}

class ChromeDebugConfigurationType : ConfigurationTypeBase(
  ID,
  WebBundle.message("chrome.configuration.type.name"),
  WebBundle.message("chrome.configuration.type.description"),
  AllIcons.RunConfigurations.Web_app,
) {
  init {
    addFactory(object : ConfigurationFactory(this) {
      override fun getId(): String = ID

      override fun createTemplateConfiguration(project: Project) = ChromeDebugRunConfiguration(project, this, "").apply {
        url = DEFAULT_URL
        webRoot = project.basePath.orEmpty()
      }

      override fun getOptionsClass(): Class<out BaseState> = ChromeDebugRunConfigurationOptions::class.java
    })
  }

  companion object {
    const val ID: String = "RestudioChromeDebug"

    /** The default address of the Vite dev server. */
    const val DEFAULT_URL: String = "http://localhost:5173"
  }
}
