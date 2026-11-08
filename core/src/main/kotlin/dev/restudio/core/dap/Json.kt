package dev.restudio.core.dap

/**
 * Writes a small JSON object for a DAP `launch` request.
 *
 * A value is a string, a boolean, a number, a list of these, or a map with string keys.
 */
fun jsonObject(vararg entries: Pair<String, Any>): String =
  entries.joinToString(",\n", "{\n", "\n}") { (key, value) -> "  ${quote(key)}: ${jsonValue(value)}" }

private fun jsonValue(value: Any?): String = when (value) {
  is String -> quote(value)
  is Boolean, is Int, is Long -> value.toString()
  is List<*> -> value.joinToString(", ", "[", "]") { jsonValue(it) }
  is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { (key, item) -> "${quote(key.toString())}: ${jsonValue(item)}" }
  else -> error("Unsupported JSON value: $value")
}

private fun quote(text: String): String = buildString {
  append('"')
  for (c in text) {
    when (c) {
      '"' -> append("\\\"")
      '\\' -> append("\\\\")
      '\n' -> append("\\n")
      '\t' -> append("\\t")
      else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
    }
  }
  append('"')
}
