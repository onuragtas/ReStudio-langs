package dev.restudio.core.sdk.install

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.NioFiles
import com.intellij.util.io.Decompressor
import com.intellij.util.io.HttpRequests
import dev.restudio.core.CoreBundle
import dev.restudio.core.sdk.Tool
import dev.restudio.core.sdk.ToolLocator
import dev.restudio.core.sdk.ToolSettings
import org.jetbrains.annotations.Nls
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

/**
 * Installs the pinned version of a [Tool] from its [InstallRecipe].
 *
 * The install runs in a background task with a progress indicator. It never updates a tool by itself.
 * A new pinned version installs only when the user clicks "Install", in a notification or on the settings page.
 */
object ToolInstaller {
  /** The marker file in a version directory while an install runs. [ToolLocator] skips such a directory. */
  private const val INSTALLING_MARKER = ".restudio-installing"
  private const val NOTIFICATION_GROUP = "ReStudio"

  private val running = ConcurrentHashMap.newKeySet<String>()
  private val offered = ConcurrentHashMap.newKeySet<String>()

  /** Returns the version directory of [recipe]: `~/.restudio-dev/tools/<tool id>/<version>`. */
  fun versionDirectory(tool: Tool, recipe: InstallRecipe): Path = ToolLocator.TOOLS_DIRECTORY.resolve(tool.id).resolve(recipe.version)

  /** Returns the executable of the pinned version, or `null` when that version is not installed. */
  fun installedPath(tool: Tool, recipe: InstallRecipe): Path? {
    val directory = versionDirectory(tool, recipe)
    if (isInstalling(directory)) return null
    return directory.resolve(recipe.executable).takeIf { Files.isRegularFile(it) }
  }

  /** `true` when an install into [versionDirectory] did not finish. */
  fun isInstalling(versionDirectory: Path): Boolean = Files.exists(versionDirectory.resolve(INSTALLING_MARKER))

  /**
   * Returns the path of [tool], as [ToolLocator.find] does.
   *
   * When the tool has a recipe and the pinned version is missing, it also shows a notification with an "Install" action.
   * It shows the notification one time for each tool in an IDE session. [onInstalled] runs after a successful install.
   * The user override and a tool on the PATH stop the notification. An older managed version gives an update notification.
   */
  fun findOrOffer(project: Project?, tool: Tool, onInstalled: (Path) -> Unit = {}): Path? {
    val found = ToolLocator.find(tool)
    val recipe = tool.recipe ?: return found
    if (ToolSettings.getInstanceOrNull()?.override(tool.id) != null && found != null) return found
    if (found != null && installedPath(tool, recipe) == found) return found
    val olderManaged = found != null && found.startsWith(ToolLocator.TOOLS_DIRECTORY.resolve(tool.id))
    if (found != null && !olderManaged) return found
    if (offered.add(tool.id)) {
      val content = if (olderManaged) CoreBundle.message("tool.update.available", tool.displayName, recipe.version)
      else CoreBundle.message("tool.missing", tool.displayName)
      notify(project, content, NotificationType.INFORMATION, CoreBundle.message("tool.install.action", recipe.version)) {
        install(project, tool, onInstalled)
      }
    }
    return found
  }

  /**
   * Installs the pinned version of [tool] in a background task. It does nothing when the same tool installs already.
   *
   * [onInstalled] gets the path of the executable. It runs on the EDT.
   */
  fun install(project: Project?, tool: Tool, onInstalled: (Path) -> Unit = {}) {
    val recipe = tool.recipe ?: return
    if (!running.add(tool.id)) return
    object : Task.Backgroundable(project, CoreBundle.message("tool.install.progress", tool.displayName, recipe.version), true) {
      private var result: Path? = null

      override fun run(indicator: ProgressIndicator) {
        result = installNow(tool, recipe, indicator)
      }

      override fun onSuccess() {
        val path = result ?: return
        notify(project, CoreBundle.message("tool.install.done", tool.displayName, recipe.version), NotificationType.INFORMATION)
        onInstalled(path)
      }

      override fun onThrowable(error: Throwable) {
        LOG.warn("Cannot install ${tool.id} ${recipe.version}", error)
        val reason = (error as? ToolInstallException)?.message ?: error.toString()
        notify(project, CoreBundle.message("tool.install.failed", tool.displayName, recipe.version, reason), NotificationType.ERROR)
      }

      override fun onFinished() {
        running.remove(tool.id)
      }
    }.queue()
  }

