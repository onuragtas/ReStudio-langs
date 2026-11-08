package dev.restudio.core.testing

import com.intellij.execution.Location
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import dev.restudio.core.entrypoints.EntryPoint
import dev.restudio.core.entrypoints.EntryPointDetector
import dev.restudio.core.entrypoints.EntryPointLocation

/**
 * Finds a test from a location hint `<protocol>://<directory>#<test name>`.
 *
 * It looks in the files of the directory that [accepts] gives, and runs [detector] on each file.
 * [hint] makes the location hint for a translator.
 */
class DirectoryTestLocator(
  private val protocol: String,
  private val detector: EntryPointDetector,
  private val accepts: (String) -> Boolean,
) : SMTestLocator {
  fun hint(directory: String, testName: String): String = "$protocol://$directory#$testName"

  override fun getLocation(protocol: String, path: String, project: Project, scope: GlobalSearchScope): List<Location<*>> {
    if (protocol != this.protocol) return emptyList()
    val directory = LocalFileSystem.getInstance().findFileByPath(path.substringBefore('#')) ?: return emptyList()
    val name = path.substringAfter('#', "")
    if (name.isEmpty()) return emptyList()
    val psiManager = PsiManager.getInstance(project)
    return directory.children.asSequence()
      .filter { !it.isDirectory && accepts(it.name) && scope.contains(it) }
      .mapNotNull { psiManager.findFile(it) }
      .flatMap { file ->
        detector.detect(file.name, file.viewProvider.contents)
          .filter { it.kind == EntryPoint.Kind.TEST && it.name == name }
          .mapNotNull { entryPoint -> file.findElementAt(entryPoint.offset)?.let { EntryPointLocation(it, entryPoint) } }
      }
      .toList()
  }
}
