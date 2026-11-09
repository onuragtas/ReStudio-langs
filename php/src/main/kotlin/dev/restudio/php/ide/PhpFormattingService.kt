package dev.restudio.php.ide

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import dev.restudio.core.sdk.ToolLocator
import dev.restudio.php.PhpBundle
import dev.restudio.php.lsp.PhpClientFeatures
import dev.restudio.php.sdk.PhpSdk
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Formats a PHP file with PHP CS Fixer or Laravel Pint, for Code > Reformat Code.
 *
 * It runs only when the project has `vendor/bin/php-cs-fixer` or `vendor/bin/pint`, or when `php-cs-fixer` is on the
 * PATH. Else the language server formats the file. Both tools change files in place and do not read the standard
 * input, so the service writes the text to a temporary file, runs the tool on it, and reads the result.
 */
class PhpFormattingService : AsyncDocumentFormattingService() {
  override fun getFeatures(): Set<FormattingService.Feature> = emptySet()

  override fun canFormat(file: PsiFile): Boolean = file.virtualFile?.let { PhpFormatter.find(it) != null } == true

  override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask? {
    val formatter = request.context.virtualFile?.let(PhpFormatter::find) ?: return null
    return object : FormattingTask {
      @Volatile
      private var handler: CapturingProcessHandler? = null

      @Volatile
      private var cancelled = false

      override fun run() {
        val directory = Files.createTempDirectory("restudio-php-format")
        try {
          val temporary = directory.resolve("input.php")
          Files.writeString(temporary, request.documentText, StandardCharsets.UTF_8)
          val process = CapturingProcessHandler(formatter.commandLine(temporary))
          handler = process
          if (cancelled) return
          val output = process.runProcess(TIMEOUT_MS)
          when {
            output.isCancelled || cancelled -> {}
            output.exitCode == 0 -> request.onTextReady(Files.readString(temporary, StandardCharsets.UTF_8))
            else -> request.onError(PhpBundle.message("formatter.failed", formatter.name), output.stderr.ifBlank { output.stdout }.trim())
          }
        }
        finally {
          directory.toFile().deleteRecursively()
        }
      }

      override fun cancel(): Boolean {
        cancelled = true
        handler?.destroyProcess()
        return true
      }

      override fun isRunUnderProgress(): Boolean = true
    }
  }

  override fun getNotificationGroupId(): String = PhpClientFeatures.NOTIFICATION_GROUP

  override fun getName(): String = "PHP CS Fixer / Pint"

  private companion object {
    const val TIMEOUT_MS = 30_000
  }
}

/**
 * A PHP formatter of a Composer project. [script] is the tool. [root] is the working directory, where the tool finds
 * its configuration.
 */
class PhpFormatter(val name: String, private val root: Path, private val script: Path, private val vendor: Boolean, private val configuration: Path?) {
  fun commandLine(file: Path): GeneralCommandLine {
    val tool = if (vendor) listOf(PhpSdk.php()?.toString() ?: "php", script.toString()) else listOf(script.toString())
    val arguments = when (name) {
      PINT -> listOf(file.toString())
      // PHP CS Fixer looks for its configuration next to a single file argument, so the service gives it.
      else -> listOfNotNull("fix", "--using-cache=no", "--quiet", configuration?.let { "--config=$it" }, file.toString())
    }
    return GeneralCommandLine(tool + arguments)
      .withWorkingDirectory(root)
      .withEnvironment(PhpSdk.environment())
      .withCharset(StandardCharsets.UTF_8)
  }

  companion object {
    private const val PHP_CS_FIXER = "php-cs-fixer"
    private const val PINT = "pint"
    private val CS_FIXER_CONFIGURATIONS = listOf(".php-cs-fixer.php", ".php-cs-fixer.dist.php")

    /** The formatter for [file]: the one in `vendor/bin` first, then `php-cs-fixer` on the PATH. */
    fun find(file: VirtualFile): PhpFormatter? {
      if (file.extension != "php" || "/vendor/" in file.path) return null
      val root = PhpSdk.composerRoot(file) ?: return null
      val rootPath = Path.of(root.path)
      val configuration = CS_FIXER_CONFIGURATIONS.firstNotNullOfOrNull { root.findChild(it) }?.let { Path.of(it.path) }
      PhpSdk.vendorBinary(root, PHP_CS_FIXER)?.let { return PhpFormatter(PHP_CS_FIXER, rootPath, Path.of(it.path), true, configuration) }
      PhpSdk.vendorBinary(root, PINT)?.let { return PhpFormatter(PINT, rootPath, Path.of(it.path), true, null) }
      return ToolLocator.find(PHP_CS_FIXER)?.let { PhpFormatter(PHP_CS_FIXER, rootPath, it, false, configuration) }
    }
  }
}
