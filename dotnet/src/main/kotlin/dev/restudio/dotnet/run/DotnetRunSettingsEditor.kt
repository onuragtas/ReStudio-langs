package dev.restudio.dotnet.run

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import dev.restudio.dotnet.DotnetBundle
import java.nio.file.Path
import javax.swing.JComponent

/** The ".NET" tab of [DotnetRunConfiguration]: the project file and the launch profile. */
class DotnetRunSettingsEditor(project: Project) : SettingsEditor<DotnetRunConfiguration>() {
  private val projectFile = TextFieldWithBrowseButton().apply {
    addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withExtensionFilter("csproj"))
  }
  private val profileModel = CollectionComboBoxModel<String>()
  private val profile = ComboBox(profileModel).apply { isEditable = true }

  override fun resetEditorFrom(configuration: DotnetRunConfiguration) {
    projectFile.text = configuration.projectFile
    val selected = configuration.launchProfile
    profileModel.replaceAll(listOf(selected))
    profile.selectedItem = selected
    val file = configuration.projectFile
    if (file.isEmpty()) return
    // The editor runs on the EDT. Read the profiles in the background.
    ApplicationManager.getApplication().executeOnPooledThread {
      val names = runCatching { LaunchSettings.read(Path.of(file).parent).map { it.name } }.getOrDefault(emptyList())
      ApplicationManager.getApplication().invokeLater({
        profileModel.replaceAll((listOf("") + names).distinct())
        profile.selectedItem = selected
      }, ModalityState.any())
    }
  }

  override fun applyEditorTo(configuration: DotnetRunConfiguration) {
    configuration.projectFile = projectFile.text.trim()
    configuration.launchProfile = (profile.editor.item as? String ?: profile.selectedItem as? String).orEmpty().trim()
  }

  override fun createEditor(): JComponent = panel {
    row(DotnetBundle.message("run.editor.project.file")) { cell(projectFile).align(AlignX.FILL) }
    row(DotnetBundle.message("run.editor.launch.profile")) {
      cell(profile).align(AlignX.FILL).comment(DotnetBundle.message("run.editor.launch.profile.comment"))
    }
  }
}
