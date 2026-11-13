package dev.restudio.dotnet.solution

import com.intellij.execution.RunManager
import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiManager
import com.intellij.ui.SimpleTextAttributes
import dev.restudio.dotnet.DotnetBundle
import dev.restudio.dotnet.run.DotnetRunConfiguration
import java.nio.file.Path

/** The sort group of a node. A lower group comes first. In a group, the name decides. */
internal interface SolutionSortable {
  val sortGroup: Int
  val sortName: String
}

internal const val GROUP_DEPENDENCIES = 0
internal const val GROUP_FOLDER = 1
internal const val GROUP_PROJECT = 2
internal const val GROUP_FILE = 3

private fun Project.virtualFile(path: Path): VirtualFile? = LocalFileSystem.getInstance().findFileByNioFile(path)

private fun Project.psiDirectory(path: Path): PsiDirectory? = virtualFile(path)?.takeIf { it.isDirectory }?.let { PsiManager.getInstance(this).findDirectory(it) }

private fun VirtualFile.contains(file: VirtualFile): Boolean = file.path == path || file.path.startsWith("$path/")

private fun Path.contains(file: VirtualFile): Boolean = Path.of(file.path).startsWith(this)

/** The invisible root of the Solution view. Its only child is the solution node. */
internal class SolutionViewRootNode(project: Project, settings: ViewSettings) : ProjectViewNode<Project>(project, project, settings) {
  override fun getChildren(): Collection<AbstractTreeNode<*>> {
    val snapshot = SolutionModel.getInstance(myProject).snapshot() ?: return emptyList()
    return listOf(SolutionNode(myProject, snapshot, settings))
  }

  override fun contains(file: VirtualFile): Boolean = SolutionModel.getInstance(myProject).snapshot()?.directory?.contains(file) == true

  override fun update(presentation: PresentationData) = Unit
}

/** The value of the solution node. */
data class SolutionRef(val directory: Path, val file: Path?)

