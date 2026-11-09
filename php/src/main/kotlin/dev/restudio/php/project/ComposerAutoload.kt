package dev.restudio.php.project

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

/**
 * The autoload directories of a `composer.json` file, relative to its directory.
 *
 * [sources] come from `autoload`, [tests] from `autoload-dev`. Each holds the `psr-4` and `psr-0` directories and the
 * `classmap` entries. A `classmap` entry can be a file; the caller keeps only the directories.
 * The `files` entries are single files, so they do not make a source root.
 * An empty path means the directory of `composer.json`. It is skipped, because the content root is already there.
 */
data class ComposerAutoload(val sources: List<String>, val tests: List<String>) {
  companion object {
    private val EMPTY = ComposerAutoload(emptyList(), emptyList())

    fun parse(text: String): ComposerAutoload {
      val json = try {
        JsonParser.parseString(text)
      }
      catch (_: JsonSyntaxException) {
        return EMPTY
      }
      if (!json.isJsonObject) return EMPTY
      val root = json.asJsonObject
      return ComposerAutoload(paths(root["autoload"]), paths(root["autoload-dev"]))
    }

    private fun paths(section: JsonElement?): List<String> {
      if (section == null || !section.isJsonObject) return emptyList()
      val obj = section.asJsonObject
      val result = linkedSetOf<String>()
      for (key in listOf("psr-4", "psr-0")) {
        val mapping = obj[key]?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
        for ((_, value) in mapping.entrySet()) result += strings(value)
      }
      result += strings(obj["classmap"])
      return result.map(::normalize).filter { it.isNotEmpty() }.distinct()
    }

    private fun strings(element: JsonElement?): List<String> = when {
      element == null -> emptyList()
      element.isJsonArray -> element.asJsonArray.filter { it.isJsonPrimitive }.map { it.asString }
      element.isJsonPrimitive -> listOf(element.asString)
      else -> emptyList()
    }

    private fun normalize(path: String): String = path.trim().removePrefix("./").trimEnd('/').let { if (it == ".") "" else it }
  }
}
