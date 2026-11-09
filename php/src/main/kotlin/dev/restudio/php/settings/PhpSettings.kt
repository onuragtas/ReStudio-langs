package dev.restudio.php.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** The PHP language servers that the plugin can start. */
enum class PhpLanguageServer { PHPACTOR, INTELEPHENSE }

/** The application settings of the PHP plugin. */
@Service(Service.Level.APP)
@State(name = "RestudioPhpSettings", storages = [Storage("restudio-php.xml")])
class PhpSettings : SimplePersistentStateComponent<PhpSettings.Options>(Options()) {
  class Options : BaseState() {
    var languageServer: PhpLanguageServer by enum(PhpLanguageServer.PHPACTOR)
  }

  var languageServer: PhpLanguageServer
    get() = state.languageServer
    set(value) {
      state.languageServer = value
    }

  companion object {
    fun getInstance(): PhpSettings = service()
  }
}
