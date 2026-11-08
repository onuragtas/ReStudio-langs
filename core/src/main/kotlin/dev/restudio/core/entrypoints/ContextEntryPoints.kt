package dev.restudio.core.entrypoints

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/**
 * Finds the entry point of a run context.
 *
 * The gutter icon passes an [EntryPointLocation] with the exact entry point.
 * The editor context menu passes the whole file, because a TextMate file is one PSI leaf.
 * Then the caret decides: the entry point on the caret line, or else the nearest one above it.
 * The project view has no caret. When the caret gives nothing and the file has one main entry point, that one wins:
 * a program file runs from any line.
 */
object ContextEntryPoints {
  fun find(context: ConfigurationContext, detector: EntryPointDetector, accepts: (VirtualFile) -> Boolean): Pair<VirtualFile, EntryPoint>? {
    val location = context.location ?: return null
    val file = location.psiElement.containingFile ?: return null
    val virtualFile = file.virtualFile ?: return null
    if (!accepts(virtualFile)) return null
    val entryPoint = (location as? EntryPointLocation)?.entryPoint ?: atCaret(context, file, detector) ?: return null
    return virtualFile to entryPoint
  }

  private fun atCaret(context: ConfigurationContext, file: PsiFile, detector: EntryPointDetector): EntryPoint? {
    val document = file.viewProvider.document ?: return null
    val offset = CommonDataKeys.EDITOR.getData(context.dataContext)?.caretModel?.offset
                 ?: context.psiLocation?.textRange?.startOffset
                 ?: 0
    val line = document.getLineNumber(offset)
    val entryPoints = detector.detect(file.name, document.charsSequence)
    return entryPoints.firstOrNull { document.getLineNumber(it.offset) == line }
           ?: entryPoints.lastOrNull { document.getLineNumber(it.offset) < line }
           ?: entryPoints.singleOrNull { it.kind == EntryPoint.Kind.MAIN }
  }
}
