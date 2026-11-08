package dev.restudio.web.run

import com.intellij.psi.PsiFile
import dev.restudio.core.entrypoints.EntryPointDetector
import dev.restudio.core.entrypoints.EntryPointLineMarkerProvider
import dev.restudio.web.WebBundle
import dev.restudio.web.test.VitestEntryPointDetector

/** Puts the run and debug gutter icon on each script of a `package.json` file outside `node_modules`. */
class NpmScriptLineMarkerProvider : EntryPointLineMarkerProvider() {
  override val detector: EntryPointDetector = NpmScriptDetector

  override fun accepts(file: PsiFile): Boolean = file.name == "package.json" && file.virtualFile?.path?.contains("/node_modules/") != true

  override fun getName(): String = WebBundle.message("gutter.npm.name")
}

/** Puts the run and debug gutter icon on the `describe`, `it` and `test` calls of a Vitest file. */
class VitestLineMarkerProvider : EntryPointLineMarkerProvider() {
  override val detector: EntryPointDetector = VitestEntryPointDetector

  override fun accepts(file: PsiFile): Boolean = VitestEntryPointDetector.isTestFile(file.name) && file.virtualFile?.path?.contains("/node_modules/") != true

  override fun getName(): String = WebBundle.message("gutter.vitest.name")
}
