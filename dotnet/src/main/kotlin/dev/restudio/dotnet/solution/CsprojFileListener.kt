package dev.restudio.dotnet.solution

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import dev.restudio.dotnet.DotnetBundle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * Updates the project file after the user adds, deletes, moves or renames a file in the IDE.
 *
 * An SDK-style project finds its files with globs, so an added file needs no change. The listener only:
 * - adds a `Compile` or `None` item for a new file when the project has no default items (an old-style project),
 * - removes the items of a deleted file,
 * - changes the paths of the items (and `DependentUpon`) of a moved or renamed file.
 *
 * A change from outside the IDE (for example `git checkout`) comes from a refresh. The listener ignores it.
 */
class CsprojFileListener(private val project: Project) : BulkFileListener {
  private sealed interface Change {
    data class Created(val path: Path, val isDirectory: Boolean) : Change
    data class Deleted(val path: Path, val isDirectory: Boolean) : Change
    data class Moved(val from: Path, val to: Path) : Change
  }

  override fun after(events: List<VFileEvent>) {
    val changes = events.filter { !it.isFromRefresh }.mapNotNull(::change)
    if (changes.isEmpty() || project.isDisposed) return
    ApplicationManager.getApplication().invokeLater({ apply(changes) }, project.disposed)
  }

  private fun change(event: VFileEvent): Change? = when (event) {
    is VFileCreateEvent -> Change.Created(Path.of(event.path), event.isDirectory)
    is VFileCopyEvent -> Change.Created(Path.of(event.path), event.file.isDirectory)
    is VFileDeleteEvent -> Change.Deleted(Path.of(event.path), event.file.isDirectory)
    is VFileMoveEvent -> Change.Moved(Path.of(event.oldPath), Path.of(event.path))
    is VFilePropertyChangeEvent -> if (event.isRename) Change.Moved(Path.of(event.oldPath), Path.of(event.path)) else null
    else -> null
  }

  private fun apply(changes: List<Change>) {
    val edits = LinkedHashMap<Path, MutableList<(String) -> String?>>()
    fun edit(file: Path?, action: (String) -> String?) {
      if (file != null) edits.getOrPut(file) { ArrayList() } += action
    }
    for (change in changes) {
      when (change) {
        is Change.Created -> {
          if (change.isDirectory || isIgnored(change.path)) continue
          val file = projectFile(change.path) ?: continue
          val relative = relative(file, change.path)
          edit(file) { text -> if (CsprojEditor.hasDefaultItems(text, relative)) null else CsprojEditor.addItem(text, CsprojEditor.itemType(relative), relative) }
        }
        is Change.Deleted -> {
          if (isIgnored(change.path)) continue
          val file = projectFile(change.path) ?: continue
          edit(file) { text -> CsprojEditor.removeItems(text, relative(file, change.path), change.isDirectory) }
        }
        is Change.Moved -> {
          if (isIgnored(change.from) && isIgnored(change.to)) continue
          val from = projectFile(change.from)
          val to = projectFile(change.to)
          if (from != null && from == to) {
            edit(from) { text -> CsprojEditor.renamePath(text, relative(from, change.from), relative(from, change.to)) }
            continue
          }
          val isDirectory = Files.isDirectory(change.to)
          if (from != null) edit(from) { text -> CsprojEditor.removeItems(text, relative(from, change.from), isDirectory) }
          if (to != null && !isDirectory) {
            val relative = relative(to, change.to)
            edit(to) { text -> if (CsprojEditor.hasDefaultItems(text, relative)) null else CsprojEditor.addItem(text, CsprojEditor.itemType(relative), relative) }
          }
        }
      }
    }
    for ((file, actions) in edits) write(file, actions)
  }

  private fun write(file: Path, actions: List<(String) -> String?>) {
    val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file) ?: return
    val documents = FileDocumentManager.getInstance()
    val document = documents.getDocument(virtualFile) ?: return
    val text = actions.fold(document.text) { current, action -> action(current) ?: current }
    if (text == document.text) return
    LOG.info("Update ${file.fileName} after a file change")
    WriteCommandAction.writeCommandAction(project).withName(DotnetBundle.message("solution.csproj.update")).run<RuntimeException> {
      document.setText(text)
      documents.saveDocument(document)
    }
  }

  /** The project file of the nearest project directory that holds [path]. */
  private fun projectFile(path: Path): Path? {
    SolutionModel.getInstance(project).snapshot()?.projectOf(path)?.let { return it.file }
    var directory = path.parent
    while (directory != null) {
      val file = runCatching { Files.list(directory).use { files -> files.filter { it.extension == "csproj" }.findFirst().orElse(null) } }.getOrNull()
      if (file != null) return file
      if (project.basePath?.let { directory == Path.of(it) } == true) return null
      directory = directory.parent
    }
    return null
  }

  private fun relative(projectFile: Path, path: Path): String = projectFile.parent.relativize(path).toString()

  /** The project files themselves, and the files below `bin` and `obj`, need no item. */
  private fun isIgnored(path: Path): Boolean {
    val name = path.fileName?.toString() ?: return true
    if (name.endsWith("proj", ignoreCase = true) || name.endsWith(".sln", ignoreCase = true) || name.endsWith(".slnx", ignoreCase = true)) return true
    val file = projectFile(path) ?: return true
    val relative = file.parent.relativize(path)
    val first = relative.firstOrNull()?.toString() ?: return true
    return first.equals("bin", ignoreCase = true) || first.equals("obj", ignoreCase = true) || first.startsWith("..")
  }

  private companion object {
    val LOG = logger<CsprojFileListener>()
  }
}
