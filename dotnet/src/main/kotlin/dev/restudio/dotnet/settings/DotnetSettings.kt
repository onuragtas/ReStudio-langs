package dev.restudio.dotnet.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.panel
import dev.restudio.dotnet.DotnetBundle

/** The C# language server that the plugin starts. */
enum class CSharpServer {
  /** Roslyn LS when it is installed, else csharp-ls. */
  AUTO,
  ROSLYN,
  CSHARP_LS,
}

/** Keeps the .NET settings of the user. */
@Service(Service.Level.APP)
@State(name = "ReStudioDotnet", storages = [Storage("restudio-dotnet.xml")])
class DotnetSettings : SimplePersistentStateComponent<DotnetSettings.Options>(Options()) {
  class Options : BaseState() {
    var server by enum(CSharpServer.AUTO)
  }

  var server: CSharpServer
    get() = state.server
    set(value) {
      state.server = value
    }

  companion object {
    /** Returns `null` when no application runs, for example in a plain unit test. */
    fun getInstanceOrNull(): DotnetSettings? = ApplicationManager.getApplication()?.service()
  }
}

/** The settings page Tools > ReStudio .NET. */
class DotnetSettingsConfigurable : BoundConfigurable(DotnetBundle.message("settings.display.name")) {
  override fun createPanel(): DialogPanel {
    val settings = service<DotnetSettings>()
    return panel {
      row(DotnetBundle.message("settings.server.label")) {
        comboBox(CSharpServer.entries, textListCellRenderer { it?.let(::serverName) })
          .bindItem({ settings.server }, { settings.server = it ?: CSharpServer.AUTO })
          .comment(DotnetBundle.message("settings.server.comment"))
      }
    }
  }

  private companion object {
    fun serverName(server: CSharpServer): String = when (server) {
      CSharpServer.AUTO -> DotnetBundle.message("settings.server.auto")
      CSharpServer.ROSLYN -> DotnetBundle.message("settings.server.roslyn")
      CSharpServer.CSHARP_LS -> DotnetBundle.message("settings.server.csharp.ls")
    }
  }
}
