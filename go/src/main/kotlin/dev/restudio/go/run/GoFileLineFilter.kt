package dev.restudio.go.run

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Makes each `file.go:line` and `file.go:line:col` in the console a link to the source.
 *
 * `go build` writes a path that is relative to its working directory. Thus the filter resolves a relative path
 * against [baseDirectory]. A panic stack trace has absolute paths.
 */
class GoFileLineFilter(private val project: Project, private val baseDirectory: String) : Filter, DumbAware {
  override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
    val lineStart = entireLength - line.length
    val items = PATTERN.findAll(line).mapNotNull { match ->
      val path = resolve(match.groupValues[1]) ?: return@mapNotNull null
      val file = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return@mapNotNull null
      val lineNumber = match.groupValues[2].toInt() - 1
      val column = match.groupValues[3].toIntOrNull()?.minus(1) ?: 0
      Filter.ResultItem(lineStart + match.range.first, lineStart + match.range.last + 1,
                        OpenFileHyperlinkInfo(project, file, lineNumber, column))
    }.toList()
    return if (items.isEmpty()) null else Filter.Result(items)
  }

  private fun resolve(text: String): Path? =
    try {
      val path = Path.of(text)
      if (path.isAbsolute) path else Path.of(baseDirectory).resolve(path).normalize()
    }
    catch (_: InvalidPathException) {
      null
    }

  companion object {
    /** A Go source path, a line and an optional column, for example `./main.go:5:2`. */
    val PATTERN: Regex = Regex("""((?:[A-Za-z]:)?[^\s:"'()]+\.go):(\d+)(?::(\d+))?""")
  }
}
