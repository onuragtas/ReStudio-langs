package dev.restudio.spring.inject

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.TypeConversionUtil
import dev.restudio.spring.model.SpringBean
import dev.restudio.spring.model.SpringContext
import dev.restudio.spring.model.SpringModels
import dev.restudio.spring.model.SpringNames

/** How Spring uses the beans for an injection point. */
enum class InjectionMode {
  /** One bean. */
  SINGLE,
  /** `Optional<T>`: one bean or none. */
  OPTIONAL,
  /** `ObjectProvider<T>`, `Provider<T>`: Spring gets the bean when the code asks for it. */
  LAZY,
  /** A collection, a map or an array: all the matching beans. */
  MULTIPLE,
}

sealed interface InjectionProblem {
  data class NoBeans(val type: PsiType) : InjectionProblem
  data class MultipleBeans(val type: PsiType, val beans: List<SpringBean>) : InjectionProblem
  data class UnknownQualifier(val annotation: PsiAnnotation, val value: String) : InjectionProblem
}

/** The result of the resolution of an injection point in one context. */
class InjectionResult(
  val point: InjectionPoint,
  val context: SpringContext,
  /** The beans of the type that match the qualifier. */
  val candidates: List<SpringBean>,
  /** The beans that Spring injects. For [InjectionMode.SINGLE] there is one bean when the resolution has no problem. */
  val resolved: List<SpringBean>,
  val problem: InjectionProblem?,
)

object InjectionResolver {
  /** Returns the injection points of the class or `@Bean` method that contains [element], with the contexts that contain them. */
  fun contextsOf(point: InjectionPoint): List<SpringContext> {
    val method = PsiTreeUtil.getParentOfType(point.owner, PsiMethod::class.java, false)
    val psiClass = PsiTreeUtil.getParentOfType(point.owner, PsiClass::class.java, false) ?: return emptyList()
    return SpringModels.contexts(psiClass.project).filter {
      if (point.kind == InjectionKind.BEAN_METHOD) method != null && it.beansOf(method).isNotEmpty()
      else it.isBeanClass(psiClass)
    }
  }

  /** Resolves [point] in all its contexts. */
  fun resolve(point: InjectionPoint): List<InjectionResult> = contextsOf(point).mapNotNull { resolve(point, it) }

  /** Returns the problem that all the contexts of [point] report, or null. */
  fun commonProblem(results: List<InjectionResult>): InjectionProblem? {
    if (results.isEmpty()) return null
    val problems = results.map { it.problem ?: return null }
    val first = problems.first()
    return if (problems.all { it::class == first::class }) first else null
  }

  fun resolve(point: InjectionPoint, context: SpringContext): InjectionResult? {
    val (required, mode) = unwrap(point.type) ?: return null
    var candidates = context.beans.filter { isAssignable(required, it.type) }
    val qualifier = point.qualifier()

    if (point.byName) {
      val name = point.resourceName ?: point.name
      val named = context.beans.filter { name in it.names }
      if (named.isNotEmpty() || point.resourceName != null) {
        return InjectionResult(point, context, named, named, null)
      }
    }

    if (qualifier != null) {
      val (annotation, value) = qualifier
      val annotationName = annotation.qualifiedName ?: return null
      val matched = candidates.filter { it.matchesQualifier(annotationName, value) }
      if (matched.isEmpty() && mode != InjectionMode.MULTIPLE && mode != InjectionMode.LAZY) {
        val anyType = context.beans.any { it.matchesQualifier(annotationName, value) }
        val problem = when {
          !anyType && value != null && canReportUnknownQualifier(context) -> InjectionProblem.UnknownQualifier(annotation, value)
          anyType && mode == InjectionMode.SINGLE && point.required && canReportMissing(context, required) -> InjectionProblem.NoBeans(required)
          else -> null
        }
        return InjectionResult(point, context, matched, matched, problem)
      }
      candidates = matched
    }

    if (mode == InjectionMode.MULTIPLE || mode == InjectionMode.LAZY) {
      return InjectionResult(point, context, candidates, candidates, null)
    }
    if (candidates.isEmpty()) {
      val problem = if (mode == InjectionMode.SINGLE && point.required && canReportMissing(context, required)) InjectionProblem.NoBeans(required) else null
      return InjectionResult(point, context, candidates, candidates, problem)
    }
    if (candidates.size == 1) return InjectionResult(point, context, candidates, candidates, null)

    val chosen = choose(candidates, point.name)
    if (chosen != null) return InjectionResult(point, context, candidates, listOf(chosen), null)
    val certain = candidates.filter { !it.conditional }
    val uncertain = candidates.any { it.primary || it.priority || it.fallback }
    val problem = if (certain.size >= 2 && !uncertain) InjectionProblem.MultipleBeans(required, candidates) else null
    return InjectionResult(point, context, candidates, candidates, problem)
  }

