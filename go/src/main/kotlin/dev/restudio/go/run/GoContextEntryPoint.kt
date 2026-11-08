package dev.restudio.go.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import dev.restudio.core.entrypoints.EntryPoint
import dev.restudio.core.entrypoints.EntryPointLocation

/**
 * Finds the Go entry point of a run context.
 *
 * The gutter icon passes an [EntryPointLocation] with the exact entry point.
 * The editor context menu passes the whole file, because a TextMate file is one PSI leaf.
 * Then the caret decides: the entry point on the caret line, or else the nearest one above it.
 */
internal object GoContextEntryPoint {
  fun find(context: ConfigurationContext): Pair<VirtualFile, EntryPoint>? {
    val location = context.location ?: return null
    val file = location.psiElement.containingFile ?: return null
    val virtualFile = file.virtualFile ?: return null
    if (virtualFile.extension != "go") return null
    val entryPoint = (location as? EntryPointLocation)?.entryPoint ?: atCaret(context, file) ?: return null
    return virtualFile to entryPoint
  }

  private fun atCaret(context: ConfigurationContext, file: PsiFile): EntryPoint? {
    val document = file.viewProvider.document ?: return null
    val offset = CommonDataKeys.EDITOR.getData(context.dataContext)?.caretModel?.offset
                 ?: context.psiLocation?.textRange?.startOffset
                 ?: return null
    val line = document.getLineNumber(offset)
    val entryPoints = GoEntryPointDetector.detect(file.name, document.charsSequence)
    return entryPoints.firstOrNull { document.getLineNumber(it.offset) == line }
           ?: entryPoints.lastOrNull { document.getLineNumber(it.offset) < line }
  }
}
