package dev.restudio.web.ide

import com.intellij.openapi.application.PathManager
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider.PluginBundle
import java.nio.file.Files
import java.nio.file.Path

/**
 * Gives TextMate the Vue grammar, so that a `.vue` file has syntax colors. The bundled TextMate bundles have none.
 *
 * The grammar comes from vuejs/language-tools v3.3.14 under the MIT license (`textmate/vue/LICENSE`). It embeds
 * `source.ts`, `source.css` and `text.html.basic`, which the bundled TypeScript, CSS and HTML bundles give.
 * TextMate reads a bundle from a directory, so the provider copies it from the plugin to the IDE system directory.
 */
class VueBundleProvider : TextMateBundleProvider {
  override fun getBundles(): List<PluginBundle> = listOf(PluginBundle("vue", extract()))

  private fun extract(): Path {
    val target = Path.of(PathManager.getSystemPath(), "restudio", "textmate", "vue")
    for (file in FILES) {
      val content = javaClass.getResourceAsStream("/textmate/vue/$file")!!.use { it.readBytes() }
      val path = target.resolve(file)
      if (Files.isRegularFile(path) && Files.readAllBytes(path).contentEquals(content)) continue
      Files.createDirectories(path.parent)
      Files.write(path, content)
    }
    return target
  }

  private companion object {
    val FILES = listOf(
      "package.json",
      "LICENSE",
      "language-configuration.json",
      "syntaxes/vue.tmLanguage.json",
      "syntaxes/vue-directives.json",
      "syntaxes/vue-interpolations.json",
      "syntaxes/vue-sfc-style-variable-injection.json",
    )
  }
}
