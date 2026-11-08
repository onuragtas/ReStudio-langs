package dev.restudio.dotnet.lsp

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorOpenRequest
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.commands.LSPCommand
import com.redhat.devtools.lsp4ij.commands.LSPCommandAction
import dev.restudio.core.entrypoints.EntryPoint
import dev.restudio.dotnet.DotnetBundle
import dev.restudio.dotnet.project.DotnetProject
import dev.restudio.dotnet.run.CSharpEntryPointDetector
import dev.restudio.dotnet.test.DotnetTestConfigurationType
import dev.restudio.dotnet.test.DotnetTestRunConfiguration
import java.net.URI

/**
 * Runs the Roslyn LS code lens "Run Test", "Debug Test", "Run All Tests" and "Debug All Tests".
 *
 * The argument is `{textDocument: {uri}, range, attachDebugger}` (recorded with the LSP probe, case roslyn-codelens).
 * The range is the name of a test method, or the name of a test class for "all tests".
 */
class DotnetTestRunCommandAction : LSPCommandAction() {
  override fun commandPerformed(command: LSPCommand, e: AnActionEvent) {
    val project = e.project ?: return
    val argument = command.getArgumentAt(0, JsonObject::class.java) ?: return
    val request = TestRunRequest.parse(argument) ?: return
    val settings = request.configuration(project) ?: return
    val executorId = if (request.debug) DEBUG_EXECUTOR_ID else DefaultRunExecutor.EXECUTOR_ID
    val executor = ExecutorRegistry.getInstance().getExecutorById(executorId) ?: return
    ProgramRunnerUtil.executeConfiguration(settings, executor)
  }

  /** The parts of a `dotnet.test.run` argument that the plugin uses. [line] is zero-based. */
  data class TestRunRequest(val file: VirtualFile, val line: Int, val debug: Boolean) {
    /**
     * Creates a temporary test configuration. The test on [line] runs alone. When [line] has no test, it is a class
     * line: all the classes of the file with a test run.
     */
    fun configuration(project: Project): com.intellij.execution.RunnerAndConfigurationSettings? {
      val dotnetProject = DotnetProject.find(file) ?: return null
      val text = VfsUtil.loadText(file)
      val tests = CSharpEntryPointDetector.detect(file.name, text).filter { it.kind == EntryPoint.Kind.TEST }
      val test = tests.firstOrNull { lineOf(text, it.offset) == line }
      val (filter, name) = if (test != null) {
        "FullyQualifiedName=${test.name}" to DotnetBundle.message("test.configuration.name", test.name.substringAfterLast('.'))
      }
      else {
        val classes = tests.map { it.name.substringBeforeLast('.') }.distinct()
        if (classes.isEmpty()) return null
        classes.joinToString("|") { "FullyQualifiedName~$it." } to DotnetBundle.message("test.configuration.name", classes.joinToString { it.substringAfterLast('.') })
      }
      val settings = RunManager.getInstance(project).createConfiguration(name, DotnetTestConfigurationType.getInstance().configurationFactories.single())
      (settings.configuration as DotnetTestRunConfiguration).setUp(dotnetProject, file.path, filter, name)
      settings.isTemporary = true
      return settings
    }

    companion object {
      fun parse(argument: JsonObject): TestRunRequest? {
        val uri = argument.getAsJsonObject("textDocument")?.get("uri")?.asString ?: return null
        val file = VfsUtil.findFile(java.nio.file.Path.of(URI(uri)), true) ?: return null
        val line = argument.getAsJsonObject("range")?.getAsJsonObject("start")?.get("line")?.asInt ?: return null
        val debug = argument.get("attachDebugger")?.takeUnless(JsonElement::isJsonNull)?.asBoolean ?: false
        return TestRunRequest(file, line, debug)
      }

      private fun lineOf(text: CharSequence, offset: Int) = (0 until offset).count { text[it] == '\n' }
    }
  }

  private companion object {
    /** The ID of the platform Debug executor. */
    const val DEBUG_EXECUTOR_ID = "Debug"
  }
}

/**
 * Runs the Roslyn LS code lens "N references".
 *
 * The arguments are `[uri, position]`. The action opens the file at the position and starts Show Usages there.
 */
class RoslynPeekReferencesCommandAction : LSPCommandAction() {
  override fun commandPerformed(command: LSPCommand, e: AnActionEvent) {
    val project = e.project ?: return
    val uri = command.getArgumentAt(0, String::class.java) ?: return
    val position = command.getArgumentAt(1, JsonObject::class.java) ?: return
    val file = VfsUtil.findFile(java.nio.file.Path.of(URI(uri)), true) ?: return
    val line = position.get("line")?.asInt ?: return
    val column = position.get("character")?.asInt ?: 0
    val request = FileEditorOpenRequest.defaults().withRequestFocus(true)
    FileEditorManager.getInstance(project).requestOpenTextEditor(OpenFileDescriptor(project, file, line, column), request).thenAccept { editor ->
      if (editor == null) return@thenAccept
      ApplicationManager.getApplication().invokeLater({ showUsages(project, editor, file, e.place) }, project.disposed)
    }
  }

  private fun showUsages(project: Project, editor: Editor, file: VirtualFile, place: String) {
    val showUsages = ActionManager.getInstance().getAction(SHOW_USAGES_ACTION_ID) ?: return
    val dataContext = SimpleDataContext.builder()
      .add(CommonDataKeys.PROJECT, project)
      .add(CommonDataKeys.EDITOR, editor)
      .add(CommonDataKeys.VIRTUAL_FILE, file)
      .build()
    ActionUtil.performAction(showUsages, AnActionEvent.createEvent(dataContext, null, place, ActionUiKind.NONE, null))
  }

  private companion object {
    const val SHOW_USAGES_ACTION_ID = "ShowUsages"
  }
}
