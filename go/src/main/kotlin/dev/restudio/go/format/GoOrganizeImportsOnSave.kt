package dev.restudio.go.format

import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import dev.restudio.go.GoBundle
import dev.restudio.go.settings.GoSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull

/** Organizes the imports of each saved Go file with gopls when [GoSettings] tells so. */
class GoOrganizeImportsOnSave : ActionsOnSaveFileDocumentManagerListener.DocumentUpdatingActionOnSave() {
  override val presentableName: String
    get() = GoBundle.message("settings.organize.imports.on.save")

  override fun isEnabledForProject(project: Project): Boolean = GoSettings.getInstance(project).organizeImportsOnSave

  override suspend fun updateDocument(project: Project, document: Document) {
    val file = FileDocumentManager.getInstance().getFile(document) ?: return
    if (file.extension != "go") return
    val stamp = document.modificationStamp
    val future = GoplsImports.request(project, file) ?: return
    val edits = try {
      withTimeoutOrNull(3000) { future.await() }
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (_: Exception) {
      // gopls failed. Save the file without a change.
      null
    }
    if (edits == null) future.cancel(true)
    if (edits.isNullOrEmpty()) return
    writeCommandAction(project, GoBundle.message("settings.organize.imports.command")) {
      if (document.modificationStamp == stamp) GoplsImports.apply(document, edits)
    }
  }
}
