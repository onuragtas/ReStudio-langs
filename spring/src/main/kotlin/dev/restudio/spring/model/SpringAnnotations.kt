package dev.restudio.spring.model

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import java.util.concurrent.ConcurrentHashMap

/** Reads annotations and meta-annotations in the way that Spring reads them. */
object SpringAnnotations {
  /** Returns the annotations of [owner] that have the name [fqn] or are meta-annotated with it. */
  fun annotationsWithMeta(owner: PsiModifierListOwner, fqn: String): List<PsiAnnotation> =
    owner.annotations.filter { it.qualifiedName == fqn || isMetaAnnotated(it, fqn) }

  /** Returns true when [owner] has the annotation [fqn], directly or as a meta-annotation. */
  fun hasMeta(owner: PsiModifierListOwner, fqn: String): Boolean = annotationsWithMeta(owner, fqn).isNotEmpty()

  fun hasAny(owner: PsiModifierListOwner, names: Collection<String>): Boolean = names.any { owner.hasAnnotation(it) }

  fun findAny(owner: PsiModifierListOwner, names: Collection<String>): PsiAnnotation? =
    names.firstNotNullOfOrNull { owner.getAnnotation(it) }

  /**
   * Returns the instances of the annotation [fqn] on [owner]: the direct ones and the ones found on the
   * annotations of [owner] (meta-annotations, at all levels).
   */
  fun findAllMeta(owner: PsiModifierListOwner, fqn: String): List<PsiAnnotation> {
    val result = mutableListOf<PsiAnnotation>()
    collect(owner.annotations, fqn, mutableSetOf(), result)
    return result
  }

  private fun collect(annotations: Array<PsiAnnotation>, fqn: String, visited: MutableSet<String>, result: MutableList<PsiAnnotation>) {
    for (annotation in annotations) {
      val name = annotation.qualifiedName ?: continue
      if (name == fqn) {
        result += annotation
        continue
      }
      if (isPlatformAnnotation(name) || !visited.add(name)) continue
      val type = annotation.resolveAnnotationType() ?: continue
      collect(type.annotations, fqn, visited, result)
    }
  }

  /** Returns true when the annotation type of [annotation] is meta-annotated with [fqn]. */
  fun isMetaAnnotated(annotation: PsiAnnotation, fqn: String): Boolean {
    val name = annotation.qualifiedName ?: return false
    if (isPlatformAnnotation(name)) return false
    val type = annotation.resolveAnnotationType() ?: return false
    return isAnnotationTypeMetaAnnotated(type, fqn)
  }

  /** Returns true when the annotation type [type] is [fqn] or is meta-annotated with [fqn]. */
  fun isAnnotationTypeMetaAnnotated(type: PsiClass, fqn: String): Boolean {
    if (type.qualifiedName == fqn) return true
    val cache = CachedValuesManager.getCachedValue(type) {
      CachedValueProvider.Result.create(ConcurrentHashMap<String, Boolean>(), PsiModificationTracker.getInstance(type.project))
    }
    return cache.getOrPut(fqn) { search(type, fqn, mutableSetOf()) }
  }

  private fun search(type: PsiClass, fqn: String, visited: MutableSet<String>): Boolean {
    for (annotation in type.annotations) {
      val name = annotation.qualifiedName ?: continue
      if (name == fqn) return true
      if (isPlatformAnnotation(name) || !visited.add(name)) continue
      val next = annotation.resolveAnnotationType() ?: continue
      if (search(next, fqn, visited)) return true
    }
    return false
  }

  private fun isPlatformAnnotation(name: String): Boolean =
    name.startsWith("java.lang.") || name.startsWith("kotlin.") || name.startsWith("org.jetbrains.annotations.")

  /**
   * Returns the string values of the attribute [name], or null when a value is not a compile-time constant.
   * An attribute that is not set gives an empty list.
   */
  fun strings(annotation: PsiAnnotation, name: String): List<String>? {
    val value = annotation.findDeclaredAttributeValue(name) ?: return emptyList()
    val result = mutableListOf<String>()
    for (item in flatten(value)) {
      val constant = constant(item) ?: return null
      result += constant
    }
    return result
  }

  /** Returns the first non-empty string of the attributes [names], or null. */
  fun firstString(annotation: PsiAnnotation, vararg names: String): String? =
    names.firstNotNullOfOrNull { strings(annotation, it)?.firstOrNull { value -> value.isNotEmpty() } }

  /**
   * Returns the classes in the attribute [name]. An element is null when the class does not resolve.
   */
  fun classes(annotation: PsiAnnotation, name: String): List<PsiClass?> =
    flatten(annotation.findDeclaredAttributeValue(name) ?: return emptyList()).map { item ->
      ((item as? PsiClassObjectAccessExpression)?.operand?.type as? PsiClassType)?.resolve()
    }

  fun boolean(annotation: PsiAnnotation, name: String, default: Boolean): Boolean {
    val value = annotation.findDeclaredAttributeValue(name) as? PsiExpression ?: return default
    return JavaPsiFacade.getInstance(annotation.project).constantEvaluationHelper.computeConstantExpression(value) as? Boolean ?: default
  }

  private fun flatten(value: PsiAnnotationMemberValue): List<PsiAnnotationMemberValue> =
    if (value is PsiArrayInitializerMemberValue) value.initializers.toList() else listOf(value)

  private fun constant(value: PsiAnnotationMemberValue): String? {
    val expression = value as? PsiExpression ?: return null
    return JavaPsiFacade.getInstance(value.project).constantEvaluationHelper.computeConstantExpression(expression) as? String
  }
}
