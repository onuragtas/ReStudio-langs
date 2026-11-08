package dev.restudio.core.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.PluginAdvertiserSuppressor
import com.intellij.openapi.vfs.VirtualFile

/**
 * Stops the "*.go files are supported by IntelliJ IDEA" editor notification for the files of a ReStudio language.
 *
 * A ReStudio plugin already handles these files through a language server. The platform calls this in a background
 * read action and asks it to be fast. Thus it reads only the file extension, not the file type or the content.
 */
abstract class FileExtensionAdvertiserSuppressor(private val extensions: Set<String>) : PluginAdvertiserSuppressor {
  override fun isSuppressedFor(project: Project, file: VirtualFile): Boolean = file.extension?.lowercase() in extensions
}
