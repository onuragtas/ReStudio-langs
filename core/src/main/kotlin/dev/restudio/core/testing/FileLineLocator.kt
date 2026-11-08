package dev.restudio.core.testing

import com.intellij.execution.Location
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import dev.restudio.core.entrypoints.EntryPoint
import dev.restudio.core.entrypoints.EntryPointLocation

/**
 * Finds a test from the hint `file://<absolute path>:<line>:<column>`. The line and the column start at 1 and are
 * optional. A runner that knows the source position of a test, such as Vitest with `includeTaskLocation`, uses it.
 */
object FileLineLocator : SMTestLocator {
  const val PROTOCOL: String = "file"

  fun hint(path: String, line: Int? = null, column: Int? = null): String =
    "$PROTOCOL://" + listOfNotNull(path, line, column?.takeIf { line != null }).joinToString(":")

  override fun getLocation(protocol: String, path: String, project: Project, scope: GlobalSearchScope): List<Location<*>> {
    if (protocol != PROTOCOL) return emptyList()
    val match = Regex("""^(.*?)(?::(\d+))?(?::(\d+))?$""").matchEntire(path) ?: return emptyList()
    val (filePath, line, column) = match.destructured
    val file = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return emptyList()
    if (!scope.contains(file)) return emptyList()
    val psiFile = PsiManager.getInstance(project).findFile(file) ?: return emptyList()
    val document: Document = FileDocumentManager.getInstance().getDocument(file) ?: return emptyList()
    val offset = line.toIntOrNull()?.let { (it - 1).coerceIn(0, document.lineCount - 1) }?.let { lineIndex ->
      (document.getLineStartOffset(lineIndex) + ((column.toIntOrNull() ?: 1) - 1)).coerceAtMost(document.getLineEndOffset(lineIndex))
    } ?: 0
    val element = psiFile.findElementAt(offset) ?: psiFile
    return listOf(EntryPointLocation(element, EntryPoint(EntryPoint.Kind.TEST, path, offset)))
  }
}
