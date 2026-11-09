package dev.restudio.dotnet.run

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.LazyFileHyperlinkInfo
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * Makes the MSBuild and compiler messages in a console clickable.
 *
 * The format is `path(line,column): error CS1234: text [project]`, and `path(line,column,endLine,endColumn)` also occurs.
 * A relative path is relative to the base directory of the IDE project.
 */
class DotnetBuildErrorFilter(private val project: Project) : Filter {
  override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
    val match = PATTERN.find(line) ?: return null
    val path = match.groups["path"]!!
    val file = path.value.trim().replace('\\', '/')
    val absolute = if (Path.of(file).isAbsolute) file else project.basePath?.let { Path.of(it).resolve(file).normalize().toString() } ?: return null
    val lineNumber = match.groups["line"]!!.value.toInt() - 1
    val column = (match.groups["column"]?.value?.toInt() ?: 1) - 1
    val start = entireLength - line.length
    val linkStart = start + path.range.first + (path.value.length - path.value.trimStart().length)
    val linkEnd = start + (match.groups["position"]?.range?.last ?: path.range.last) + 1
    return Filter.Result(linkStart, linkEnd, LazyFileHyperlinkInfo(project, absolute, lineNumber.coerceAtLeast(0), column.coerceAtLeast(0)))
  }

  companion object {
    val PATTERN: Regex = Regex(
      """(?<path>(?:[A-Za-z]:)?[^\s:(][^:(]*?\.(?:cs|csx|fs|vb|razor|cshtml|csproj|fsproj|vbproj|props|targets|sln|slnx|json))(?<position>\((?<line>\d+)(?:,(?<column>\d+))?(?:,\d+,\d+)?\)): (?:error|warning) [A-Z]+\d+:""",
      RegexOption.IGNORE_CASE,
    )
  }
}

/** Adds [DotnetBuildErrorFilter] to each console, so that the output of `dotnet build` in a terminal-less console also gets links. */
class DotnetBuildErrorFilterProvider : ConsoleFilterProvider {
  override fun getDefaultFilters(project: Project): Array<Filter> = arrayOf(DotnetBuildErrorFilter(project))
}
