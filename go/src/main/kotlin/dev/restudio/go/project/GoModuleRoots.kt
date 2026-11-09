package dev.restudio.go.project

import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Maps the Go modules of the project to the content roots of the IDE modules.
 *
 * - A `go.mod` directory inside a content root is already in the project. It gets no new root.
 * - Each `use` directory of a `go.work` file outside the content roots becomes a content root of the IDE module
 *   that holds the `go.work` file.
 * - The `vendor` directory of a Go module becomes an excluded folder when it holds `modules.txt`, because only
 *   `go mod vendor` writes that file.
 *
 * The service does not remove a content root when a `go.work` file drops a `use` directory.
 */
@OptIn(FlowPreview::class)
@Service(Service.Level.PROJECT)
class GoModuleRoots(private val project: Project, scope: CoroutineScope) {
  private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  init {
    scope.launch {
      requests.debounce(500).collectLatest { update() }
    }
  }

  /** Asks for an update. Many requests in a short time cause one update. */
  fun schedule() {
    requests.tryEmit(Unit)
  }

  private suspend fun update() {
    val changes = smartReadAction(project) { findChanges() }
    for ((module, change) in changes) {
      val model = readAction { ModuleRootManager.getInstance(module).modifiableModel }
      var done = false
      try {
        for (root in change.contentRoots) model.addContentEntry(root)
        var changed = change.contentRoots.isNotEmpty()
        for (vendor in change.excludedFolders) {
          val entry = model.contentEntries.firstOrNull { entry -> entry.file?.let { VfsUtilCore.isAncestor(it, vendor, false) } == true }
          if (entry != null && entry.excludeFolderUrls.none { it == vendor.url }) {
            entry.addExcludeFolder(vendor)
            changed = true
          }
        }
        if (changed) {
          LOG.info("Go module roots of ${module.name}: add $change")
          edtWriteAction {
            done = true
            if (module.isDisposed) model.dispose() else model.commit()
          }
        }
      }
      finally {
        if (!done) model.dispose()
      }
    }
  }

  private fun findChanges(): Map<Module, Change> {
    val scope = GlobalSearchScope.projectScope(project)
    val fileIndex = ProjectFileIndex.getInstance(project)
    val changes = LinkedHashMap<Module, Change>()
    val moduleDirectories = FilenameIndex.getVirtualFilesByName(GO_MOD, scope)
      .filter { "/vendor/" !in it.path }
      .mapNotNull { it.parent }
      .toMutableSet()

    for (goWork in FilenameIndex.getVirtualFilesByName(GO_WORK, scope)) {
      val module = fileIndex.getModuleForFile(goWork) ?: continue
      val text = try {
        VfsUtilCore.loadText(goWork)
      }
      catch (_: IOException) {
        continue
      }
      for (use in GoWorkParser.useDirectories(text)) {
        val directory = resolve(goWork.parent, use) ?: continue
        if (!directory.isDirectory) continue
        moduleDirectories += directory
        if (fileIndex.isInContent(directory) || fileIndex.isExcluded(directory)) continue
        changes.getOrPut(module) { Change() }.contentRoots += directory
      }
    }

    for (directory in moduleDirectories) {
      val vendor = directory.findChild(VENDOR) ?: continue
      if (vendor.findChild(VENDOR_MARKER) == null || fileIndex.isExcluded(vendor)) continue
      val module = fileIndex.getModuleForFile(directory)
                   ?: changes.entries.firstOrNull { (_, change) -> change.contentRoots.any { VfsUtilCore.isAncestor(it, directory, false) } }?.key
                   ?: continue
      changes.getOrPut(module) { Change() }.excludedFolders += vendor
    }
    return changes
  }

  private fun resolve(base: VirtualFile, path: String): VirtualFile? =
    try {
      LocalFileSystem.getInstance().findFileByNioFile(base.toNioPath().resolve(Path.of(path)).normalize())
    }
    catch (_: InvalidPathException) {
      null
    }
    catch (_: UnsupportedOperationException) {
      null
    }

  private class Change {
    val contentRoots: MutableSet<VirtualFile> = LinkedHashSet()
    val excludedFolders: MutableSet<VirtualFile> = LinkedHashSet()

    override fun toString(): String = "content roots $contentRoots, excluded folders $excludedFolders"
  }

  companion object {
    const val GO_MOD: String = "go.mod"
    const val GO_WORK: String = "go.work"
    const val VENDOR: String = "vendor"
    const val VENDOR_MARKER: String = "modules.txt"

    private val LOG = logger<GoModuleRoots>()

    fun getInstance(project: Project): GoModuleRoots = project.service()
  }
}

/** Reads the `use` directives of a `go.work` file. */
object GoWorkParser {
  private val useLine = Regex("""^use(?=[\s(]|$)\s*(.*)$""")

  /** Returns the paths of the `use` directives in their order, without quotes. */
  fun useDirectories(text: String): List<String> {
    val result = mutableListOf<String>()
    var inBlock = false
    for (raw in text.lineSequence()) {
      val line = raw.substringBefore("//").trim()
      if (inBlock) {
        if (line.startsWith(")")) inBlock = false else if (line.isNotEmpty()) result += unquote(line)
        continue
      }
      val rest = useLine.find(line)?.groupValues?.get(1)?.trim() ?: continue
      when {
        rest.startsWith("(") -> {
          val inner = rest.removePrefix("(").trim()
          if (inner.endsWith(")")) {
            inner.removeSuffix(")").trim().takeIf { it.isNotEmpty() }?.let { result += unquote(it) }
          }
          else {
            if (inner.isNotEmpty()) result += unquote(inner)
            inBlock = true
          }
        }
        rest.isNotEmpty() -> result += unquote(rest)
      }
    }
    return result
  }

  private fun unquote(text: String): String {
    if (text.length >= 2 && (text.first() == '"' && text.last() == '"' || text.first() == '`' && text.last() == '`')) {
      return text.substring(1, text.length - 1)
    }
    return text
  }
}

/** Updates the Go module roots when the project opens. */
class GoModuleRootsActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    GoModuleRoots.getInstance(project).schedule()
  }
}

/** Updates the Go module roots when a `go.mod`, a `go.work` or a `vendor/modules.txt` file changes. */
class GoModuleFilesListener(private val project: Project) : BulkFileListener {
  override fun after(events: List<VFileEvent>) {
    if (events.any { isGoModuleFile(it.path) }) GoModuleRoots.getInstance(project).schedule()
  }

  private fun isGoModuleFile(path: String): Boolean {
    val name = path.substringAfterLast('/')
    return name == GoModuleRoots.GO_MOD || name == GoModuleRoots.GO_WORK ||
           name == GoModuleRoots.VENDOR_MARKER && path.substringBeforeLast('/').endsWith("/" + GoModuleRoots.VENDOR)
  }
}
