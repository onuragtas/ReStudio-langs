package dev.restudio.dotnet.solution

import dev.restudio.dotnet.project.MsbuildItem
import java.nio.file.Path

/** One entry of a directory in the Solution view. [displayPath] is the place in the tree, [realPath] is the file on disk. */
sealed interface ViewEntry {
  val displayPath: Path
  val inProject: Boolean
  val name: String get() = displayPath.fileName.toString()
}

/** A file. [nested] are the dependent files that the view shows below this file. */
data class FileEntry(
  override val displayPath: Path,
  val realPath: Path,
  override val inProject: Boolean,
  val nested: List<FileEntry> = emptyList(),
) : ViewEntry {
  val isLink: Boolean get() = displayPath != realPath
}

/** A directory. [realPath] is `null` for a folder that only links make, which does not exist on disk. */
data class DirEntry(override val displayPath: Path, val realPath: Path?, override val inProject: Boolean) : ViewEntry

/**
 * The files that belong to one project.
 *
 * With [items] from MSBuild, a file belongs to the project when it is an item. This applies `Compile Remove`,
 * `None Include` and all the default globs of the SDK. Without [items] (MSBuild did not evaluate the project yet),
 * the class uses the default globs of an SDK-style project: all the files, but not `bin`, `obj`, hidden files and project files.
 */
class ProjectContent(val directory: Path, items: List<MsbuildItem>?) {
  val isEvaluated: Boolean = items != null

  private val included = HashSet<Path>()
  private val links = HashMap<Path, Path>()
  private val dependentUpon = HashMap<Path, String>()
  private val visibleDirectories = HashSet<Path>()

  init {
    for (item in items.orEmpty()) {
      if (!item.visible) continue
      val path = item.path.normalize()
      if (item.type == "Folder") {
        addDirectory(path)
        continue
      }
      val display = displayPath(path, item.link)
      if (display != path) links[display] = path else included.add(path)
      item.dependentUpon?.let { dependentUpon[display] = it }
      display.parent?.let(::addDirectory)
    }
  }

  private fun displayPath(path: Path, link: String?): Path = when {
    link != null -> directory.resolve(link).normalize()
    path.startsWith(directory) -> path
    else -> directory.resolve(path.fileName)
  }

  private fun addDirectory(path: Path) {
    var current: Path? = path
    while (current != null && current.startsWith(directory) && current != directory && visibleDirectories.add(current)) current = current.parent
  }

  /** `true` when the file or directory at [path] belongs to the project. */
  fun isIncluded(path: Path, isDirectory: Boolean): Boolean {
    if (!isEvaluated) return isDefaultIncluded(path, isDirectory)
    return if (isDirectory) path in visibleDirectories else path in included
  }

  private fun isDefaultIncluded(path: Path, isDirectory: Boolean): Boolean {
    if (!path.startsWith(directory) || path == directory) return false
    val relative = directory.relativize(path)
    if (relative.any { it.toString().startsWith(".") }) return false
    val first = relative.first().toString()
    if (first.equals("bin", ignoreCase = true) || first.equals("obj", ignoreCase = true)) return false
    if (isDirectory) return true
    val name = path.fileName.toString()
    return !name.endsWith("proj", ignoreCase = true) && !name.endsWith(".user", ignoreCase = true) && !name.endsWith(".sln", ignoreCase = true)
  }

  /**
   * Returns the entries of the directory at [displayDirectory].
   *
   * [listing] holds the names on disk and `true` for a directory. It is empty for a folder that only links make.
   * With [showAll], the result also holds the files and directories that do not belong to the project.
   */
  fun children(displayDirectory: Path, listing: List<Pair<String, Boolean>>, showAll: Boolean): List<ViewEntry> {
    val result = LinkedHashMap<Path, ViewEntry>()
    for ((name, isDirectory) in listing) {
      val path = displayDirectory.resolve(name)
      val inProject = isIncluded(path, isDirectory)
      if (!inProject && !showAll) continue
      result[path] = if (isDirectory) DirEntry(path, path, inProject) else FileEntry(path, path, inProject)
    }
    for ((display, real) in links) {
      if (display.parent == displayDirectory) result[display] = FileEntry(display, real, true)
    }
    for (directory in visibleDirectories) {
      if (directory.parent == displayDirectory && directory !in result) result[directory] = DirEntry(directory, null, true)
    }
    return nest(result.values.toList())
  }

  /** Puts each dependent file below its parent file. */
  private fun nest(entries: List<ViewEntry>): List<ViewEntry> {
    val files = entries.filterIsInstance<FileEntry>().associateBy { it.name }
    val parents = HashMap<String, String>()
    for (file in files.values) {
      val parent = parentName(file, files.keys) ?: continue
      // Do not make a cycle.
      var current: String? = parent
      var cycle = false
      while (current != null) {
        if (current == file.name) {
          cycle = true
          break
        }
        current = parents[current]
      }
      if (!cycle) parents[file.name] = parent
    }
    val children = parents.entries.groupBy({ it.value }, { it.key })
    fun build(file: FileEntry): FileEntry = file.copy(nested = children[file.name].orEmpty().sorted().mapNotNull { files[it] }.map(::build))
    return entries.mapNotNull { entry ->
      when {
        entry !is FileEntry -> entry
        entry.name in parents -> null
        else -> build(entry)
      }
    }
  }

  private fun parentName(file: FileEntry, names: Set<String>): String? {
    dependentUpon[file.displayPath]?.let { value ->
      val name = value.replace('\\', '/').substringAfterLast('/')
      if (name in names && name != file.name) return name
    }
    return NestingRules.parent(file.name, names)
  }
}

/**
 * The name rules that put a generated or dependent file below its parent file, as Visual Studio does.
 *
 * - `Index.razor.cs` and `Index.razor.css` go below `Index.razor`. The same applies to `.cshtml`, `.xaml` and others.
 * - `Form1.Designer.cs` goes below `Form1.cs`, and `Resources.Designer.cs` goes below `Resources.resx`.
 * - `appsettings.Development.json` goes below `appsettings.json`, and `Web.Release.config` goes below `Web.config`.
 */
object NestingRules {
  private val CODE_BEHIND_PARENTS = setOf("razor", "cshtml", "xaml", "axaml", "aspx", "ascx", "master", "asax", "ashx", "asmx", "svc", "tt", "resx")
  private val VARIANT_EXTENSIONS = setOf("json", "config")

  fun parent(name: String, names: Set<String>): String? {
    val dot = name.lastIndexOf('.')
    if (dot <= 0) return null
    val base = name.substring(0, dot)
    val extension = name.substring(dot + 1).lowercase()
    if (base.substringAfterLast('.', "").lowercase() in CODE_BEHIND_PARENTS && base in names) return base
    if (base.endsWith(".Designer", ignoreCase = true)) {
      val owner = base.dropLast(".Designer".length)
      listOf("$owner.cs", "$owner.resx", "$owner.vb", "$owner.settings").firstOrNull { it in names && it != name }?.let { return it }
    }
    if (extension in VARIANT_EXTENSIONS) {
      val first = name.indexOf('.')
      if (first in 1 until dot) {
        val candidate = name.substring(0, first) + name.substring(dot)
        if (candidate in names && candidate != name) return candidate
      }
    }
    return null
  }
}
