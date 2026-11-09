package dev.restudio.web.ide

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import dev.restudio.web.WebBundle
import dev.restudio.web.sdk.NodeSdk
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Formats a file with the Prettier of its project, for Code > Reformat Code.
 *
 * It runs only in a project with `node_modules/prettier`, so a project without Prettier keeps the language server
 * formatting. Prettier reads the text from standard input and finds `.prettierrc` from `--stdin-filepath`.
 */
class PrettierFormattingService : AsyncDocumentFormattingService() {
  override fun getFeatures(): Set<FormattingService.Feature> = emptySet()

  override fun canFormat(file: PsiFile): Boolean = file.virtualFile?.let { Prettier.find(it) != null } == true

  override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask? {
    val file = request.context.virtualFile ?: return null
    val prettier = Prettier.find(file) ?: return null
    val handler = CapturingProcessHandler(prettier.commandLine(file.path))
    return object : FormattingTask {
      override fun run() {
        handler.processInput.use { it.write(request.documentText.toByteArray(StandardCharsets.UTF_8)) }
        val output = handler.runProcess(TIMEOUT_MS)
        when {
          output.isCancelled -> {}
          output.exitCode == 0 -> request.onTextReady(output.stdout)
          else -> request.onError(WebBundle.message("prettier.failed"), output.stderr.ifBlank { output.stdout }.trim())
        }
      }

      override fun cancel(): Boolean {
        handler.destroyProcess()
        return true
      }

      override fun isRunUnderProgress(): Boolean = true
    }
  }

  override fun getNotificationGroupId(): String = NOTIFICATION_GROUP

  override fun getName(): String = "Prettier"

  companion object {
    const val NOTIFICATION_GROUP: String = "ReStudio Web"
    private const val TIMEOUT_MS = 30_000
  }
}

/** The Prettier of a project: `<package root>/node_modules/prettier/bin/prettier.cjs`. */
class Prettier(private val root: Path, private val script: Path) {
  fun commandLine(filePath: String): GeneralCommandLine {
    val node = NodeSdk.node()?.toString() ?: "node"
    return GeneralCommandLine(node, script.toString(), "--stdin-filepath", filePath)
      .withWorkingDirectory(root)
      .withEnvironment(NodeSdk.environment(root))
      .withCharset(StandardCharsets.UTF_8)
  }

  companion object {
    /** The extensions that Prettier formats out of the box. */
    private val EXTENSIONS = setOf("js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts", "vue", "css", "scss", "less", "html", "json", "md", "yaml", "yml", "graphql")

    fun find(file: VirtualFile): Prettier? {
      if (file.extension?.lowercase() !in EXTENSIONS || "/node_modules/" in file.path) return null
      val root = NodeSdk.packageRoot(file) ?: return null
      val script = root.findFileByRelativePath("node_modules/prettier/bin/prettier.cjs") ?: return null
      return Prettier(Path.of(root.path), Path.of(script.path))
    }
  }
}
