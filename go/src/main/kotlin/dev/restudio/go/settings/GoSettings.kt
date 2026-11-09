package dev.restudio.go.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** The Go settings of a project. */
@Service(Service.Level.PROJECT)
@State(name = "RestudioGoSettings", storages = [Storage("restudio-go.xml")])
class GoSettings : SimplePersistentStateComponent<GoSettings.Options>(Options()) {
  class Options : BaseState() {
    /** When `true`, a save organizes the imports with gopls, as `goimports` does. */
    var organizeImportsOnSave by property(true)
  }

  var organizeImportsOnSave: Boolean
    get() = state.organizeImportsOnSave
    set(value) {
      state.organizeImportsOnSave = value
    }

  companion object {
    fun getInstance(project: Project): GoSettings = project.service()
  }
}
