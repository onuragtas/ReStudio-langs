package dev.restudio.go.format

import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Asks gopls for the `source.organizeImports` code action of one file. This is the `goimports` behavior.
 *
 * gopls v0.24 sends the edit in the code action: `edit.documentChanges[0].edits` (recorded with a probe client).
 * When an action comes without an edit, this class asks `codeAction/resolve` for it.
 * It uses only a gopls that runs already. It does not start gopls.
 */
object GoplsImports {
  private const val GOPLS_SERVER_ID = "restudio.gopls"
  private const val TIMEOUT_MS = 3000L

  /** Sends the request. The result is `null` when gopls does not run. */
  fun request(project: Project, file: VirtualFile): CompletableFuture<List<TextEdit>>? {
    val wrapper = LanguageServiceAccessor.getInstance(project).startedServers
                    .firstOrNull { it.serverDefinition.id == GOPLS_SERVER_ID } ?: return null
    val uri = LSPIJUtils.toUriAsString(file)
    val params = CodeActionParams(
      TextDocumentIdentifier(uri),
      Range(Position(0, 0), Position(0, 0)),
      CodeActionContext(emptyList(), listOf(CodeActionKind.SourceOrganizeImports)),
    )
    return wrapper.initializedServer.thenCompose { server ->
      server.textDocumentService.codeAction(params).thenCompose { actions ->
        val action = actions.orEmpty().mapNotNull { if (it.isRight) it.right else null }
                       .firstOrNull { it.kind == CodeActionKind.SourceOrganizeImports }
        when {
          action == null -> CompletableFuture.completedFuture(emptyList())
          action.edit != null || action.data == null -> CompletableFuture.completedFuture(editsFor(action, uri))
          else -> server.textDocumentService.resolveCodeAction(action).thenApply { editsFor(it, uri) }
        }
      }
    }
  }

  /**
   * Waits for the edits of [file] and keeps the progress cancelable. Returns an empty list after a timeout or an error.
   * Call it from a background thread.
   */
  fun await(project: Project, file: VirtualFile): List<TextEdit> {
    val future = request(project, file) ?: return emptyList()
    val deadline = System.currentTimeMillis() + TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
      ProgressManager.checkCanceled()
      try {
        return future.get(50, TimeUnit.MILLISECONDS)
      }
      catch (_: TimeoutException) {
        // Wait more.
      }
      catch (_: ExecutionException) {
        return emptyList()
      }
    }
    future.cancel(true)
    return emptyList()
  }

  /** Applies [edits] to [document]. Call it in a write action. */
  fun apply(document: Document, edits: List<TextEdit>) {
    val ranges = edits.map { LSPIJUtils.toOffset(it.range.start, document) to it }
      .sortedByDescending { it.first }
    for ((start, edit) in ranges) {
      val end = LSPIJUtils.toOffset(edit.range.end, document)
      document.replaceString(start, end, edit.newText)
    }
  }

  private fun editsFor(action: CodeAction, uri: String): List<TextEdit> = action.edit?.let { editsFor(it, uri) }.orEmpty()

  private fun editsFor(edit: WorkspaceEdit, uri: String): List<TextEdit> {
    val fromDocumentChanges = edit.documentChanges.orEmpty()
      .mapNotNull { if (it.isLeft) it.left else null }
      .filter { sameUri(it.textDocument.uri, uri) }
      .flatMap { change -> change.edits.mapNotNull { either -> if (either.isLeft) either.left else null } }
    if (fromDocumentChanges.isNotEmpty()) return fromDocumentChanges
    return edit.changes.orEmpty().entries.filter { sameUri(it.key, uri) }.flatMap { it.value }
  }

  private fun sameUri(a: String, b: String): Boolean =
    a == b || runCatching { java.net.URI(a) == java.net.URI(b) }.getOrDefault(false)
}
