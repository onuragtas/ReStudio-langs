package dev.restudio.core.sdk

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import dev.restudio.core.CoreBundle
import dev.restudio.core.sdk.install.ToolInstaller
import javax.swing.JButton

/**
 * The settings page Tools > ReStudio Tools.
 *
 * For each registered [Tool] it shows the path that [ToolLocator] finds, and a field for the user override.
 * It finds the paths in a background thread, because the search reads the file system.
 */
class ToolsConfigurable : BoundSearchableConfigurable(CoreBundle.message("tools.settings.title"), "dev.restudio.tools", "dev.restudio.tools") {
  override fun createPanel() = panel {
    val settings = ApplicationManager.getApplication().getService(ToolSettings::class.java)
    row { text(CoreBundle.message("tools.settings.description")) }
    for (tool in ToolRegistry.all().sortedBy { it.displayName.lowercase() }) {
      val detected = JBLabel(CoreBundle.message("tools.settings.searching")).apply { foreground = UIUtil.getContextHelpForeground() }
      var installButton: JButton? = null
      group(tool.displayName) {
        row(CoreBundle.message("tools.settings.path")) {
          textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle(tool.displayName))
            .align(AlignX.FILL)
            .bindText({ settings.override(tool.id)?.toString().orEmpty() }, { settings.setOverride(tool.id, it) })
        }
        row("") { cell(detected) }
        val recipe = tool.recipe
        if (recipe != null) {
          row("") {
            installButton = button(CoreBundle.message("tool.install.action", recipe.version)) {
              ToolInstaller.install(null, tool) { refresh(tool, detected, installButton) }
            }.component
          }
        }
      }
      refresh(tool, detected, installButton)
    }
  }

  private fun refresh(tool: Tool, label: JBLabel, installButton: JButton?) {
    ApplicationManager.getApplication().executeOnPooledThread {
      val path = ToolLocator.detect(tool)
      val pinned = tool.recipe?.let { ToolInstaller.installedPath(tool, it) }
      ApplicationManager.getApplication().invokeLater({
        label.text = path?.let { CoreBundle.message("tools.settings.detected", it.toString()) } ?: CoreBundle.message("tools.settings.not.found")
        installButton?.isEnabled = pinned == null
      }, ModalityState.any())
    }
  }
}