  /** Installs [recipe] in the current thread and returns the executable. Call it only in a background thread. */
  fun installNow(tool: Tool, recipe: InstallRecipe, indicator: ProgressIndicator): Path {
    installedPath(tool, recipe)?.let { return it }
    val directory = versionDirectory(tool, recipe)
    if (Files.exists(directory)) NioFiles.deleteRecursively(directory)
    Files.createDirectories(directory)
    val marker = Files.createFile(directory.resolve(INSTALLING_MARKER))
    try {
      when (recipe) {
        is InstallRecipe.Npm -> run(
          indicator, directory, required(recipe), "install", "--prefix", directory.toString(), "--no-save", "--no-audit", "--no-fund",
          "${recipe.packageName}@${recipe.version}", *recipe.extraPackages.toTypedArray(),
        )
        is InstallRecipe.GoInstall -> run(
          indicator, directory, required(recipe), "install", "${recipe.module}@${recipe.version}",
          environment = mapOf("GOBIN" to directory.toString()),
        )
        is InstallRecipe.DotnetTool -> run(
          indicator, directory, required(recipe), "tool", "install", recipe.packageId, "--version", recipe.version,
          "--tool-path", directory.toString(), environment = mapOf("DOTNET_CLI_UI_LANGUAGE" to "en"),
        )
        is InstallRecipe.Download -> download(recipe, directory, indicator)
      }
      val executable = directory.resolve(recipe.executable)
      if (!Files.isRegularFile(executable)) throw ToolInstallException(CoreBundle.message("tool.install.no.executable", recipe.executable))
      if (!Files.isExecutable(executable)) NioFiles.setExecutable(executable)
      Files.delete(marker)
      return executable
    }
    catch (e: Throwable) {
      runCatching { NioFiles.deleteRecursively(directory) }
      throw e
    }
  }

  private fun required(recipe: InstallRecipe): Path {
    val id = recipe.requires ?: error("The recipe needs no tool")
    return ToolLocator.find(id) ?: throw ToolInstallException(CoreBundle.message("tool.install.requires", id))
  }

  private fun run(
    indicator: ProgressIndicator,
    directory: Path,
    executable: Path,
    vararg arguments: String,
    environment: Map<String, String> = emptyMap(),
  ) {
    val commandLine = GeneralCommandLine(listOf(executable.toString()) + arguments)
      .withWorkingDirectory(directory)
      .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
      .withEnvironment(environment + ("PATH" to ToolLocator.childPath()))
    indicator.text2 = commandLine.commandLineString
    val output = CapturingProcessHandler(commandLine).runProcessWithProgressIndicator(indicator)
    if (output.isCancelled) throw ProcessCanceledException()
    if (output.exitCode != 0) {
      val text = (output.stderr.ifBlank { output.stdout }).trim().takeLast(1000)
      throw ToolInstallException(CoreBundle.message("tool.install.exit.code", executable.fileName.toString(), output.exitCode, text))
    }
  }

  private fun download(recipe: InstallRecipe.Download, directory: Path, indicator: ProgressIndicator) {
    val file = directory.resolve(recipe.fileName)
    indicator.text2 = recipe.url
    HttpRequests.request(recipe.url).productNameAsUserAgent().saveToFile(file, indicator)
    val actual = sha256(file)
    if (!actual.equals(recipe.sha256.trim(), ignoreCase = true)) {
      throw ToolInstallException(CoreBundle.message("tool.install.checksum", recipe.fileName, recipe.sha256, actual))
    }
    when (recipe.archive) {
      InstallRecipe.Archive.NONE -> return
      InstallRecipe.Archive.ZIP -> Decompressor.Zip(file).extract(directory)
      InstallRecipe.Archive.TAR -> Decompressor.Tar(file).extract(directory)
    }
    Files.delete(file)
  }

  private fun sha256(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    DigestInputStream(Files.newInputStream(file), digest).use { it.transferTo(java.io.OutputStream.nullOutputStream()) }
    return HexFormat.of().formatHex(digest.digest())
  }

  private fun notify(project: Project?, @Nls content: String, type: NotificationType, @Nls actionText: String? = null, action: () -> Unit = {}) {
    val notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP).createNotification(content, type)
    if (actionText != null) notification.addAction(NotificationAction.createSimpleExpiring(actionText, action))
    notification.notify(project)
  }

  private val LOG = logger<ToolInstaller>()
}

/** An install step failed. [message] is for the user. */
class ToolInstallException(@Nls message: String) : Exception(message)