  /** Picks one bean from many in the order of Spring: `@Primary`, not `@Fallback`, the parameter name. */
  private fun choose(candidates: List<SpringBean>, name: String): SpringBean? {
    candidates.filter { it.primary }.singleOrNull()?.let { return it }
    candidates.filter { !it.fallback }.takeIf { it.size < candidates.size }?.singleOrNull()?.let { return it }
    return candidates.filter { name in it.names }.singleOrNull()
  }

  /**
   * Returns the type to look up and the mode. Returns null when the model cannot know the type,
   * for example a type parameter or a raw collection.
   */
  fun unwrap(type: PsiType): Pair<PsiType, InjectionMode>? {
    if (type is PsiArrayType) return element(type.componentType)?.let { it to InjectionMode.MULTIPLE }
    val classType = type as? PsiClassType ?: return null
    val psiClass = classType.resolve() ?: return null
    if (psiClass is PsiTypeParameter) return null
    val name = psiClass.qualifiedName
    val parameters = classType.parameters
    return when (name) {
      in SpringNames.COLLECTIONS -> parameters.getOrNull(0)?.let(::element)?.let { it to InjectionMode.MULTIPLE }
      in SpringNames.MAPS -> parameters.getOrNull(1)?.let(::element)?.let { it to InjectionMode.MULTIPLE }
      "java.util.Optional" -> parameters.getOrNull(0)?.let(::element)?.let { inner ->
        unwrap(inner)?.let { (found, mode) -> found to if (mode == InjectionMode.SINGLE) InjectionMode.OPTIONAL else mode }
      }
      in SpringNames.LAZY_WRAPPERS -> parameters.getOrNull(0)?.let(::element)?.let { inner ->
        unwrap(inner)?.let { (found, _) -> found to InjectionMode.LAZY }
      }
      else -> type to InjectionMode.SINGLE
    }
  }

  private fun element(type: PsiType): PsiType? {
    val result = if (type is PsiWildcardType) type.extendsBound else type
    if (result is PsiClassType && result.resolve() is PsiTypeParameter) return null
    return result
  }

  private fun isAssignable(required: PsiType, beanType: PsiType): Boolean =
    TypeConversionUtil.isAssignable(required, beanType, false)

  /**
   * Returns true when the model is complete enough to say that no bean of [type] exists.
   * The model cannot see beans from XML files, registrars and unknown `FactoryBean` types. A registrar
   * usually adds interface proxies (repositories, HTTP clients), so the check stops only for interfaces then.
   */
  fun canReportMissing(context: SpringContext, type: PsiType): Boolean {
    if (context.hasXmlConfiguration || context.hasUntypedBeans || context.hasUnknownScanPackages) return false
    val psiClass = (type as? PsiClassType)?.resolve() ?: return false
    if (psiClass is PsiTypeParameter) return false
    val abstract = psiClass.isInterface || psiClass.hasModifierProperty(PsiModifier.ABSTRACT)
    val file = psiClass.containingFile?.virtualFile ?: return false
    val inProject = ProjectFileIndex.getInstance(psiClass.project).isInSourceContent(file)
    if (!inProject) return !abstract
    if (abstract) {
      if (context.hasOpaqueRegistrars) return false
      if (InheritanceUtil.isInheritor(psiClass, SpringNames.DATA_REPOSITORY)) return false
      val annotated = psiClass.annotations.any { annotation ->
        val name = annotation.qualifiedName ?: return@any true
        !name.startsWith("java.") && !name.startsWith("org.jetbrains.annotations.")
      }
      if (annotated) return false
    }
    return true
  }

  private fun canReportUnknownQualifier(context: SpringContext): Boolean =
    !context.hasXmlConfiguration && !context.hasOpaqueRegistrars && !context.hasUntypedBeans && !context.hasUnknownScanPackages
}
