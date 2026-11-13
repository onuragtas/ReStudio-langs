package dev.restudio.dotnet.solution

/**
 * Changes the items of a project file as text, so the comments and the format of the file stay.
 *
 * All the paths are relative to the project directory. The functions write them with `\`, as Visual Studio does,
 * and compare them without case and without the type of the slash.
 */
object CsprojEditor {
  private val CODE_EXTENSIONS = setOf("cs", "vb", "fs")

  /**
   * `true` when MSBuild finds the files of the project with globs, so a new file needs no item.
   *
   * That is an SDK-style project, which does not set `EnableDefaultItems` or `EnableDefaultCompileItems` to `false`.
   */
  fun hasDefaultItems(text: String, path: String): Boolean {
    val sdkStyle = Regex("""<Project\s[^>]*\bSdk\s*=""").containsMatchIn(text) || Regex("""<Sdk\s+Name\s*=""").containsMatchIn(text) ||
                   Regex("""<Import\s[^>]*\bSdk\s*=""").containsMatchIn(text)
    if (!sdkStyle) return false
    if (isFalse(text, "EnableDefaultItems")) return false
    return !(isCode(path) && isFalse(text, "EnableDefaultCompileItems")) && !(!isCode(path) && isFalse(text, "EnableDefaultNoneItems"))
  }

  /** The item type for a new file: `Compile` for code, else `None`. */
  fun itemType(path: String): String = if (isCode(path)) "Compile" else "None"

  /** Adds an item for [path]. It returns `null` when the file has an item for [path] already. */
  fun addItem(text: String, type: String, path: String): String? {
    val value = toMsbuild(path)
    if (elements(text).any { it.groups["kind"]!!.value == "Include" && same(it.groups["value"]!!.value, value) }) return null
    val indent = Regex("""\n([ \t]+)<ItemGroup""").find(text)?.groupValues?.get(1) ?: "  "
    val element = "<$type Include=\"$value\" />"
    val group = Regex("""<ItemGroup[^>]*>(?:(?!</ItemGroup>).)*?<$type\s""", RegexOption.DOT_MATCHES_ALL).find(text)
    if (group != null) {
      val insertAt = text.indexOf("</ItemGroup>", group.range.last)
      val lineStart = text.lastIndexOf('\n', insertAt) + 1
      return text.substring(0, lineStart) + "$indent$indent$element\n" + text.substring(lineStart)
    }
    val end = text.lastIndexOf("</Project>")
    if (end < 0) return null
    val lineStart = text.lastIndexOf('\n', end) + 1
    return text.substring(0, lineStart) + "$indent<ItemGroup>\n$indent$indent$element\n$indent</ItemGroup>\n" + text.substring(lineStart)
  }

  /**
   * Removes the items with `Include` or `Update` equal to [path]. With [directory], it also removes the items below [path].
   * It returns `null` when nothing changed.
   */
  fun removeItems(text: String, path: String, directory: Boolean): String? {
    val value = toMsbuild(path)
    var result = text
    for (match in elements(text).toList().asReversed()) {
      val attribute = match.groups["value"]!!.value
      val kind = match.groups["kind"]!!.value
      if (kind == "Remove") continue
      if (!same(attribute, value) && !(directory && below(attribute, value))) continue
      result = cutLine(result, match.range)
    }
    result = result.replace(Regex("""\n[ \t]*<ItemGroup>\s*</ItemGroup>[ \t]*(?=\n)"""), "")
    return result.takeIf { it != text }
  }

  /**
   * Changes the paths of the items from [from] to [to], for a rename or a move in the same project.
   * For a file rename in the same directory, it also changes the `DependentUpon` values. It returns `null` when nothing changed.
   */
  fun renamePath(text: String, from: String, to: String): String? {
    val old = toMsbuild(from)
    val new = toMsbuild(to)
    val builder = StringBuilder()
    var last = 0
    for (match in elements(text)) {
      val group = match.groups["value"]!!
      val attribute = group.value
      val replaced = when {
        same(attribute, old) -> new
        below(attribute, old) -> new + attribute.substring(old.length)
        else -> continue
      }
      builder.append(text, last, group.range.first).append(replaced)
      last = group.range.last + 1
    }
    builder.append(text, last, text.length)
    var result = builder.toString()
    val oldName = old.substringAfterLast('\\')
    val newName = new.substringAfterLast('\\')
    if (old.substringBeforeLast('\\', "") == new.substringBeforeLast('\\', "") && oldName != newName) {
      result = result.replace(Regex("""(<DependentUpon>\s*)${Regex.escape(oldName)}(\s*</DependentUpon>)""", RegexOption.IGNORE_CASE), "$1${newName.replace("$", "\\$")}$2")
    }
    return result.takeIf { it != text }
  }

  private fun elements(text: String): Sequence<MatchResult> =
    Regex("""<(?<type>[A-Za-z_][\w.]*)\s+(?<kind>Include|Update|Remove)\s*=\s*"(?<value>[^"]*)"[^>]*?(?:/>|>(?:(?!<\k<type>[\s>/]).)*?</\k<type>\s*>)""", RegexOption.DOT_MATCHES_ALL)
      .findAll(text)
      .filter { it.groups["type"]!!.value != "ItemGroup" }

  /** Removes [range] and, when the range is alone on its lines, also the indent and the line end. */
  private fun cutLine(text: String, range: IntRange): String {
    val lineStart = text.lastIndexOf('\n', range.first - 1) + 1
    val lineEnd = text.indexOf('\n', range.last + 1).let { if (it < 0) text.length else it + 1 }
    val alone = text.substring(lineStart, range.first).isBlank() && text.substring(range.last + 1, lineEnd).isBlank()
    return if (alone) text.removeRange(lineStart, lineEnd) else text.removeRange(range)
  }

  private fun same(first: String, second: String): Boolean = normalize(first).equals(normalize(second), ignoreCase = true)

  private fun below(value: String, directory: String): Boolean = normalize(value).startsWith(normalize(directory) + "\\", ignoreCase = true)

  private fun normalize(value: String): String = value.trim().replace('/', '\\').removePrefix(".\\").trimEnd('\\')

  private fun toMsbuild(path: String): String = path.replace('/', '\\')

  private fun isCode(path: String): Boolean = path.substringAfterLast('.').lowercase() in CODE_EXTENSIONS

  private fun isFalse(text: String, property: String): Boolean = Regex("""<$property>\s*false\s*</$property>""", RegexOption.IGNORE_CASE).containsMatchIn(text)
}
