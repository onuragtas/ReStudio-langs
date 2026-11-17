package dev.restudio.php.lsp

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import dev.restudio.core.lsp.TracingClientFeatures
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import dev.restudio.php.PhpBundle
import dev.restudio.php.docker.ContainerUris
import dev.restudio.php.docker.PhpDocker
import dev.restudio.php.docker.PhpDockerTarget
import dev.restudio.php.docker.fileUri
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.CreateFile
import org.eclipse.lsp4j.DeleteFile
import org.eclipse.lsp4j.RenameFile
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Removes the edits in `vendor/` from the rename result of the PHP language server.
 *
 * Phpactor renames a symbol also in the Composer packages, for example in the Laravel sources (see the plan).
 * The packages belong to Composer, so the IDE must not change them. The filter looks at the `textDocument/rename`
 * responses and at the `workspace/applyEdit` requests. It tells the user how many files it left unchanged.
 *
 * When Phpactor runs in a Docker container ([PhpDocker]), the class also maps the file URIs. The server gets the
 * container paths, and the IDE gets the host paths back.
 */
class PhpClientFeatures : TracingClientFeatures() {
  private val renameRequests: MutableSet<String> = ConcurrentHashMap.newKeySet()

  private val dockerTarget: PhpDockerTarget? by lazy { PhpDocker.target(project) }

  override fun getFileUri(file: VirtualFile): URI? =
    dockerTarget?.containerUri(file.path) ?: super.getFileUri(file)

  override fun findFileByUri(fileUri: String): VirtualFile? {
    val hostPath = dockerTarget?.hostPath(fileUri) ?: return super.findFileByUri(fileUri)
    return LocalFileSystem.getInstance().findFileByPath(hostPath)
  }

  override fun initializeParams(initializeParams: InitializeParams) {
    super.initializeParams(initializeParams)
    val target = dockerTarget ?: return
    val rootUri = fileUri(target.containerRoot).toString()
    initializeParams.rootUri = rootUri
    @Suppress("DEPRECATION")
    initializeParams.rootPath = target.containerRoot
    // The workspace folders come with the host URIs. A folder outside the project stays as it is.
    initializeParams.workspaceFolders = initializeParams.workspaceFolders?.map { folder ->
      val hostPath = runCatching { URI(folder.uri).path }.getOrNull()
      WorkspaceFolder(hostPath?.let(target::containerUri)?.toString() ?: folder.uri, folder.name)
    }
  }

  override fun filterMessage(message: Message): Message {
    val definition = (message as? RequestMessage)?.takeIf { it.method == DEFINITION }?.params as? TextDocumentPositionParams
    val hostUri = definition?.textDocument?.uri
    // The filter sees the messages in both directions. The server sends only container URIs, so the change does nothing there.
    dockerTarget?.let { target ->
      when (message) {
        is RequestMessage -> ContainerUris.toContainer(message.params, target)
        is NotificationMessage -> ContainerUris.toContainer(message.params, target)
      }
    }
    when (message) {
      is RequestMessage -> when (message.method) {
        RENAME -> renameRequests += message.id
        APPLY_EDIT -> (message.params as? ApplyWorkspaceEditParams)?.edit?.let(::removeVendorEdits)
        DEFINITION -> if (definition != null && hostUri != null) {
          PhpDeclarations.getInstance(project).request(message.id, hostUri, definition.textDocument.uri, definition.position)
        }
      }
      is ResponseMessage -> {
        if (renameRequests.remove(message.id)) (message.result as? WorkspaceEdit)?.let(::removeVendorEdits)
        PhpDeclarations.getInstance(project).response(message.id, message.result)
      }
    }
    return message
  }

  private fun removeVendorEdits(edit: WorkspaceEdit) {
    val skipped = VendorEdits.remove(edit)
    if (skipped.isEmpty()) return
    NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
      .createNotification(PhpBundle.message("rename.vendor.skipped", skipped.size), NotificationType.INFORMATION)
      .notify(project)
  }

  companion object {
    const val NOTIFICATION_GROUP: String = "ReStudio PHP"
    private const val RENAME = "textDocument/rename"
    private const val APPLY_EDIT = "workspace/applyEdit"
    private const val DEFINITION = "textDocument/definition"
  }
}

/** Removes the changes of the files in a `vendor/` directory from a [WorkspaceEdit]. */
object VendorEdits {
  fun isVendor(uri: String): Boolean = "/vendor/" in uri

  /** Changes [edit] in place. Returns the URIs of the files that it removed. */
  fun remove(edit: WorkspaceEdit): Set<String> {
    val skipped = linkedSetOf<String>()
    edit.changes?.let { changes ->
      val vendor = changes.keys.filter(::isVendor)
      skipped += vendor
      if (vendor.isNotEmpty()) edit.changes = changes.filterKeys { !isVendor(it) }
    }
    edit.documentChanges?.let { changes ->
      val kept = changes.filter { change ->
        val vendor = uris(change).filter(::isVendor)
        skipped += vendor
        vendor.isEmpty()
      }
      if (kept.size != changes.size) edit.documentChanges = kept
    }
    return skipped
  }

  private fun uris(change: Either<TextDocumentEdit, ResourceOperation>): List<String> =
    if (change.isLeft) listOf(change.left.textDocument.uri)
    else when (val operation = change.right) {
      is CreateFile -> listOf(operation.uri)
      is DeleteFile -> listOf(operation.uri)
      is RenameFile -> listOf(operation.oldUri, operation.newUri)
      else -> emptyList()
    }
}
