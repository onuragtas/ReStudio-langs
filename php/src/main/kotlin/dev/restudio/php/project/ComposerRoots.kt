package dev.restudio.php.project

import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Sets the source roots, the test source roots and the excluded `vendor/` directory from `composer.json`.
 *
 * It looks at the `composer.json` file in each content root. It only adds roots. It does not remove a root that the
 * user or an earlier version of `composer.json` added. `vendor/` is excluded from the project, and
 * [ComposerVendorLibraryProvider] adds it back as a library. Thus Find in Files skips it in the project scope, but
 * navigation and the "All Places" scope find the package sources.
 */
@Service(Service.Level.PROJECT)
class ComposerRoots(private val project: Project, scope: CoroutineScope) {
  private val requests = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  init {
    scope.launch {
      requests.collectLatest {
        // A `composer install` changes many files. Wait until the events stop.
        delay(500)
        sync()
      }
    }
  }

  fun request() {
    requests.tryEmit(Unit)
  }

  private suspend fun sync() {
    val changes = readAction { ModuleManager.getInstance(project).modules.mapNotNull { module -> missingRoots(module) } }
    if (changes.isEmpty()) return
    edtWriteAction {
      for (change in changes) {
        if (change.module.isDisposed) continue
        val model = ModuleRootManager.getInstance(change.module).modifiableModel
        val entries = model.contentEntries.associateBy { it.url }
        for (root in change.roots) {
          val entry = entries[root.contentRoot] ?: continue
          when (root.kind) {
            RootKind.SOURCE -> entry.addSourceFolder(root.url, false)
            RootKind.TEST -> entry.addSourceFolder(root.url, true)
            RootKind.EXCLUDED -> entry.addExcludeFolder(root.url)
          }
        }
        model.commit()
      }
    }
  }

  /** Returns the roots of [module] that `composer.json` asks for and that the module does not have yet. */
  private fun missingRoots(module: Module): ModuleChange? {
    val roots = mutableListOf<Root>()
    for (entry in ModuleRootManager.getInstance(module).contentEntries) {
      val directory = entry.file ?: continue
      val composer = directory.findChild(COMPOSER_JSON) ?: continue
      val autoload = ComposerAutoload.parse(String(composer.contentsToByteArray(), Charsets.UTF_8))
      val known = entry.sourceFolders.map { it.url }.toSet() + entry.excludeFolderUrls
      fun add(path: String, kind: RootKind) {
        val child = directory.findFileByRelativePath(path)?.takeIf { it.isDirectory } ?: return
        if (child.url !in known && roots.none { it.url == child.url }) roots += Root(entry.url, child.url, kind)
      }
      autoload.sources.forEach { add(it, RootKind.SOURCE) }
      autoload.tests.forEach { add(it, RootKind.TEST) }
      add(VENDOR, RootKind.EXCLUDED)
    }
    return if (roots.isEmpty()) null else ModuleChange(module, roots)
  }

  private enum class RootKind { SOURCE, TEST, EXCLUDED }

  private data class Root(val contentRoot: String, val url: String, val kind: RootKind)

  private data class ModuleChange(val module: Module, val roots: List<Root>)

  companion object {
    const val COMPOSER_JSON: String = "composer.json"
    const val VENDOR: String = "vendor"

    fun getInstance(project: Project): ComposerRoots = project.service()

    /** The `vendor/` directories of the Composer projects in the content roots. */
    fun vendorDirectories(project: Project): List<VirtualFile> =
      ModuleManager.getInstance(project).modules.asSequence()
        .flatMap { ModuleRootManager.getInstance(it).contentRoots.asSequence() }
        .filter { it.findChild(COMPOSER_JSON) != null }
        .mapNotNull { it.findChild(VENDOR)?.takeIf(VirtualFile::isDirectory) }
        .distinct()
        .toList()
  }
}

/** Reads `composer.json` when the project opens. */
class ComposerRootsActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    ComposerRoots.getInstance(project).request()
  }
}

/** Reads `composer.json` again when it changes, and when `vendor/` appears after `composer install`. */
class ComposerJsonListener(private val project: Project) : BulkFileListener {
  override fun after(events: List<VFileEvent>) {
    val relevant = events.any { event ->
      val path = event.path
      "/${ComposerRoots.VENDOR}/" !in path && (path.endsWith("/${ComposerRoots.COMPOSER_JSON}") || path.endsWith("/${ComposerRoots.VENDOR}"))
    }
    if (relevant) ComposerRoots.getInstance(project).request()
  }
}

/** Adds the `vendor/` directory of each Composer project as a library, because [ComposerRoots] excludes it. */
class ComposerVendorLibraryProvider : AdditionalLibraryRootsProvider() {
  override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> =
    ComposerRoots.vendorDirectories(project).map { SyntheticLibrary.newImmutableLibrary("composer:${it.path}", listOf(it), emptyList(), emptySet(), null) }

  override fun getRootsToWatch(project: Project): Collection<VirtualFile> = ComposerRoots.vendorDirectories(project)
}
