package dev.restudio.web.run

import dev.restudio.core.entrypoints.EntryPoint
import dev.restudio.core.entrypoints.EntryPointDetector

/** Finds the scripts of a `package.json` file. Each key of the `scripts` object is a [EntryPoint.Kind.MAIN]. */
object NpmScriptDetector : EntryPointDetector {
  private val SCRIPTS = Regex("\"scripts\"\\s*:\\s*\\{")
  private val KEY = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:")

  override fun detect(fileName: String, text: CharSequence): List<EntryPoint> {
    if (fileName != "package.json") return emptyList()
    val start = SCRIPTS.find(text)?.range?.last?.plus(1) ?: return emptyList()
    val result = mutableListOf<EntryPoint>()
    var index = start
    var depth = 0
    while (index < text.length) {
      when (text[index]) {
        '"' -> {
          val key = if (depth == 0) KEY.matchAt(text, index) else null
          if (key != null) {
            result += EntryPoint(EntryPoint.Kind.MAIN, key.groupValues[1], index)
            index = key.range.last + 1
            index = skipString(text, skipSpaces(text, index))
            continue
          }
          index = skipString(text, index)
          continue
        }
        '{', '[' -> depth++
        '}', ']' -> if (depth-- == 0) return result
      }
      index++
    }
    return result
  }

  private fun skipSpaces(text: CharSequence, from: Int): Int {
    var index = from
    while (index < text.length && text[index].isWhitespace()) index++
    return index
  }

  /** Returns the index after the string that starts at [from], or [from] when no string starts there. */
  private fun skipString(text: CharSequence, from: Int): Int {
    if (from >= text.length || text[from] != '"') return from
    var index = from + 1
    while (index < text.length && text[index] != '"') index += if (text[index] == '\\') 2 else 1
    return index + 1
  }
}
