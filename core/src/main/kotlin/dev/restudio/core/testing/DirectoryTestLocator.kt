package dev.restudio.core.testing

import com.intellij.execution.Location
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import dev.restudio.core.entrypoints.EntryPoint
import dev.restudio.core.entrypoints.EntryPointDetector
import dev.restudio.core.entrypoints.EntryPointLocation

/**
 * Finds a test from a location hint `<protocol>://<directory>#<test name>`.
 *
 * It looks in the files of the directory that [accepts] gives, and runs [detector] on each file.
 * When [recursive] is `true`, it also looks in the subdirectories, but not in [SKIPPED_DIRECTORIES].
 * [hint] makes the location hint for a translator.
 */
class DirectoryTestLocator(
  private val protocol: String,
  private val detector: EntryPointDetector,
  private val recursive: Boolean = false,
  private val accepts: (String) -> Boolean,
) : SMTestLocator {
  fun hint(directory: String, testName: String): String = "$protocol://$directory#$testName"

  override fun getLocation(protocol: String, path: String, project: Project, scope: GlobalSearchScope): List<Location<*>> {
    if (protocol != this.protocol) return emptyList()
    val directory = LocalFileSystem.getInstance().findFileByPath(path.substringBefore('#')) ?: return emptyList()
    val name = path.substringAfter('#', "")
    if (name.isEmpty()) return emptyList()
    val psiManager = PsiManager.getInstance(project)
    return files(directory)
      .filter { accepts(it.name) && scope.contains(it) }
      .mapNotNull { psiManager.findFile(it) }
      .flatMap { file ->
        detector.detect(file.name, file.viewProvider.contents)
          .filter { it.kind == EntryPoint.Kind.TEST && it.name == name }
          .mapNotNull { entryPoint -> file.findElementAt(entryPoint.offset)?.let { EntryPointLocation(it, entryPoint) } }
      }
      .toList()
  }

  private fun files(directory: VirtualFile): Sequence<VirtualFile> = directory.children.asSequence().flatMap {
    when {
      !it.isDirectory -> sequenceOf(it)
      recursive && it.name !in SKIPPED_DIRECTORIES -> files(it)
      else -> emptySequence()
    }
  }

  companion object {
    /** Build output and tool directories. They hold no test source. */
    val SKIPPED_DIRECTORIES: Set<String> = setOf("bin", "obj", "node_modules", ".git", ".idea", "build")
  }
}
