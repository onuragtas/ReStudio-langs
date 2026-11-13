package dev.restudio.dotnet.nuget

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.util.Key
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.panel
import dev.restudio.dotnet.DotnetBundle
import dev.restudio.dotnet.lsp.RoslynWorkspace
import dev.restudio.dotnet.project.DotnetMsbuild
import dev.restudio.dotnet.project.DotnetSolution
import dev.restudio.dotnet.project.PackageReference
import dev.restudio.dotnet.run.DotnetBuildBeforeRunTaskProvider
import dev.restudio.dotnet.sdk.DotnetCli
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel

/** Adds the NuGet tool window to a project that has a solution or a `.csproj` file. */
class NuGetToolWindowFactory : ToolWindowFactory, DumbAware {
  override suspend fun isApplicableAsync(project: Project): Boolean {
    val basePath = project.basePath ?: return false
    return RoslynWorkspace.find(Path.of(basePath)) != null
  }

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = NuGetPanel(project)
    val content = ContentFactory.getInstance().createContent(panel.component, null, false)
    content.putUserData(PANEL, panel)
    toolWindow.contentManager.addContent(content)
    panel.reloadProjects()
  }

  companion object {
    private const val ID = "NuGet"
    private val PANEL = Key.create<NuGetPanel>("restudio.nuget.panel")

    /** Opens the NuGet tool window with [file] as the selected project, and puts the focus in the search field. */
    fun show(project: Project, file: Path) {
      val window = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
      window.activate {
        window.contentManager.contents.firstNotNullOfOrNull { it.getUserData(PANEL) }?.select(file)
      }
    }
  }
}

/**
 * Shows the packages of the selected project, and searches nuget.org.
 *
 * Add, update and remove run `dotnet add package` and `dotnet remove package`. Update without a version takes the
 * newest stable version. All the network and process work runs in the background.
 */
