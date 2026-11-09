package dev.restudio.core.sdk

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import java.nio.file.Path

/** Keeps the tool paths that the user sets on the ReStudio Tools settings page. */
@Service(Service.Level.APP)
@State(name = "ReStudioTools", storages = [Storage("restudio-tools.xml")])
class ToolSettings : SimplePersistentStateComponent<ToolSettings.Options>(Options()) {
  class Options : BaseState() {
    /** The tool ID to the absolute path of the executable. */
    var overrides: MutableMap<String, String> by map()

    fun setOverride(id: String, value: String) {
      if (value.isEmpty()) overrides.remove(id) else overrides[id] = value
      incrementModificationCount()
    }
  }

  /** Returns the path that the user set for the tool [id], or `null`. */
  fun override(id: String): Path? = state.overrides[id]?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }

  /** Sets the path of the tool [id]. An empty [path] removes the override. */
  fun setOverride(id: String, path: String) {
    state.setOverride(id, path.trim())
  }

  companion object {
    /** Returns `null` when no application runs, for example in a plain unit test. */
    fun getInstanceOrNull(): ToolSettings? = ApplicationManager.getApplication()?.service()
  }
}