/** The solution: its folders, its projects and its solution items. */
internal class SolutionNode(project: Project, private val snapshot: SolutionSnapshot, settings: ViewSettings)
  : ProjectViewNode<SolutionRef>(project, SolutionRef(snapshot.directory, snapshot.file), settings) {
  override fun getChildren(): Collection<AbstractTreeNode<*>> = folderChildren(myProject, snapshot, snapshot.root, emptyList(), settings)

  override fun contains(file: VirtualFile): Boolean = snapshot.directory.contains(file) || snapshot.projects.values.any { it.directory.contains(file) }

  override fun getVirtualFile(): VirtualFile? = snapshot.file?.let { myProject.virtualFile(it) }

  override fun update(presentation: PresentationData) {
    presentation.setIcon(AllIcons.Nodes.ModuleGroup)
    presentation.addText(DotnetBundle.message("solution.node", snapshot.root.name), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
    presentation.addText("  " + DotnetBundle.message("solution.node.projects", snapshot.projects.size), SimpleTextAttributes.GRAYED_ATTRIBUTES)
  }

  override fun isAlwaysExpand(): Boolean = true
}

/** The value of a solution folder node: the folder names from the solution root. */
data class SolutionFolderRef(val solution: Path, val names: List<String>)

internal class SolutionFolderNode(
  project: Project,
  private val snapshot: SolutionSnapshot,
  private val folder: SolutionFolder,
  private val names: List<String>,
  settings: ViewSettings,
) : ProjectViewNode<SolutionFolderRef>(project, SolutionFolderRef(snapshot.directory, names), settings), SolutionSortable {
  override val sortGroup: Int get() = GROUP_FOLDER
  override val sortName: String get() = folder.name

  override fun getChildren(): Collection<AbstractTreeNode<*>> = folderChildren(myProject, snapshot, folder, names, settings)

  override fun contains(file: VirtualFile): Boolean =
    folder.allProjects.any { it.parent.contains(file) } || folder.files.any { Path.of(file.path) == it }

  override fun update(presentation: PresentationData) {
    presentation.setIcon(AllIcons.Nodes.Folder)
    presentation.presentableText = folder.name
  }
}

private fun folderChildren(project: Project, snapshot: SolutionSnapshot, folder: SolutionFolder, names: List<String>, settings: ViewSettings): List<AbstractTreeNode<*>> {
  val result = ArrayList<AbstractTreeNode<*>>()
  folder.folders.mapTo(result) { SolutionFolderNode(project, snapshot, it, names + it.name, settings) }
  for (file in folder.projects) {
    val solutionProject = snapshot.projects[file] ?: continue
    val directory = project.psiDirectory(solutionProject.directory) ?: continue
    result += DotnetProjectNode(project, directory, solutionProject, settings)
  }
  for (file in folder.files) {
    val psiFile = project.virtualFile(file)?.let { PsiManager.getInstance(project).findFile(it) } ?: continue
    result += SolutionFileNode(project, psiFile, FileEntry(file, file, true), settings)
  }
  return result
}

/** A .NET project. The value is the project directory, so the file actions of the IDE (New, Paste, Find in Files) work on it. */
internal class DotnetProjectNode(project: Project, directory: PsiDirectory, val solutionProject: SolutionProject, settings: ViewSettings)
  : ProjectViewNode<PsiDirectory>(project, directory, settings), SolutionSortable {
  override val sortGroup: Int get() = GROUP_PROJECT
  override val sortName: String get() = solutionProject.name

  override fun getChildren(): Collection<AbstractTreeNode<*>> =
    listOf(DependenciesNode(myProject, solutionProject, settings)) + directoryChildren(myProject, solutionProject, solutionProject.directory, settings)

  override fun contains(file: VirtualFile): Boolean = solutionProject.directory.contains(file)

  override fun getVirtualFile(): VirtualFile? = value?.virtualFile

  override fun update(presentation: PresentationData) {
    val startup = isStartupProject(myProject, solutionProject.file)
    presentation.setIcon(if (solutionProject.project.isTestProject) AllIcons.Nodes.TestSourceFolder else AllIcons.Nodes.Module)
    presentation.addText(solutionProject.name, if (startup) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
    val frameworks = solutionProject.project.targetFrameworks
    if (frameworks.isNotEmpty()) presentation.addText("  " + frameworks.joinToString(", "), SimpleTextAttributes.GRAYED_ATTRIBUTES)
    if (!solutionProject.content.isEvaluated) presentation.addText("  " + DotnetBundle.message("solution.node.loading"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
  }

  override fun canRepresent(element: Any?): Boolean = super.canRepresent(element) || element == virtualFile
}

/** `true` when the selected run configuration runs [file]. */
internal fun isStartupProject(project: Project, file: Path): Boolean =
  (RunManager.getInstance(project).selectedConfiguration?.configuration as? DotnetRunConfiguration)?.projectFile == file.toString()

private fun directoryChildren(project: Project, solutionProject: SolutionProject, displayDirectory: Path, settings: ViewSettings): List<AbstractTreeNode<*>> {
  val listing = project.virtualFile(displayDirectory)?.takeIf { it.isDirectory }?.children.orEmpty().map { it.name to it.isDirectory }
  val showAll = SolutionViewSettings.isShowAllFiles(project)
  return solutionProject.content.children(displayDirectory, listing, showAll).mapNotNull { entryNode(project, solutionProject, it, settings) }
}

private fun entryNode(project: Project, solutionProject: SolutionProject, entry: ViewEntry, settings: ViewSettings): AbstractTreeNode<*>? = when (entry) {
  is DirEntry -> {
    val directory = entry.realPath?.let { project.psiDirectory(it) }
    if (directory != null) ProjectDirectoryNode(project, directory, solutionProject, entry, settings)
    else VirtualFolderNode(project, solutionProject, entry, settings)
  }
  is FileEntry -> project.virtualFile(entry.realPath)?.let { PsiManager.getInstance(project).findFile(it) }?.let { SolutionFileNode(project, it, entry, settings) }
}

/** A directory of a project. */
internal class ProjectDirectoryNode(project: Project, directory: PsiDirectory, private val solutionProject: SolutionProject, private val entry: DirEntry, settings: ViewSettings)
  : ProjectViewNode<PsiDirectory>(project, directory, settings), SolutionSortable {
  override val sortGroup: Int get() = GROUP_FOLDER
  override val sortName: String get() = entry.name

  override fun getChildren(): Collection<AbstractTreeNode<*>> = directoryChildren(myProject, solutionProject, entry.displayPath, settings)

  override fun contains(file: VirtualFile): Boolean = entry.displayPath.contains(file)

  override fun getVirtualFile(): VirtualFile? = value?.virtualFile

  override fun update(presentation: PresentationData) {
    presentation.setIcon(if (entry.name == "Properties") AllIcons.Nodes.ConfigFolder else AllIcons.Nodes.Folder)
    presentation.addText(entry.name, if (entry.inProject) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
  }

  override fun canRepresent(element: Any?): Boolean = super.canRepresent(element) || element == virtualFile
}

/** The value of a folder that only `Link` items make. */
data class VirtualFolderRef(val project: Path, val path: Path)

internal class VirtualFolderNode(project: Project, private val solutionProject: SolutionProject, private val entry: DirEntry, settings: ViewSettings)
  : ProjectViewNode<VirtualFolderRef>(project, VirtualFolderRef(solutionProject.file, entry.displayPath), settings), SolutionSortable {
  override val sortGroup: Int get() = GROUP_FOLDER
  override val sortName: String get() = entry.name

  override fun getChildren(): Collection<AbstractTreeNode<*>> = directoryChildren(myProject, solutionProject, entry.displayPath, settings)

  override fun contains(file: VirtualFile): Boolean = false

  override fun update(presentation: PresentationData) {
    presentation.setIcon(AllIcons.Nodes.Folder)
    presentation.addText(entry.name, SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES)
  }
}

/** A file. Its children are the dependent files, for example `Index.razor.cs` below `Index.razor`. */
internal class SolutionFileNode(project: Project, file: com.intellij.psi.PsiFile, val entry: FileEntry, settings: ViewSettings)
  : PsiFileNode(project, file, settings), SolutionSortable {
  override val sortGroup: Int get() = GROUP_FILE
  override val sortName: String get() = entry.name

  override fun getChildrenImpl(): Collection<AbstractTreeNode<*>> {
    val project = project ?: return emptyList()
    return entry.nested.mapNotNull { nested ->
      project.virtualFile(nested.realPath)?.let { PsiManager.getInstance(project).findFile(it) }?.let { SolutionFileNode(project, it, nested, settings) }
    }
  }

  override fun isAlwaysLeaf(): Boolean = entry.nested.isEmpty()

  override fun updateImpl(data: PresentationData) {
    super.updateImpl(data)
    val name = if (entry.isLink) entry.name else value?.name ?: entry.name
    data.presentableText = name
    when {
      !entry.inProject -> data.addText(name, SimpleTextAttributes.GRAYED_ATTRIBUTES)
      entry.isLink -> {
        data.addText(name, SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES)
        data.locationString = DotnetBundle.message("solution.node.link")
      }
    }
  }
}

/** The value of a node below Dependencies. [kind] is `null` for the Dependencies node itself. */
data class DependencyRef(val project: Path, val kind: DependencyKind?, val id: String? = null)

enum class DependencyKind { FRAMEWORKS, PACKAGES, PROJECTS }

internal class DependenciesNode(project: Project, private val solutionProject: SolutionProject, settings: ViewSettings)
  : ProjectViewNode<DependencyRef>(project, DependencyRef(solutionProject.file, null), settings), SolutionSortable {
  override val sortGroup: Int get() = GROUP_DEPENDENCIES
  override val sortName: String get() = ""

  override fun getChildren(): Collection<AbstractTreeNode<*>> {
    val msbuild = solutionProject.msbuild ?: return emptyList()
    val groups = listOf(
      DependencyKind.FRAMEWORKS to msbuild.frameworkReferences.distinct().map { it to null },
      DependencyKind.PACKAGES to msbuild.packageReferences.map { it.id to it.version },
      DependencyKind.PROJECTS to msbuild.projectReferences.map { it.fileName.toString().substringBeforeLast('.') to null },
    )
    return groups.filter { it.second.isNotEmpty() }.map { (kind, entries) -> DependencyGroupNode(myProject, solutionProject.file, kind, entries, settings) }
  }

  override fun contains(file: VirtualFile): Boolean = false

  override fun update(presentation: PresentationData) {
    presentation.setIcon(AllIcons.Nodes.PpLibFolder)
    presentation.presentableText = DotnetBundle.message("solution.node.dependencies")
  }
}

internal class DependencyGroupNode(
  project: Project,
  private val file: Path,
  private val kind: DependencyKind,
  private val entries: List<Pair<String, String?>>,
  settings: ViewSettings,
) : ProjectViewNode<DependencyRef>(project, DependencyRef(file, kind), settings), SolutionSortable {
  override val sortGroup: Int get() = GROUP_DEPENDENCIES + kind.ordinal
  override val sortName: String get() = kind.name

  override fun getChildren(): Collection<AbstractTreeNode<*>> = entries.map { (id, version) -> DependencyNode(myProject, file, kind, id, version, settings) }

  override fun contains(file: VirtualFile): Boolean = false

  override fun update(presentation: PresentationData) {
    presentation.setIcon(AllIcons.Nodes.PpLibFolder)
    presentation.presentableText = DotnetBundle.message("solution.node.dependencies.${kind.name.lowercase()}")
  }
}

internal class DependencyNode(project: Project, file: Path, private val kind: DependencyKind, id: String, private val version: String?, settings: ViewSettings)
  : ProjectViewNode<DependencyRef>(project, DependencyRef(file, kind, id), settings), SolutionSortable {
  override val sortGroup: Int get() = GROUP_FILE
  override val sortName: String = id

  override fun getChildren(): Collection<AbstractTreeNode<*>> = emptyList()

  override fun isAlwaysLeaf(): Boolean = true

  override fun contains(file: VirtualFile): Boolean = false

  override fun update(presentation: PresentationData) {
    presentation.setIcon(
      when (kind) {
        DependencyKind.FRAMEWORKS -> AllIcons.Nodes.PpJdk
        DependencyKind.PACKAGES -> AllIcons.Nodes.PpLib
        DependencyKind.PROJECTS -> AllIcons.Nodes.Module
      }
    )
    presentation.presentableText = value.id
    version?.let { presentation.locationString = it }
  }
}
