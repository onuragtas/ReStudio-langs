package dev.restudio.web.test

import dev.restudio.core.entrypoints.EntryPoint
import dev.restudio.core.entrypoints.EntryPointDetector

/**
 * Finds the `describe`, `it` and `test` calls of a test file (`*.test.*` or `*.spec.*`). Vitest and Jest use it.
 *
 * Each call with a string name is a [EntryPoint.Kind.TEST]. Its name is the full name that the runner matches with
 * `-t`: the names of the enclosing `describe` calls and its own name, joined with [separator].
 * A modifier such as `.only` or `.each(table)` is allowed. A name that is not a string literal is skipped.
 */
open class TestCallDetector(val separator: String) : EntryPointDetector {
  private val TEST_FILE = Regex("""\.(test|spec)\.[cm]?[jt]sx?$""")
  // `.each(table)` puts one more argument list before the name. The table must have no parentheses.
  private val CALL = Regex("""\b(describe|suite|it|test)(?:\.\w+)*(?:\s*\([^()]*\))?\s*\(\s*(['"`])((?:(?!\2)[^\\]|\\.)*)\2""")

  fun isTestFile(fileName: String): Boolean = TEST_FILE.containsMatchIn(fileName)

  override fun detect(fileName: String, text: CharSequence): List<EntryPoint> {
    if (!isTestFile(fileName)) return emptyList()
    data class Call(val name: String, val start: Int, val end: Int)
    val calls = CALL.findAll(text).map { match ->
      // The call parenthesis is the last one before the name.
      val open = text.lastIndexOf('(', match.groups[3]!!.range.first)
      Call(match.groupValues[3], match.range.first, closingParenthesis(text, open))
    }.toList()
    return calls.map { call ->
      val parents = calls.filter { it.start < call.start && it.end > call.end }.map { it.name }
      EntryPoint(EntryPoint.Kind.TEST, (parents + call.name).joinToString(separator), call.start)
    }
  }

  /** Returns the index of the parenthesis that closes the one at [open]. Strings and comments are skipped. */
  private fun closingParenthesis(text: CharSequence, open: Int): Int {
    var depth = 0
    var index = open
    while (index < text.length) {
      when (val c = text[index]) {
        '(' -> depth++
        ')' -> if (--depth == 0) return index
        '\'', '"', '`' -> {
          index++
          while (index < text.length && text[index] != c) index += if (text[index] == '\\') 2 else 1
        }
        '/' -> when (text.getOrNull(index + 1)) {
          '/' -> index = text.indexOf('\n', index).let { if (it < 0) text.length else it }
          '*' -> index = text.indexOf("*/", index + 2).let { if (it < 0) text.length else it + 1 }
        }
      }
      index++
    }
    return text.length
  }
}

/**
 * The Vitest form of [TestCallDetector]. Vitest 5 matches `-t` with the names joined with [SEPARATOR]. The JSON
 * reporter writes the names with a space instead, but `-t` does not match that form (recorded on the Vue sample).
 */
object VitestEntryPointDetector : TestCallDetector(" > ") {
  const val SEPARATOR: String = " > "
}

/** The Jest form of [TestCallDetector]. Jest matches `-t` with the full name: the names joined with a space. */
object JestEntryPointDetector : TestCallDetector(" ") {
  const val SEPARATOR: String = " "
}
