package dev.restudio.php.test

import com.intellij.execution.Location
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import dev.restudio.core.entrypoints.EntryPoint
import dev.restudio.core.entrypoints.EntryPointLocation

/**
 * Finds a PHPUnit test from its `php_qn` hint: `/path/File.php::\Namespace\Class` for a class, and
 * `/path/File.php::\Namespace\Class::method` for a method.
 */
object PhpQnLocator : SMTestLocator {
  const val PROTOCOL: String = "php_qn"

  override fun getLocation(protocol: String, path: String, project: Project, scope: GlobalSearchScope): List<Location<*>> {
    if (protocol != PROTOCOL) return emptyList()
    val parts = path.split("::")
    val file = LocalFileSystem.getInstance().findFileByPath(parts[0]) ?: return emptyList()
    if (!scope.contains(file)) return emptyList()
    val psiFile = PsiManager.getInstance(project).findFile(file) ?: return emptyList()
    val text = psiFile.viewProvider.contents
    val className = parts.getOrNull(1)?.substringAfterLast('\\')
    val method = parts.getOrNull(2)
    val pattern = when {
      method != null -> Regex("""\bfunction\s+${Regex.escape(method)}\s*\(""")
      className != null -> Regex("""\b(?:class|trait)\s+${Regex.escape(className)}\b""")
      else -> null
    }
    val offset = pattern?.find(text)?.range?.first ?: 0
    val element = psiFile.findElementAt(offset) ?: return emptyList()
    return listOf(EntryPointLocation(element, EntryPoint(EntryPoint.Kind.TEST, path.substringAfter("::"), offset)))
  }
}
