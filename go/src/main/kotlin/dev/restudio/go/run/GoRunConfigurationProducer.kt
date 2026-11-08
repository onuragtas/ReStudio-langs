package dev.restudio.go.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration
import dev.restudio.core.dap.DapLaunch
import dev.restudio.core.entrypoints.EntryPoint
import dev.restudio.core.entrypoints.EntryPointLocation

/**
 * Creates a Delve run configuration from a Go entry point.
 *
 * The gutter icon passes an [EntryPointLocation] with the exact entry point.
 * The editor context menu passes the whole file, because a TextMate file is one PSI leaf.
 * Then the producer uses the caret: the entry point on the caret line, or else the nearest one above it.
 */
class GoRunConfigurationProducer : LazyRunConfigurationProducer<DAPRunConfiguration>() {
  override fun getConfigurationFactory(): ConfigurationFactory = DapLaunch.configurationFactory()

  override fun setupConfigurationFromContext(
    configuration: DAPRunConfiguration,
    context: ConfigurationContext,
    sourceElement: Ref<PsiElement>,
  ): Boolean {
    val (name, launch) = launchFor(context) ?: return false
    launch.applyTo(configuration)
    configuration.name = name
    return true
  }

  override fun isConfigurationFromContext(configuration: DAPRunConfiguration, context: ConfigurationContext): Boolean =
    launchFor(context)?.second?.matches(configuration) == true

  private fun launchFor(context: ConfigurationContext): Pair<String, DapLaunch>? {
    val location = context.location ?: return null
    val file = location.psiElement.containingFile ?: return null
    val virtualFile = file.virtualFile ?: return null
    if (virtualFile.extension != "go") return null
    val entryPoint = (location as? EntryPointLocation)?.entryPoint ?: entryPointAtCaret(context, file) ?: return null
    return GoLaunches.create(virtualFile, entryPoint)
  }

  private fun entryPointAtCaret(context: ConfigurationContext, file: PsiFile): EntryPoint? {
    val document = file.viewProvider.document ?: return null
    val offset = CommonDataKeys.EDITOR.getData(context.dataContext)?.caretModel?.offset ?: context.psiLocation?.textRange?.startOffset ?: return null
    val line = document.getLineNumber(offset)
    val entryPoints = GoEntryPointDetector.detect(file.name, document.charsSequence)
    return entryPoints.firstOrNull { document.getLineNumber(it.offset) == line }
      ?: entryPoints.lastOrNull { document.getLineNumber(it.offset) < line }
  }
}
