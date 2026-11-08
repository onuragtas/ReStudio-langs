package dev.restudio.core.ide

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import com.redhat.devtools.lsp4ij.ui.LanguageServerSuggestionEditorNotificationProvider
import java.util.function.Function
import javax.swing.JComponent

/**
 * Shows the LSP4IJ banner "*.ts files are supported by language servers" only for the files that no ReStudio plugin
 * handles.
 *
 * LSP4IJ offers a server template for a file when no user-defined server of that template exists. It does not look at
 * the servers of the plugins, so it offered TypeScript servers next to the vtsls of the web plugin.
 * [LanguageServerSuggestionFilterActivity] removes the LSP4IJ provider; this provider shows its banner for other files.
 */
class FilteredLanguageServerSuggestionProvider : EditorNotificationProvider, DumbAware {
  private val delegate = LanguageServerSuggestionEditorNotificationProvider()

  override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? =
    if (FileExtensionAdvertiserSuppressor.isReStudioFile(file)) null else delegate.collectNotificationData(project, file)
}

/** Removes the LSP4IJ suggestion provider from the project, so that only [FilteredLanguageServerSuggestionProvider] runs. */
class LanguageServerSuggestionFilterActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    EditorNotificationProvider.EP_NAME.getPoint(project).unregisterExtension(LanguageServerSuggestionEditorNotificationProvider::class.java)
    EditorNotifications.getInstance(project).updateAllNotifications()
  }
}
