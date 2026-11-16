package dev.restudio.php.docker

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * The Docker container that runs Phpactor for this project.
 *
 * The settings go into the workspace file, because the container name often differs from computer to computer.
 * When [container] or [containerPath] is empty, Phpactor runs on this computer.
 */
@Service(Service.Level.PROJECT)
@State(name = "RestudioPhpDocker", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class PhpDockerSettings : SimplePersistentStateComponent<PhpDockerSettings.Options>(Options()) {
  class Options : BaseState() {
    var container: String? by string()
    var containerPath: String? by string()
  }

  /** The name or the ID of the running container. */
  var container: String
    get() = state.container.orEmpty()
    set(value) {
      state.container = value.trim().ifEmpty { null }
    }

  /** The project directory in the container, for example `/var/www/html`. */
  var containerPath: String
    get() = state.containerPath.orEmpty()
    set(value) {
      state.containerPath = value.trim().ifEmpty { null }
    }

  companion object {
    fun getInstance(project: Project): PhpDockerSettings = project.service()
  }
}
