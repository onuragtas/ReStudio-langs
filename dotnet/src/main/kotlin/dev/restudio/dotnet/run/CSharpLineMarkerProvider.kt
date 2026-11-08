package dev.restudio.dotnet.run

import com.intellij.psi.PsiFile
import dev.restudio.core.entrypoints.EntryPointDetector
import dev.restudio.core.entrypoints.EntryPointLineMarkerProvider
import dev.restudio.dotnet.DotnetBundle

/** Puts the run and debug gutter icon on the C# entry points and tests. */
class CSharpLineMarkerProvider : EntryPointLineMarkerProvider() {
  override val detector: EntryPointDetector = CSharpEntryPointDetector

  override fun accepts(file: PsiFile): Boolean = file.name.endsWith(".cs")

  override fun getName(): String = DotnetBundle.message("gutter.name")
}
