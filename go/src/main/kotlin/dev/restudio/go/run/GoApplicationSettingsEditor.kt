package dev.restudio.go.run

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import dev.restudio.go.GoBundle
import javax.swing.JComponent

/** Edits the package, the program arguments, the working directory and the environment of a Go application. */
class GoApplicationSettingsEditor(project: Project) : SettingsEditor<GoApplicationRunConfiguration>() {
  private val packageField = TextFieldWithBrowseButton().apply {
    addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir().withTitle(GoBundle.message("application.package.title")))
  }
  private val argumentsField = RawCommandLineEditor()
  private val workingDirectoryField = TextFieldWithBrowseButton().apply {
    addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir().withTitle(GoBundle.message("application.working.directory.title")))
  }
  private val environmentField = EnvironmentVariablesComponent(project)

  override fun resetEditorFrom(configuration: GoApplicationRunConfiguration) {
    packageField.text = configuration.packageDirectory
    argumentsField.text = configuration.programArguments
    workingDirectoryField.text = configuration.workingDirectory.orEmpty()
    environmentField.envs = configuration.programEnvironment
  }

  override fun applyEditorTo(configuration: GoApplicationRunConfiguration) {
    configuration.packageDirectory = packageField.text.trim()
    configuration.programArguments = argumentsField.text
    configuration.workingDirectory = workingDirectoryField.text.trim()
    configuration.programEnvironment = environmentField.envs
    configuration.updateLaunch()
  }

  override fun createEditor(): JComponent = panel {
    row(GoBundle.message("application.package.label")) {
      cell(packageField).align(AlignX.FILL)
    }
    row(GoBundle.message("application.arguments.label")) {
      cell(argumentsField).align(AlignX.FILL)
    }
    row(GoBundle.message("application.working.directory.label")) {
      cell(workingDirectoryField).align(AlignX.FILL)
    }
    row(GoBundle.message("application.environment.label")) {
      cell(environmentField.component).align(AlignX.FILL)
    }
  }
}
