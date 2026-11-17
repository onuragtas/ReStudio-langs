package dev.restudio.php.lsp

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolDeclaration
import com.intellij.model.psi.PsiSymbolDeclarationProvider
import com.intellij.model.psi.PsiSymbolService
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.usages.LSPUsageTriggeredPsiElement
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the declarations that the PHP language server reported for a Cmd+click.
 *
 * A Cmd+click sends `textDocument/definition`. When the result contains the position of the request, the caret is on
 * the declaration itself. The IDE must then show the usages. TextMate files have no PSI declarations, so the
 * [PhpDeclarationProvider] gives this declaration to the IDE.
 */
@Service(Service.Level.PROJECT)
class PhpDeclarations {
  private val requests = ConcurrentHashMap<String, Request>()

  /** The position of the last declaration in each file. The key is the host path of the file. */
  private val declarations = ConcurrentHashMap<String, Position>()

  /** Records a definition request. [hostUri] is the URI before the Docker mapping, [serverUri] is the URI after it. */
  fun request(id: String, hostUri: String, serverUri: String, position: Position) {
    val path = runCatching { URI(hostUri).path }.getOrNull() ?: return
    requests[id] = Request(path, serverUri, position)
  }

  /** Records the result of the definition request [id]. */
  fun response(id: String, result: Any?) {
    val request = requests.remove(id) ?: return
    if (ranges(result, request.serverUri).any { it.contains(request.position) }) declarations[request.path] = request.position
    else declarations.remove(request.path)
  }

  /** Returns the declaration position in the file [path], or null. */
  fun declaration(path: String): Position? = declarations[path]

  private class Request(val path: String, val serverUri: String, val position: Position)

  companion object {
    fun getInstance(project: Project): PhpDeclarations = project.service()

    /** Returns the ranges in [result] that are in the file [uri]. The result is a location, a list of them, or links. */
    fun ranges(result: Any?, uri: String): List<Range> {
      val items = when (result) {
        is Either<*, *> -> listOfNotNull(result.left, result.right).flatMap { it as? List<*> ?: listOf(it) }
        is List<*> -> result
        else -> listOf(result)
      }
      return items.mapNotNull { item ->
        when (item) {
          is Location -> item.range.takeIf { sameUri(item.uri, uri) }
          is LocationLink -> item.targetRange.takeIf { sameUri(item.targetUri, uri) }
          else -> null
        }
      }
    }

    private fun sameUri(a: String, b: String): Boolean =
      a == b || runCatching { URI(a).path == URI(b).path }.getOrDefault(false)

    private fun Range.contains(position: Position): Boolean =
      compare(start, position) <= 0 && compare(position, end) <= 0

    private fun compare(a: Position, b: Position): Int =
      if (a.line != b.line) a.line.compareTo(b.line) else a.character.compareTo(b.character)
  }
}

/**
 * Gives the IDE the declaration under the caret that [PhpDeclarations] recorded.
 *
 * Without it, a Cmd+click on a declaration does not show the usages.
 */
class PhpDeclarationProvider : PsiSymbolDeclarationProvider {
  override fun getDeclarations(element: PsiElement, offsetInElement: Int): Collection<PsiSymbolDeclaration> {
    if (offsetInElement < 0) return emptyList()
    val file = element as? PsiFile ?: element.containingFile ?: return emptyList()
    val virtualFile = file.virtualFile ?: return emptyList()
    if (virtualFile.extension != "php") return emptyList()
    val position = PhpDeclarations.getInstance(file.project).declaration(virtualFile.path) ?: return emptyList()
    val document = LSPIJUtils.getDocument(virtualFile) ?: return emptyList()
    val elementStart = element.textRange?.startOffset ?: return emptyList()
    val offset = elementStart + offsetInElement
    val word = LSPIJUtils.getWordRangeAt(document, file, offset) ?: return emptyList()
    if (!word.containsOffset(LSPIJUtils.toOffset(position, document))) return emptyList()
    return listOf(Declaration(element, word.shiftLeft(elementStart), LSPUsageTriggeredPsiElement(file, word)))
  }

  private class Declaration(
    private val element: PsiElement,
    private val range: TextRange,
    private val target: PsiElement,
  ) : PsiSymbolDeclaration {
    override fun getDeclaringElement(): PsiElement = element
    override fun getRangeInDeclaringElement(): TextRange = range
    override fun getSymbol(): Symbol = PsiSymbolService.getInstance().asSymbol(target)
  }
}
