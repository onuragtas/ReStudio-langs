package dev.restudio.spring.graph

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import dev.restudio.spring.SpringBundle
import dev.restudio.spring.SpringIcons
import dev.restudio.spring.model.SpringModels
import dev.restudio.spring.model.SpringNames

/**
 * Opens the bean graph and selects the bean of the current class or `@Bean` method.
 * The Project view and the editor give the element in the data context. The gutter icon gives it in [target].
 */
class ShowBeanGraphAction(private val target: SmartPsiElementPointer<out PsiElement>? = null) : DumbAwareAction() {
  init {
    templatePresentation.text = SpringBundle.message("action.show.bean.graph")
    templatePresentation.description = SpringBundle.message("action.show.bean.graph.description")
    templatePresentation.icon = SpringIcons.Bean
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project = e.project
    e.presentation.isEnabledAndVisible = project != null && !DumbService.isDumb(project) && element(e) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val element = element(e) ?: return
    BeanGraphToolWindowFactory.show(project, element)
  }

  /** Returns the `@Bean` method or the class of the event when it is a bean or a configuration class. */
  private fun element(e: AnActionEvent): PsiElement? {
    target?.let { return it.element }
    val selected = e.getData(CommonDataKeys.PSI_ELEMENT) ?: e.getData(CommonDataKeys.PSI_FILE) ?: return null
    val method = selected as? PsiMethod ?: PsiTreeUtil.getParentOfType(selected, PsiMethod::class.java)
    if (method != null && method.hasAnnotation(SpringNames.BEAN) && method.containingClass?.let(SpringModels::contextsOf)?.isNotEmpty() == true) {
      return method
    }
    val psiClass = when (selected) {
      is PsiClass -> selected
      is PsiJavaFile -> selected.classes.firstOrNull()
      else -> PsiTreeUtil.getParentOfType(selected, PsiClass::class.java)
    } ?: return null
    return psiClass.takeIf { SpringModels.contextsOf(it).isNotEmpty() }
  }
}
