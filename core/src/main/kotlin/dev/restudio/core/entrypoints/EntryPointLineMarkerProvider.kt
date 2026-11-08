package dev.restudio.core.entrypoints

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.execution.Location
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.LineMarkerActionWrapper
import com.intellij.icons.AllIcons
import com.intellij.ide.TrustedFiles
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import javax.swing.Icon

/**
 * Puts the run and debug gutter icon on each entry point that [detector] finds.
 *
 * A TextMate file is one PSI leaf, so a `RunLineMarkerContributor` can mark only the first line.
 * This provider adds one marker for each entry point inside the leaf. The marker range starts at [EntryPoint.offset].
 * The run actions get an [EntryPointLocation], so the run configuration producer knows the entry point.
 */
abstract class EntryPointLineMarkerProvider : LineMarkerProviderDescriptor() {
  protected abstract val detector: EntryPointDetector

  protected abstract fun accepts(file: PsiFile): Boolean

  override fun getIcon(): Icon = AllIcons.RunConfigurations.TestState.Run

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

  override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
    for (element in elements) {
      if (element.firstChild != null) continue
      val file = element.containingFile ?: continue
      if (!accepts(file)) continue
      // A run action executes code from the file. It must not show in the safe mode.
      val virtualFile = file.virtualFile
      if (virtualFile != null && !TrustedFiles.isTrusted(virtualFile, file.project)) continue
      val range = element.textRange
      val text = file.viewProvider.contents
      for (entryPoint in entryPoints(file)) {
        if (entryPoint.offset < range.startOffset || entryPoint.offset >= range.endOffset) continue
        val lineEnd = text.indexOf('\n', entryPoint.offset).let { if (it < 0) text.length else it }
        result.add(EntryPointLineMarkerInfo(element, TextRange(entryPoint.offset, minOf(lineEnd, range.endOffset)), entryPoint))
      }
    }
  }

  fun entryPoints(file: PsiFile): List<EntryPoint> =
    CachedValuesManager.getCachedValue(file) {
      CachedValueProvider.Result.create(detector.detect(file.name, file.viewProvider.contents), PsiModificationTracker.MODIFICATION_COUNT, file)
    }
}

/** One run and debug gutter icon. [actions] are the executor actions for [entryPoint]. */
class EntryPointLineMarkerInfo(element: PsiElement, range: TextRange, val entryPoint: EntryPoint) :
  LineMarkerInfo<PsiElement>(element, range, AllIcons.RunConfigurations.TestState.Run, { entryPoint.name }, null,
                             GutterIconRenderer.Alignment.CENTER, { entryPoint.name }) {
  val actions: ActionGroup = DefaultActionGroup(
    ExecutorAction.getActions(0).map { EntryPointActionWrapper(element, it, entryPoint) }
  )

  override fun createGutterRenderer(): GutterIconRenderer = object : LineMarkerGutterIconRenderer<PsiElement>(this) {
    override fun getClickAction(): AnAction? = null

    override fun isNavigateAction(): Boolean = true

    override fun getPopupMenuActions(): ActionGroup = actions
  }
}

private class EntryPointActionWrapper(element: PsiElement, origin: AnAction, private val entryPoint: EntryPoint) :
  LineMarkerActionWrapper(element, origin) {
  override fun dataSnapshot(sink: DataSink) {
    sink.lazy(Location.DATA_KEY) {
      val element = myElement.element
      if (element != null && element.isValid) EntryPointLocation(element, entryPoint) else null
    }
  }
}
