package dev.restudio.spring.model

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil

/**
 * The entry point to the bean model. The model has one [SpringContext] for each class with `@ComponentScan`
 * in the project. The values are cached until the next PSI or project root change. Call the functions in a read action.
 */
object SpringModels {
  private val ROOTS = Key.create<com.intellij.psi.util.CachedValue<List<PsiClass>>>("restudio.spring.roots")

  /** Returns the classes with `@ComponentScan` (also through `@SpringBootApplication`) in the project sources. */
  fun roots(project: Project): List<PsiClass> =
    CachedValuesManager.getManager(project).getCachedValue(project, ROOTS, {
      CachedValueProvider.Result.create(findRoots(project), *dependencies(project))
    }, false)

  fun context(root: PsiClass): SpringContext =
    CachedValuesManager.getCachedValue(root) {
      CachedValueProvider.Result.create(SpringContextBuilder(root).build(), *dependencies(root.project))
    }

  fun contexts(project: Project): List<SpringContext> = roots(project).map(::context)

  /** Returns the contexts in which [psiClass] is a bean or a configuration class. */
  fun contextsOf(psiClass: PsiClass): List<SpringContext> =
    contexts(psiClass.project).filter { it.isBeanClass(psiClass) || it.isConfiguration(psiClass) }

  /** Returns the contexts that contain the class of [element]. */
  fun contextsOfElement(element: PsiElement): List<SpringContext> {
    val psiClass = element as? PsiClass ?: PsiTreeUtil.getParentOfType(element, PsiClass::class.java) ?: return emptyList()
    return contextsOf(psiClass)
  }

  fun isSpringAvailable(element: PsiElement): Boolean =
    JavaPsiFacade.getInstance(element.project).findClass(SpringNames.COMPONENT, element.resolveScope) != null

  private fun dependencies(project: Project): Array<Any> =
    arrayOf(PsiModificationTracker.getInstance(project), ProjectRootModificationTracker.getInstance(project))

  private fun findRoots(project: Project): List<PsiClass> {
    val facade = JavaPsiFacade.getInstance(project)
    val all = GlobalSearchScope.allScope(project)
    val scan = facade.findClass(SpringNames.COMPONENT_SCAN, all) ?: return emptyList()
    val annotationTypes = linkedSetOf<PsiClass>()
    val queue = ArrayDeque(listOf(scan))
    while (queue.isNotEmpty()) {
      val next = queue.removeFirst()
      if (!annotationTypes.add(next)) continue
      AnnotatedElementsSearch.searchPsiClasses(next, all).forEach { found ->
        if (found.isAnnotationType && found !in annotationTypes) queue += found
      }
    }
    val projectScope = GlobalSearchScope.projectScope(project)
    val result = linkedSetOf<PsiClass>()
    for (type in annotationTypes) {
      AnnotatedElementsSearch.searchPsiClasses(type, projectScope).forEach { found ->
        if (!found.isAnnotationType) result += found
      }
    }
    return result.toList()
  }
}
