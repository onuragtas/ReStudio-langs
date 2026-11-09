package dev.restudio.go.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import dev.restudio.go.GoBundle

/** Settings > Languages & Frameworks > Go. */
class GoSettingsConfigurable(private val project: Project) : BoundConfigurable(GoBundle.message("settings.name")) {
  override fun createPanel(): DialogPanel {
    val settings = GoSettings.getInstance(project)
    return panel {
      row {
        checkBox(GoBundle.message("settings.organize.imports.on.save"))
          .bindSelected(settings::organizeImportsOnSave)
          .comment(GoBundle.message("settings.organize.imports.comment"))
      }
    }
  }
}
