package dev.restudio.php.lsp

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import dev.restudio.core.lsp.TracingClientFeatures
import dev.restudio.php.PhpBundle
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.CreateFile
import org.eclipse.lsp4j.DeleteFile
import org.eclipse.lsp4j.RenameFile
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.ConcurrentHashMap

/**
 * Removes the edits in `vendor/` from the rename result of the PHP language server.
 *
 * Phpactor renames a symbol also in the Composer packages, for example in the Laravel sources (see the plan).
 * The packages belong to Composer, so the IDE must not change them. The filter looks at the `textDocument/rename`
 * responses and at the `workspace/applyEdit` requests. It tells the user how many files it left unchanged.
 */
class PhpClientFeatures : TracingClientFeatures() {
  private val renameRequests: MutableSet<String> = ConcurrentHashMap.newKeySet()

  override fun <S : LanguageServer> createLauncherBuilder(): Launcher.Builder<S> =
    super.createLauncherBuilder<S>().wrapMessages { consumer -> MessageConsumer { message -> consumer.consume(filter(message)) } }

  private fun filter(message: Message): Message {
    when (message) {
      is RequestMessage -> when (message.method) {
        RENAME -> renameRequests += message.id
        APPLY_EDIT -> (message.params as? ApplyWorkspaceEditParams)?.edit?.let(::removeVendorEdits)
      }
      is ResponseMessage -> if (renameRequests.remove(message.id)) (message.result as? WorkspaceEdit)?.let(::removeVendorEdits)
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
