package dev.restudio.dotnet.solution

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * A solution folder. The root folder of a solution has the name of the solution.
 *
 * [projects] are the project files and [files] are the solution items. All the paths are full paths.
 */
data class SolutionFolder(
  val name: String,
  val folders: List<SolutionFolder> = emptyList(),
  val projects: List<Path> = emptyList(),
  val files: List<Path> = emptyList(),
) {
  /** All the project files in this folder and in its subfolders. */
  val allProjects: List<Path> get() = projects + folders.flatMap { it.allProjects }
}

/** The structure of a `.sln` or `.slnx` file: the solution folders, the projects and the solution items. */
data class SolutionStructure(val file: Path, val root: SolutionFolder) {
  val name: String get() = root.name
}

/**
 * Reads the folder structure of a solution file.
 *
 * `dotnet sln list` gives the projects, but not the solution folders and the solution items. Thus this class reads the file.
 */
object SolutionFile {
  private const val FOLDER_TYPE = "2150E333-8FDC-42A3-9474-1A3956D46DE8"
  private val PROJECT_EXTENSIONS = setOf("csproj", "fsproj", "vbproj")
  private val PROJECT_LINE = Regex("""^Project\("\{([^}]+)}"\)\s*=\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*"\{([^}]+)}"""")
  private val NESTED_LINE = Regex("""^\{([^}]+)}\s*=\s*\{([^}]+)}""")
  private val ITEM_LINE = Regex("""^([^=]+?)\s*=\s*(.+)$""")

  /** Reads [file]. It returns `null` when the file cannot be read. */
  fun read(file: Path): SolutionStructure? {
    val text = runCatching { Files.readString(file) }.getOrNull() ?: return null
    return if (file.fileName.toString().endsWith(".slnx", ignoreCase = true)) parseSlnx(file, text) else parseSln(file, text)
  }

  /** Reads the classic text format. */
  fun parseSln(file: Path, text: String): SolutionStructure {
    val directory = file.toAbsolutePath().parent
    class Entry(val id: String, val name: String, val path: String, val folder: Boolean) {
      val items = ArrayList<Path>()
      var parent: String? = null
    }

    val entries = LinkedHashMap<String, Entry>()
    var current: Entry? = null
    var inItems = false
    var inNested = false
    for (raw in text.lines()) {
      val line = raw.trim()
      when {
        line.startsWith("Project(") -> {
          val match = PROJECT_LINE.find(line) ?: continue
          val (type, name, path, id) = match.destructured
          current = Entry(id.uppercase(), name, path, type.equals(FOLDER_TYPE, ignoreCase = true)).also { entries[it.id] = it }
        }
        line == "EndProject" -> current = null
        line.startsWith("ProjectSection(SolutionItems)") -> inItems = true
        line == "EndProjectSection" -> inItems = false
        inItems && current != null -> ITEM_LINE.find(line)?.let { current.items.add(resolve(directory, it.groupValues[1])) }
        line.startsWith("GlobalSection(NestedProjects)") -> inNested = true
        line == "EndGlobalSection" -> inNested = false
        inNested -> NESTED_LINE.find(line)?.let { entries[it.groupValues[1].uppercase()]?.parent = it.groupValues[2].uppercase() }
      }
    }

    fun folder(name: String, id: String?): SolutionFolder {
      val children = entries.values.filter { it.parent?.takeIf { parent -> parent in entries } == id }
      return SolutionFolder(
        name = name,
        folders = children.filter { it.folder }.map { folder(it.name, it.id) },
        projects = children.filter { !it.folder && isProject(it.path) }.map { resolve(directory, it.path) },
        files = id?.let { entries[it]?.items }.orEmpty(),
      )
    }
    return SolutionStructure(file, folder(solutionName(file), null))
  }

  /** Reads the XML format. A folder name is a path such as `/src/tests/`. */
  fun parseSlnx(file: Path, text: String): SolutionStructure? {
    val directory = file.toAbsolutePath().parent
    val factory = DocumentBuilderFactory.newInstance().apply {
      setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }
    val document = runCatching { factory.newDocumentBuilder().parse(text.byteInputStream()) }.getOrNull() ?: return null

    class Node(val name: String) {
      val folders = LinkedHashMap<String, Node>()
      val projects = ArrayList<Path>()
      val files = ArrayList<Path>()
      fun toFolder(): SolutionFolder = SolutionFolder(name, folders.values.map { it.toFolder() }, projects, files)
    }

    val root = Node(solutionName(file))
    fun node(folderName: String): Node =
      folderName.split('/').filter { it.isNotEmpty() }.fold(root) { parent, segment -> parent.folders.getOrPut(segment) { Node(segment) } }

    fun read(element: Element, target: Node) {
      val children = element.childNodes
      for (index in 0 until children.length) {
        val child = children.item(index) as? Element ?: continue
        val path = child.getAttribute("Path")
        when (child.tagName) {
          "Folder" -> read(child, node(child.getAttribute("Name")))
          "Project" -> if (isProject(path)) target.projects.add(resolve(directory, path))
          "File" -> if (path.isNotEmpty()) target.files.add(resolve(directory, path))
        }
      }
    }
    read(document.documentElement, root)
    return SolutionStructure(file, root.toFolder())
  }

  private fun solutionName(file: Path): String = file.fileName.toString().substringBeforeLast('.')

  private fun isProject(path: String): Boolean = path.substringAfterLast('.').lowercase() in PROJECT_EXTENSIONS

  private fun resolve(directory: Path, path: String): Path = directory.resolve(path.trim().replace('\\', '/')).normalize()
}
