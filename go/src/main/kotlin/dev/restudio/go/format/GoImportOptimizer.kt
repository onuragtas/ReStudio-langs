package dev.restudio.go.format

import com.intellij.lang.ImportOptimizer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * Organizes the imports of a Go file with gopls for Code > Optimize Imports and for the "Optimize imports" option of
 * Reformat Code. A Go file is a TextMate file, thus the optimizer accepts only the `.go` files.
 */
class GoImportOptimizer : ImportOptimizer {
  override fun supports(file: PsiFile): Boolean = file.name.endsWith(".go") && file.virtualFile != null

  override fun processFile(file: PsiFile): Runnable {
    // The platform calls this in a read action. Do not wait for gopls on the EDT.
    if (ApplicationManager.getApplication().isDispatchThread) return Runnable {}
    val virtualFile = file.virtualFile ?: return Runnable {}
    val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return Runnable {}
    val stamp = document.modificationStamp
    val edits = GoplsImports.await(file.project, virtualFile)
    if (edits.isEmpty()) return Runnable {}
    return Runnable {
      if (document.modificationStamp == stamp) {
        GoplsImports.apply(document, edits)
        PsiDocumentManager.getInstance(file.project).commitDocument(document)
      }
    }
  }
}
