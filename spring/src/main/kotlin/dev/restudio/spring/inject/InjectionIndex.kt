package dev.restudio.spring.inject

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import dev.restudio.spring.model.SpringBean
import dev.restudio.spring.model.SpringContext
import dev.restudio.spring.model.SpringModels
import dev.restudio.spring.model.SpringNames

/** Cached lookups between injection points and beans. Call the functions in a read action. */
object InjectionIndex {
  /** Returns the injection points of [psiClass] in the contexts where it is a bean or a configuration class. */
  fun pointsOf(psiClass: PsiClass): List<InjectionPoint> = CachedValuesManager.getCachedValue(psiClass) {
    val contexts = SpringModels.contextsOf(psiClass)
    val points = mutableListOf<InjectionPoint>()
    if (contexts.any { it.isBeanClass(psiClass) }) points += InjectionPoints.ofClass(psiClass)
    if (contexts.isNotEmpty()) {
      for (method in psiClass.methods) {
        if (method.hasAnnotation(SpringNames.BEAN)) points += InjectionPoints.ofBeanMethod(method)
      }
    }
    CachedValueProvider.Result.create(points.toList(), *dependencies(psiClass))
  }

  /** Returns the injection point with the anchor [anchor], or null. */
  fun pointAt(psiClass: PsiClass, anchor: PsiElement): InjectionPoint? = pointsOf(psiClass).firstOrNull { it.anchor == anchor }

  /** Returns the beans that Spring injects into [point], in all contexts. */
  fun targetsOf(point: InjectionPoint): List<SpringBean> =
    InjectionResolver.resolve(point).flatMap { it.resolved }.distinctBy { it.element }

  /** Returns the injection points in the project sources that get the bean [bean] in the context [context]. */
  fun usagesOf(context: SpringContext, bean: SpringBean): List<InjectionPoint> = usages(context)[bean.element].orEmpty()

  private fun usages(context: SpringContext): Map<PsiElement, List<InjectionPoint>> =
    CachedValuesManager.getCachedValue(context.root) {
      val index = ProjectFileIndex.getInstance(context.root.project)
      val classes = (context.beans.mapNotNull { it.element as? PsiClass } + context.configurations).distinct().filter { psiClass ->
        psiClass.containingFile?.virtualFile?.let(index::isInSourceContent) == true
      }
      val result = mutableMapOf<PsiElement, MutableList<InjectionPoint>>()
      val current = SpringModels.context(context.root)
      for (psiClass in classes) {
        for (point in pointsOf(psiClass)) {
          val resolution = InjectionResolver.resolve(point, current) ?: continue
          for (bean in resolution.resolved) result.getOrPut(bean.element) { mutableListOf() } += point
        }
      }
      CachedValueProvider.Result.create(result.mapValues { it.value.toList() }, *dependencies(context.root))
    }

  private fun dependencies(element: PsiElement): Array<Any> =
    arrayOf(PsiModificationTracker.getInstance(element.project), ProjectRootModificationTracker.getInstance(element.project))
}
