package dev.restudio.core.sdk

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.ExtensionPointName
import dev.restudio.core.sdk.install.InstallRecipe
import org.jetbrains.annotations.Nls
import java.nio.file.Path

/**
 * A command-line tool that a ReStudio plugin starts, for example `gopls` or `flutter`.
 *
 * [id] is the key of the user override in [ToolSettings]. It is also the directory name under [ToolLocator.TOOLS_DIRECTORY].
 * [executable] is the file name that [ToolLocator] looks for in the PATH.
 * [preferred] gives paths that win over the PATH, for example `$FLUTTER_ROOT/bin/flutter`.
 * [fallbacks] gives paths that [ToolLocator] tries after the PATH.
 * [recipe] tells [dev.restudio.core.sdk.install.ToolInstaller] how to install the pinned version.
 */
class Tool(
  val id: String,
  val displayName: @Nls String,
  val executable: String = id,
  val preferred: () -> List<Path> = { emptyList() },
  val fallbacks: () -> List<Path> = { emptyList() },
  val recipe: InstallRecipe? = null,
)

/**
 * Gives the tools of a plugin to the ReStudio Tools settings page and to [ToolLocator].
 *
 * Register an implementation as `<toolProvider implementation="..."/>` in the `dev.restudio.core` namespace.
 * Return the same [Tool] objects on each call.
 */
interface ToolProvider {
  fun tools(): List<Tool>

  companion object {
    val EP_NAME: ExtensionPointName<ToolProvider> = ExtensionPointName("dev.restudio.core.toolProvider")
  }
}

/** All the tools that the installed plugins register through [ToolProvider]. */
object ToolRegistry {
  fun all(): List<Tool> {
    if (ApplicationManager.getApplication() == null || !ToolProvider.EP_NAME.hasAnyExtensions()) return emptyList()
    return ToolProvider.EP_NAME.extensionList.flatMap { it.tools() }.distinctBy { it.id }
  }

  /** Returns the tool with [id] or with the executable [id]. */
  fun find(id: String): Tool? = all().let { tools -> tools.firstOrNull { it.id == id } ?: tools.firstOrNull { it.executable == id } }
}
