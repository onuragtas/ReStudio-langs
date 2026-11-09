package dev.restudio.php.test

import dev.restudio.core.entrypoints.EntryPoint

/**
 * Finds the `test`, `it` and `describe` calls of a Pest test file.
 *
 * Each call with a string name is a [EntryPoint.Kind.TEST]. Its name is the name that `pest --teamcity` prints
 * (recorded with Pest 4 on a sample file): `test('x')` is `x`, `it('x')` is `it x`, and a `describe('g')` block puts
 * `` `g` → `` before the names inside it. The name has no `::`, so it is not a PHPUnit method name.
 */
object PestEntryPointDetector {
  // A call on an object or a class, such as `$this->test(` or `Foo::it(`, is not a Pest call.
  private val CALL = Regex("""(?<![\w>:$\\])(describe|it|test)\s*\(\s*(['"])((?:(?!\2)[^\\]|\\.)*)\2""")

  private const val SEPARATOR = " → "

  fun detect(text: CharSequence): List<EntryPoint> {
    data class Call(val kind: String, val name: String, val start: Int, val end: Int)
    val calls = CALL.findAll(text).map { match ->
      val open = text.indexOf('(', match.groups[1]!!.range.last)
      Call(match.groupValues[1], unescape(match.groupValues[3]), match.range.first, closingParenthesis(text, open))
    }.toList()
    return calls.map { call ->
      val parents = calls.filter { it.kind == "describe" && it.start < call.start && it.end > call.end }.map { "`${it.name}`" }
      val own = when (call.kind) {
        "it" -> "it ${call.name}"
        "describe" -> "`${call.name}`"
        else -> call.name
      }
      EntryPoint(EntryPoint.Kind.TEST, (parents + own).joinToString(SEPARATOR), call.start)
    }
  }

  /**
   * The `--filter` value that selects the test [name], or all the tests of a `describe` block.
   * PHPUnit takes a value with delimiters as a regular expression. The name must end the test name.
   */
  fun filter(name: String, describe: Boolean): String {
    val escaped = buildString {
      for (c in name) {
        if (c in REGEX_SPECIAL) append('\\')
        append(c)
      }
    }
    return if (describe) "/$escaped/" else "/$escaped$/"
  }

  private const val REGEX_SPECIAL = "\\^$.|?*+()[]{}/"

  private fun unescape(text: String): String = text.replace("\\'", "'").replace("\\\"", "\"").replace("\\\\", "\\")

  /** Returns the index of the parenthesis that closes the one at [open]. Strings and comments are skipped. */
  private fun closingParenthesis(text: CharSequence, open: Int): Int {
    var depth = 0
    var index = open
    while (index in 0 until text.length) {
      when (val c = text[index]) {
        '(' -> depth++
        ')' -> if (--depth == 0) return index
        '\'', '"' -> {
          index++
          while (index < text.length && text[index] != c) index += if (text[index] == '\\') 2 else 1
        }
        '#' -> index = lineEnd(text, index)
        '/' -> when (text.getOrNull(index + 1)) {
          '/' -> index = lineEnd(text, index)
          '*' -> index = text.indexOf("*/", index + 2).let { if (it < 0) text.length else it + 1 }
        }
      }
      index++
    }
    return text.length
  }

  private fun lineEnd(text: CharSequence, from: Int): Int = text.indexOf('\n', from).let { if (it < 0) text.length else it }
}
