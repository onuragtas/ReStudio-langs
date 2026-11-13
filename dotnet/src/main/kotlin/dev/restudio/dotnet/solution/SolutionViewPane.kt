package dev.restudio.dotnet.solution

import com.intellij.icons.AllIcons
import com.intellij.ide.SelectInTarget
import com.intellij.ide.StandardTargetWeights
import com.intellij.ide.impl.ProjectViewSelectInTarget
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.AbstractProjectViewPaneWithAsyncSupport
import com.intellij.ide.projectView.impl.ProjectAbstractTreeStructureBase
import com.intellij.ide.projectView.impl.ProjectTreeStructure
import com.intellij.ide.projectView.impl.ProjectViewTree
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileSystemItem
import com.intellij.util.ui.tree.TreeUtil
import dev.restudio.dotnet.DotnetBundle
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.tree.DefaultTreeModel
import kotlin.io.path.extension
import kotlin.io.path.isDirectory

/** What a Solution view action works on: the solution or one project. */
sealed interface SolutionTarget {
  val file: Path

  data class Solution(override val file: Path) : SolutionTarget

  data class Project(override val file: Path) : SolutionTarget
}

/** The Show All Files option of the Solution view. The IDE keeps it for each project. */
object SolutionViewSettings {
  private const val SHOW_ALL_FILES = "restudio.dotnet.solution.showAllFiles"

  fun isShowAllFiles(project: Project): Boolean = PropertiesComponent.getInstance(project).getBoolean(SHOW_ALL_FILES, false)

  fun setShowAllFiles(project: Project, value: Boolean) = PropertiesComponent.getInstance(project).setValue(SHOW_ALL_FILES, value, false)
}

/**
 * The "Solution" pane of the Project tool window.
 *
 * It shows the solution as Rider and Visual Studio do: solution folders, projects, the dependencies of each project,
 * and the files that belong to the project. The data comes from [SolutionModel].
 */
class SolutionViewPane(project: Project) : AbstractProjectViewPaneWithAsyncSupport(project) {
  override fun getTitle(): String = DotnetBundle.message("solution.pane.title")

  override fun getIcon(): Icon = AllIcons.Nodes.ModuleGroup

  override fun getId(): String = ID

  override fun getWeight(): Int = WEIGHT

  override fun isDefaultPane(project: Project): Boolean = hasSolution(project)

  override fun isInitiallyVisible(): Boolean = hasSolution(myProject) || hasShallowProject(myProject)

  override fun createSelectInTarget(): SelectInTarget = SolutionSelectInTarget(myProject)

  override fun createStructure(): ProjectAbstractTreeStructureBase = object : ProjectTreeStructure(myProject, ID) {
    override fun createRoot(project: Project, settings: ViewSettings): AbstractTreeNode<*> = SolutionViewRootNode(project, settings)

    // The Solution view makes its own tree. The tree structure providers of other plugins expect the Project view.
    override fun getProviders() = emptyList<com.intellij.ide.projectView.TreeStructureProvider>()

    override fun isToBuildChildrenInBackground(element: Any): Boolean = true
  }

  override fun createTree(treeModel: DefaultTreeModel): ProjectViewTree = ProjectViewTree(treeModel)

  override fun createComparator(): Comparator<NodeDescriptor<*>> = COMPARATOR

  override fun addToolbarActions(actionGroup: DefaultActionGroup) {
    actionGroup.addAction(ShowAllFilesAction()).setAsSecondary(false)
  }

  override fun uiDataSnapshot(sink: DataSink) {
    super.uiDataSnapshot(sink)
    val targets = selectionPaths.orEmpty().mapNotNull { path ->
      when (val node = TreeUtil.getLastUserObject(path)) {
        is SolutionNode -> node.value.file?.let { SolutionTarget.Solution(it) }
        is DotnetProjectNode -> SolutionTarget.Project(node.solutionProject.file)
        else -> null
      }
    }
    sink[TARGETS] = targets
  }

  private inner class ShowAllFilesAction : ToggleAction(
    DotnetBundle.message("solution.show.all.files"),
    DotnetBundle.message("solution.show.all.files.description"),
    AllIcons.Actions.ToggleVisibility,
  ), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = SolutionViewSettings.isShowAllFiles(myProject)

    override fun setSelected(e: AnActionEvent, state: Boolean) {
      SolutionViewSettings.setShowAllFiles(myProject, state)
      updateFromRoot(true)
    }
  }

  private class SolutionSelectInTarget(project: Project) : ProjectViewSelectInTarget(project) {
    override fun toString(): String = DotnetBundle.message("solution.pane.title")

    override fun canSelect(file: PsiFileSystemItem): Boolean {
      val path = file.virtualFile?.path?.let { Path.of(it) } ?: return false
      val snapshot = SolutionModel.getInstance(myProject).snapshot() ?: return false
      return snapshot.projectOf(path) != null || snapshot.root.files.contains(path)
    }

    override fun getMinorViewId(): String = ID

    override fun getWeight(): Float = StandardTargetWeights.PROJECT_WEIGHT - 1
  }

  companion object {
    const val ID: String = "RestudioSolutionPane"
    private const val WEIGHT = -1

    /** The views and actions get the selected solution and projects with this key. */
    val TARGETS: DataKey<List<SolutionTarget>> = DataKey.create("restudio.dotnet.solution.targets")

    internal val COMPARATOR = Comparator<NodeDescriptor<*>> { first, second ->
      val group = group(first).compareTo(group(second))
      if (group != 0) group else name(first).compareTo(name(second), ignoreCase = true)
    }

    private fun name(descriptor: NodeDescriptor<*>): String = (descriptor as? SolutionSortable)?.sortName ?: descriptor.toString()

    private fun group(descriptor: NodeDescriptor<*>): Int = (descriptor as? SolutionSortable)?.sortGroup ?: GROUP_FILE

    /** `true` when the base directory of [project] holds a `.sln` or `.slnx` file. It reads one directory only. */
    fun hasSolution(project: Project): Boolean {
      val base = project.basePath?.let { Path.of(it) }?.takeIf { it.isDirectory() } ?: return false
      return runCatching { Files.list(base).use { files -> files.anyMatch { it.extension == "sln" || it.extension == "slnx" } } }.getOrDefault(false)
    }

    private fun hasShallowProject(project: Project): Boolean {
      val base = project.basePath?.let { Path.of(it) }?.takeIf { it.isDirectory() } ?: return false
      return runCatching { Files.walk(base, 2).use { files -> files.anyMatch { it.extension == "csproj" } } }.getOrDefault(false)
    }
  }
}
