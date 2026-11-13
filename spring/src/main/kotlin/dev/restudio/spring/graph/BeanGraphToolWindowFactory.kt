package dev.restudio.spring.graph

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import com.intellij.ui.content.ContentFactory

/** Creates the Spring bean graph tool window. */
class BeanGraphToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = BeanGraphPanel(project)
    val content = ContentFactory.getInstance().createContent(panel, "", false)
    content.setDisposer(panel)
    content.preferredFocusableComponent = panel
    toolWindow.contentManager.addContent(content)
  }

  companion object {
    const val ID = "SpringBeanGraph"

    /** Opens the tool window and selects the beans that [element] declares. */
    fun show(project: Project, element: PsiElement) {
      val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
      toolWindow.show {
        val panel = toolWindow.contentManager.contents.firstOrNull()?.component as? BeanGraphPanel ?: return@show
        panel.reveal(element)
      }
    }
  }
}
