package dev.restudio.php.docker

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import dev.restudio.core.lsp.ManagedServers
import dev.restudio.php.PhpBundle
import dev.restudio.php.lsp.PhpServerFactory

/** Settings > Languages & Frameworks > PHP > Docker: the container that runs Phpactor for this project. */
class PhpDockerConfigurable(private val project: Project) : BoundConfigurable(PhpBundle.message("docker.settings.display.name")) {
  private val settings = PhpDockerSettings.getInstance(project)

  override fun createPanel(): DialogPanel = panel {
    row { text(PhpBundle.message("docker.settings.description")) }
    row(PhpBundle.message("docker.settings.container")) {
      textField().bindText(settings::container).align(AlignX.FILL)
        .comment(PhpBundle.message("docker.settings.container.comment"))
    }
    row(PhpBundle.message("docker.settings.path")) {
      textField().bindText(settings::containerPath).align(AlignX.FILL)
        .comment(PhpBundle.message("docker.settings.path.comment", project.basePath.orEmpty()))
    }
  }

  override fun apply() {
    val before = settings.container to settings.containerPath
    super.apply()
    // The factory reads the settings when the server starts. Thus a restart applies them.
    if (before != settings.container to settings.containerPath) ManagedServers.restart(project, PhpServerFactory.SERVER_ID)
  }
}
