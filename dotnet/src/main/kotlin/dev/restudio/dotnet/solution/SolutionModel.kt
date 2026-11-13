package dev.restudio.dotnet.solution

import com.intellij.execution.RunManagerListener
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.Alarm
import dev.restudio.dotnet.lsp.RoslynWorkspace
import dev.restudio.dotnet.project.DotnetMsbuild
import dev.restudio.dotnet.project.DotnetProject
import dev.restudio.dotnet.project.MsbuildProject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.jetbrains.annotations.TestOnly

/** One project of the solution. [msbuild] is `null` until MSBuild evaluated the project. */
data class SolutionProject(val project: DotnetProject, val msbuild: MsbuildProject?, val content: ProjectContent) {
  val file: Path get() = project.file
  val directory: Path get() = project.directory
  val name: String get() = project.name
}

/**
 * The data that the Solution view shows. It does not change. [SolutionModel] makes a new snapshot after each change.
 *
 * [solution] is `null` when the directory has `.csproj` files but no solution. Then [root] holds all the projects.
 */
class SolutionSnapshot(val directory: Path, val solution: SolutionStructure?, val root: SolutionFolder, val projects: Map<Path, SolutionProject>) {
  val file: Path? get() = solution?.file

  /** The project whose directory is [directory]. */
  fun projectAt(directory: Path): SolutionProject? = projects.values.firstOrNull { it.directory == directory }

  /** The project that holds [path], or `null`. The nearest project directory wins. */
  fun projectOf(path: Path): SolutionProject? = projects.values.filter { path.startsWith(it.directory) }.maxByOrNull { it.directory.nameCount }
}

/**
 * Keeps the [SolutionSnapshot] of the project and updates it.
 *
 * The first snapshot comes from the solution file and the project files only. Then MSBuild evaluates each project
 * in the background, and each result makes a new snapshot. A change of a file below a project directory evaluates
 * that project again, because a new file can change the items of the default globs.
 */
@Service(Service.Level.PROJECT)
class SolutionModel(private val project: Project) : Disposable {
  @Volatile
  private var snapshot: SolutionSnapshot? = null
  private val loading = AtomicBoolean()
  private val pending: MutableSet<Path> = ConcurrentHashMap.newKeySet()
  private val evaluateAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
  private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
  private var directoryOverride: Path? = null

  init {
    val connection = project.messageBus.connect(this)
    connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
      override fun after(events: List<VFileEvent>) = onFileEvents(events)
    })
    // The view shows the startup project in bold.
    connection.subscribe(RunManagerListener.TOPIC, object : RunManagerListener {
      override fun runConfigurationSelected(settings: RunnerAndConfigurationSettings?) = refreshView()
    })
  }

  /** The solution directory: the base directory of the IDE project. */
  val directory: Path? get() = directoryOverride ?: project.basePath?.let { Path.of(it) }

  /** Returns the snapshot, or `null` while the first load runs. It does not block. */
  fun snapshot(): SolutionSnapshot? {
    val current = snapshot
    if (current == null) reloadInBackground()
    return current
  }

  /** Reads the solution again, in the background. */
  fun reloadInBackground() {
    if (!loading.compareAndSet(false, true)) return
    ApplicationManager.getApplication().executeOnPooledThread {
      try {
        val loaded = read() ?: return@executeOnPooledThread
        snapshot = loaded
        refreshView()
        loaded.projects.keys.forEach(::scheduleEvaluation)
      }
      finally {
        loading.set(false)
      }
    }
  }

  /** Reads the solution and evaluates all the projects now. It blocks, so call it from a background thread or from a test only. */
  fun reloadNow(): SolutionSnapshot? {
    val loaded = read() ?: return null
    snapshot = loaded
    for (file in loaded.projects.keys) evaluate(file)
    return snapshot
  }

  @TestOnly
  fun useDirectory(directory: Path) {
    directoryOverride = directory
    snapshot = null
  }

  private fun read(): SolutionSnapshot? {
    val directory = directory ?: return null
    val (structure, root) = when (val workspace = RoslynWorkspace.find(directory) ?: return null) {
      is RoslynWorkspace.Solution -> {
        val structure = SolutionFile.read(workspace.file) ?: return null
        structure to structure.root
      }
      is RoslynWorkspace.Projects -> null to SolutionFolder(directory.fileName.toString(), projects = workspace.files)
    }
    val projects = LinkedHashMap<Path, SolutionProject>()
    for (file in root.allProjects) {
      if (!Files.isRegularFile(file)) continue
      val parsed = runCatching { DotnetProject.parse(file, Files.readString(file)) }.getOrNull() ?: continue
      val cached = snapshot?.projects?.get(file)
      projects[file] = cached ?: SolutionProject(parsed, null, ProjectContent(parsed.directory, null))
    }
    return SolutionSnapshot(directory, structure, root, projects)
  }

  private fun scheduleEvaluation(file: Path) {
    if (!pending.add(file)) return
    evaluateAlarm.addRequest({
      pending.remove(file)
      evaluate(file)
    }, EVALUATE_DELAY_MS)
  }

  private fun evaluate(file: Path) {
    val msbuild = DotnetMsbuild.evaluate(file)
    if (msbuild == null) {
      LOG.info("MSBuild did not evaluate $file. The Solution view uses the default globs.")
      return
    }
    synchronized(this) {
      val current = snapshot ?: return
      val old = current.projects[file] ?: return
      val updated = old.copy(project = old.project.withMsbuild(msbuild), msbuild = msbuild, content = ProjectContent(old.directory, msbuild.items))
      snapshot = SolutionSnapshot(current.directory, current.solution, current.root, current.projects + (file to updated))
    }
    refreshView()
  }

  private fun onFileEvents(events: List<VFileEvent>) {
    val current = snapshot ?: return
    var reload = false
    val projects = HashSet<Path>()
    for (event in events) {
      val paths = buildList {
        event.path.let { add(Path.of(it)) }
        if (event is VFileMoveEvent) add(Path.of(event.oldPath))
        if (event is VFilePropertyChangeEvent && event.isRename) add(Path.of(event.oldPath))
      }
      for (path in paths) {
        val name = path.fileName?.toString() ?: continue
        if (path == current.file) {
          reload = true
          continue
        }
        if (name.endsWith(".props", ignoreCase = true) || name.endsWith(".targets", ignoreCase = true)) {
          projects += current.projects.keys
          continue
        }
        val owner = current.projectOf(path) ?: continue
        if (event is VFileContentChangeEvent && path != owner.file) continue
        if (path.startsWith(owner.directory.resolve("bin")) || path.startsWith(owner.directory.resolve("obj"))) continue
        projects.add(owner.file)
      }
    }
    if (reload) {
      reloadInBackground()
      return
    }
    for (file in projects) {
      DotnetMsbuild.invalidate(file)
      scheduleEvaluation(file)
    }
  }

  /** Updates the Solution view. Many calls in a short time make one update. */
  fun refreshView() {
    refreshAlarm.cancelAllRequests()
    refreshAlarm.addRequest({
      if (project.isDisposed) return@addRequest
      ProjectView.getInstance(project).getProjectViewPaneById(SolutionViewPane.ID)?.updateFromRoot(true)
    }, REFRESH_DELAY_MS, ModalityState.any())
  }

  override fun dispose() = Unit

  companion object {
    private val LOG = logger<SolutionModel>()
    private const val EVALUATE_DELAY_MS = 300
    private const val REFRESH_DELAY_MS = 100

    fun getInstance(project: Project): SolutionModel = project.service()
  }
}
