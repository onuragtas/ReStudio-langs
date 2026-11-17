package dev.restudio.php.ide

import com.intellij.codeInsight.breadcrumbs.FileBreadcrumbsCollector
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.breadcrumbs.Crumb
import com.intellij.ui.components.breadcrumbs.StickyLineInfo
import com.intellij.xml.breadcrumbs.NavigatableCrumb

/**
 * Gives the breadcrumbs and the sticky lines of a PHP file from its blocks ([PhpScopes]).
 *
 * The language server reports only the classes, the methods and the functions. A sticky line also shows the
 * closures and the statements around the caret. For example, a Slim route shows `$app->group(...)` and
 * `$app->post(...)` above the code.
 */
class PhpBreadcrumbsCollector : FileBreadcrumbsCollector() {
  override fun handlesFile(virtualFile: VirtualFile): Boolean = virtualFile.extension.equals("php", ignoreCase = true)

  override fun requiresProvider(): Boolean = false

  override fun watchForChanges(file: VirtualFile, editor: Editor, disposable: Disposable, changesHandler: Runnable) {
    editor.document.addDocumentListener(object : DocumentListener {
      override fun documentChanged(event: DocumentEvent) = changesHandler.run()
    }, disposable)
  }

  override fun computeCrumbs(virtualFile: VirtualFile, document: Document, offset: Int, forcedShown: Boolean?): Iterable<Crumb> =
    PhpScopes.around(scopes(document), offset).map { PhpCrumb(document, it) }

  override fun computeStickyLineInfos(file: VirtualFile, document: Document, offset: Int): List<StickyLineInfo> =
    PhpScopes.around(scopes(document), offset).map { StickyLineInfo(it.headerOffset, it.endOffset, null) }

  private class Cache(val stamp: Long, val scopes: List<PhpScope>)

  private fun scopes(document: Document): List<PhpScope> {
    val stamp = document.modificationStamp
    document.getUserData(CACHE)?.takeIf { it.stamp == stamp }?.let { return it.scopes }
    val scopes = PhpScopes.find(document.immutableCharSequence)
    document.putUserData(CACHE, Cache(stamp, scopes))
    return scopes
  }

  private class PhpCrumb(private val document: Document, private val scope: PhpScope) : NavigatableCrumb {
    override fun getText(): String {
      val line = document.getLineNumber(scope.headerOffset)
      val text = document.getText(TextRange(scope.headerOffset, document.getLineEndOffset(line))).trim().removeSuffix("{").trim()
      return if (text.length > MAX_TEXT) text.take(MAX_TEXT - 1) + "…" else text
    }

    override fun getAnchorOffset(): Int = scope.headerOffset

    override fun getHighlightRange(): TextRange = TextRange(scope.headerOffset, scope.endOffset)

    override fun navigate(editor: Editor, withSelection: Boolean) {
      editor.caretModel.moveToOffset(scope.headerOffset)
      editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
    }
  }

  private companion object {
    val CACHE: Key<Cache> = Key.create("restudio.php.scopes")
    const val MAX_TEXT = 60
  }
}
