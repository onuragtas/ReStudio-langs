package dev.restudio.php.run

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import dev.restudio.core.entrypoints.EntryPointDetector
import dev.restudio.core.entrypoints.EntryPointLineMarkerProvider
import dev.restudio.php.PhpBundle

/** Puts the run and debug gutter icon on PHP scripts and PHPUnit tests. */
class PhpLineMarkerProvider : EntryPointLineMarkerProvider() {
  override val detector: EntryPointDetector = PhpEntryPointDetector

  override fun accepts(file: PsiFile): Boolean = file.virtualFile?.let(::isRunnable) ?: file.name.endsWith(".php")

  override fun getName(): String = PhpBundle.message("gutter.name")

  companion object {
    /** Laravel keeps files without a class in these directories, but they are not scripts to run. */
    private val NOT_SCRIPTS = setOf("routes", "config", "database", "bootstrap", "resources", "storage", "lang", "public")

    /**
     * `true` for a PHP file outside `vendor` and outside the Laravel directories of its Composer project.
     * A package in `vendor` has its own `composer.json`, so the `vendor` check looks at the whole path.
     */
    fun isRunnable(file: VirtualFile): Boolean {
      if (file.extension != "php" || "/vendor/" in file.path) return false
      for (directory in generateSequence(file.parent) { it.parent }) {
        if (directory.name in NOT_SCRIPTS) return false
        if (directory.findChild("composer.json") != null) return true
      }
      return true
    }
  }
}
