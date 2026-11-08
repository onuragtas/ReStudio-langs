package dev.restudio.core.dap

/** Writes a small JSON object for a DAP `launch` request. Values are strings or string lists. */
fun jsonObject(vararg entries: Pair<String, Any>): String =
  entries.joinToString(",\n", "{\n", "\n}") { (key, value) -> "  ${quote(key)}: ${jsonValue(value)}" }

private fun jsonValue(value: Any): String = when (value) {
  is String -> quote(value)
  is List<*> -> value.joinToString(", ", "[", "]") { quote(it.toString()) }
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