private class NuGetPanel(private val project: Project) {
  private val projectModel = CollectionComboBoxModel<Path>()
  private val projectBox = ComboBox(projectModel).apply {
    renderer = textListCellRenderer { it.fileName.toString() }
    addActionListener { reloadInstalled() }
  }
  private val installedModel = CollectionListModel<PackageReference>()
  private val installedList = JBList(installedModel).apply {
    selectionMode = ListSelectionModel.SINGLE_SELECTION
    cellRenderer = object : ColoredListCellRenderer<PackageReference>() {
      override fun customizeCellRenderer(list: JList<out PackageReference>, value: PackageReference?, index: Int, selected: Boolean, hasFocus: Boolean) {
        value ?: return
        append(value.id)
        value.version?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
      }
    }
  }
  private val searchField = SearchTextField(false).apply { addKeyboardListener(EnterListener { search() }) }
  private val prerelease = JBCheckBox(DotnetBundle.message("nuget.prerelease"))
  private val resultModel = CollectionListModel<NuGetPackage>()
  private val resultList = JBList(resultModel).apply {
    selectionMode = ListSelectionModel.SINGLE_SELECTION
    cellRenderer = object : ColoredListCellRenderer<NuGetPackage>() {
      override fun customizeCellRenderer(list: JList<out NuGetPackage>, value: NuGetPackage?, index: Int, selected: Boolean, hasFocus: Boolean) {
        value ?: return
        append(value.id, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        append("  ${value.version}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        if (value.description.isNotEmpty()) append("  ${value.description.lineSequence().first().take(DESCRIPTION_LENGTH)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        toolTipText = value.description
      }
    }
    addListSelectionListener { if (!it.valueIsAdjusting) showVersions() }
  }
  private val versionModel = CollectionComboBoxModel<String>()
  private val versionBox = ComboBox(versionModel)
  private val searchGeneration = AtomicInteger()
  private var requestedProject: Path? = null

  val component: JComponent = JBSplitter(false, SPLIT).apply {
    firstComponent = panel {
      row(DotnetBundle.message("nuget.project")) { cell(projectBox).align(AlignX.FILL) }
      row { label(DotnetBundle.message("nuget.installed")) }
      row { cell(JBScrollPane(installedList)).align(AlignX.FILL).align(AlignY.FILL) }.resizableRow()
      row {
        button(DotnetBundle.message("nuget.update")) { selectedInstalled()?.let { update(it) } }
        button(DotnetBundle.message("nuget.remove")) { selectedInstalled()?.let { remove(it) } }
        button(DotnetBundle.message("nuget.reload")) { reloadProjects() }
      }
    }
    secondComponent = panel {
      row {
        cell(searchField).align(AlignX.FILL).resizableColumn()
        cell(prerelease)
        cell(JButton(DotnetBundle.message("nuget.search")).apply { addActionListener { search() } })
      }
      row { cell(JBScrollPane(resultList)).align(AlignX.FILL).align(AlignY.FILL) }.resizableRow()
      row(DotnetBundle.message("nuget.version")) {
        cell(versionBox)
        button(DotnetBundle.message("nuget.add")) { add() }
      }
    }
  }

  fun reloadProjects() {
    val basePath = project.basePath ?: return
    background {
      val files = RoslynWorkspace.find(Path.of(basePath))?.let(DotnetSolution::projects).orEmpty()
      ui {
        val selected = projectModel.selected
        projectModel.replaceAll(files)
        val requested = requestedProject?.takeIf { it in files }
        requestedProject = null
        projectModel.selectedItem = requested ?: selected?.takeIf { it in files } ?: files.firstOrNull()
        reloadInstalled()
      }
    }
  }

  /** Selects the project [file]. When the list does not have it yet, the next reload selects it. */
  fun select(file: Path) {
    if (projectModel.items.contains(file)) {
      projectModel.selectedItem = file
      reloadInstalled()
    }
    else {
      requestedProject = file
      reloadProjects()
    }
    searchField.requestFocusInWindow()
  }

  private fun reloadInstalled() {
    val file = projectModel.selected ?: return installedModel.removeAll()
    background {
      val packages = DotnetMsbuild.evaluate(file)?.packageReferences.orEmpty().sortedBy { it.id.lowercase() }
      ui { if (projectModel.selected == file) installedModel.replaceAll(packages) }
    }
  }

  private fun search() {
    val query = searchField.text.trim()
    if (query.isEmpty()) return
    val generation = searchGeneration.incrementAndGet()
    val withPrerelease = prerelease.isSelected
    resultList.setPaintBusy(true)
    background {
      val result = runCatching { NuGetClient.search(query, withPrerelease) }
      ui {
        if (generation != searchGeneration.get()) return@ui
        resultList.setPaintBusy(false)
        result.onSuccess { resultModel.replaceAll(it) }
          .onFailure { notify(DotnetBundle.message("nuget.search.failed", it.message.orEmpty()), NotificationType.ERROR) }
      }
    }
  }

  private fun showVersions() {
    val selected = resultList.selectedValue ?: return versionModel.removeAll()
    versionModel.replaceAll(selected.versions.asReversed().ifEmpty { listOf(selected.version) })
    versionModel.selectedItem = selected.version
  }

  private fun selectedInstalled(): PackageReference? = installedList.selectedValue

  private fun add() {
    val selected = resultList.selectedValue ?: return
    val version = versionModel.selected ?: selected.version
    runDotnet(DotnetBundle.message("nuget.add.progress", selected.id), listOf("add", "{project}", "package", selected.id, "--version", version))
  }

  private fun update(reference: PackageReference) {
    runDotnet(DotnetBundle.message("nuget.update.progress", reference.id), listOf("add", "{project}", "package", reference.id))
  }

  private fun remove(reference: PackageReference) {
    runDotnet(DotnetBundle.message("nuget.remove.progress", reference.id), listOf("remove", "{project}", "package", reference.id))
  }

  /** Runs `dotnet` with [arguments] for the selected project. `{project}` stands for the project file. */
  private fun runDotnet(title: String, arguments: List<String>) {
    val file = projectModel.selected ?: return
    object : Task.Backgroundable(project, title, false) {
      override fun run(indicator: ProgressIndicator) {
        val output = DotnetCli.run(file.parent, arguments.map { if (it == "{project}") file.toString() else it }, TIMEOUT_MS)
        if (output == null) return notify(DotnetBundle.message("dotnet.not.found"), NotificationType.ERROR)
        if (output.isTimeout || output.exitCode != 0) notify(DotnetBundle.message("nuget.command.failed", DotnetCli.errorSummary(output)), NotificationType.ERROR)
        DotnetMsbuild.invalidate(file)
        LocalFileSystem.getInstance().refreshNioFiles(listOf(file), true, false, null)
        ui { reloadInstalled() }
      }
    }.queue()
  }

  private fun notify(text: String, type: NotificationType) {
    NotificationGroupManager.getInstance().getNotificationGroup(DotnetBuildBeforeRunTaskProvider.NOTIFICATION_GROUP)
      .createNotification(text.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>"), type)
      .notify(project)
  }

  private fun background(action: () -> Unit) {
    ApplicationManager.getApplication().executeOnPooledThread(action)
  }

  private fun ui(action: () -> Unit) {
    ApplicationManager.getApplication().invokeLater(action, ModalityState.any(), project.disposed)
  }

  private class EnterListener(private val action: () -> Unit) : java.awt.event.KeyAdapter() {
    override fun keyPressed(e: java.awt.event.KeyEvent) {
      if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER) action()
    }
  }

  private companion object {
    const val SPLIT = 0.4f
    const val DESCRIPTION_LENGTH = 80
    const val TIMEOUT_MS = 5 * 60 * 1000
  }
}
