package dev.restudio.go.run

import com.intellij.psi.PsiFile
import dev.restudio.core.entrypoints.EntryPointDetector
import dev.restudio.core.entrypoints.EntryPointLineMarkerProvider
import dev.restudio.go.GoBundle

/** Puts the run and debug gutter icon on `func main()` and on Go test functions. */
class GoLineMarkerProvider : EntryPointLineMarkerProvider() {
  override val detector: EntryPointDetector = GoEntryPointDetector

  override fun accepts(file: PsiFile): Boolean = file.name.endsWith(".go")

  override fun getName(): String = GoBundle.message("gutter.name")
}
