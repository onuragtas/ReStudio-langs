package dev.restudio.php.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.panel
import com.redhat.devtools.lsp4ij.LanguageServerManager
import dev.restudio.php.PhpBundle
import dev.restudio.php.lsp.PhpServerFactory

/** Settings > Languages & Frameworks > PHP: the choice of the language server. */
class PhpConfigurable : BoundConfigurable(PhpBundle.message("settings.display.name")) {
  private val settings = PhpSettings.getInstance()

  override fun createPanel(): DialogPanel = panel {
    buttonsGroup(PhpBundle.message("settings.server")) {
      row {
        radioButton(PhpBundle.message("settings.server.phpactor"), PhpLanguageServer.PHPACTOR)
          .comment(PhpBundle.message("settings.server.phpactor.comment"))
      }
      row {
        radioButton(PhpBundle.message("settings.server.intelephense"), PhpLanguageServer.INTELEPHENSE)
          .comment(PhpBundle.message("settings.server.intelephense.comment"))
      }
    }.bind(settings::languageServer)
  }

  override fun apply() {
    val before = settings.languageServer
    super.apply()
    if (settings.languageServer == before) return
    // The factory reads the setting when the server starts. Thus a restart applies the new choice.
    for (project in ProjectManager.getInstance().openProjects) {
      val manager = LanguageServerManager.getInstance(project)
      manager.stop(PhpServerFactory.SERVER_ID)
      manager.start(PhpServerFactory.SERVER_ID)
    }
  }
}
