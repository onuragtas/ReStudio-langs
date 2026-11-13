package dev.restudio.spring.navigation

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.PsiTreeUtil
import dev.restudio.spring.SpringBundle
import dev.restudio.spring.SpringIcons
import dev.restudio.spring.graph.ShowBeanGraphAction
import dev.restudio.spring.inject.InjectionIndex
import dev.restudio.spring.inject.InjectionPoints
import dev.restudio.spring.model.SpringModels
import dev.restudio.spring.model.SpringNames

/**
 * Puts gutter icons on beans and on injection points. The icon of a bean opens the injection points that get it.
 * The icon of an injection point opens the beans that Spring injects.
 */
class SpringLineMarkerProvider : RelatedItemLineMarkerProvider() {
  override fun getName(): String = SpringBundle.message("gutter.name")

  override fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>) {
    if (element !is PsiIdentifier) return
    val parent = element.parent
    when {
      parent is PsiClass && parent.nameIdentifier == element -> beanMarker(element, parent, parent)?.let(result::add)
      parent is PsiMethod && parent.nameIdentifier == element && parent.hasAnnotation(SpringNames.BEAN) ->
        beanMarker(element, parent, parent.containingClass ?: return)?.let(result::add)
      else -> injectionMarker(element)?.let(result::add)
    }
  }

  private fun beanMarker(identifier: PsiIdentifier, definition: PsiElement, psiClass: PsiClass): RelatedItemLineMarkerInfo<*>? {
    val contexts = SpringModels.contextsOf(psiClass).filter { it.beansOf(definition).isNotEmpty() }
    if (contexts.isEmpty()) return null
    val targets = NotNullLazyValue.lazy<Collection<PsiElement>> {
      contexts.flatMap { context -> context.beansOf(definition).flatMap { InjectionIndex.usagesOf(context, it) } }
        .map(InjectionPoints::target).distinct()
    }
    return NavigationGutterIconBuilder.create(SpringIcons.Bean)
      .setTargets(targets)
      .setTooltipText(SpringBundle.message("gutter.bean.tooltip"))
      .setPopupTitle(SpringBundle.message("gutter.bean.popup"))
      .setEmptyPopupText(SpringBundle.message("gutter.bean.empty"))
      .setAlignment(GutterIconRenderer.Alignment.LEFT)
      .createLineMarkerInfo(identifier)
      .let { BeanLineMarkerInfo(it, SmartPointerManager.createPointer(definition)) }
  }

  private fun injectionMarker(identifier: PsiIdentifier): RelatedItemLineMarkerInfo<*>? {
    val psiClass = PsiTreeUtil.getParentOfType(identifier, PsiClass::class.java) ?: return null
    val point = InjectionIndex.pointAt(psiClass, identifier) ?: return null
    val targets = NotNullLazyValue.lazy<Collection<PsiElement>> { InjectionIndex.targetsOf(point).map { it.element } }
    return NavigationGutterIconBuilder.create(SpringIcons.Injection)
      .setTargets(targets)
      .setTooltipText(SpringBundle.message("gutter.injection.tooltip"))
      .setPopupTitle(SpringBundle.message("gutter.injection.popup"))
      .setEmptyPopupText(SpringBundle.message("gutter.injection.empty"))
      .setAlignment(GutterIconRenderer.Alignment.LEFT)
      .createLineMarkerInfo(identifier)
  }
}

/** The marker of a bean. It has the same targets as [base] and adds "Show Bean Graph" to the popup menu of the icon. */
private class BeanLineMarkerInfo(base: RelatedItemLineMarkerInfo<PsiElement>, private val definition: SmartPsiElementPointer<PsiElement>) :
  RelatedItemLineMarkerInfo<PsiElement>(
    base.element!!, TextRange(base.startOffset, base.endOffset), base.icon,
    { base.lineMarkerTooltip }, base.navigationHandler, GutterIconRenderer.Alignment.LEFT,
    { base.createGotoRelatedItems() },
  ) {
  override fun createGutterRenderer(): GutterIconRenderer = object : LineMarkerInfo.LineMarkerGutterIconRenderer<PsiElement>(this) {
    override fun getPopupMenuActions(): ActionGroup = DefaultActionGroup(ShowBeanGraphAction(definition))
  }
}
