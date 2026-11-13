package dev.restudio.spring.model

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType

enum class BeanKind { COMPONENT, BEAN_METHOD, REPOSITORY, CONFIGURATION_PROPERTIES }

/** A qualifier on a bean: the qualifier annotation and its value. A null value matches all values. */
data class BeanQualifier(val annotation: String, val value: String?)

/** A bean definition that the model found in the code. */
class SpringBean(
  val name: String,
  val aliases: List<String>,
  /** The type of the bean. For a `FactoryBean` this is the type of the product. */
  val type: PsiType,
  /** The class or the `@Bean` method that defines the bean. */
  val element: PsiElement,
  val kind: BeanKind,
  val primary: Boolean,
  val fallback: Boolean,
  val priority: Boolean,
  /** True when a profile or a condition can remove the bean at run time. */
  val conditional: Boolean,
  val profiles: List<String>,
  val qualifiers: List<BeanQualifier>,
  val autoConfiguration: Boolean,
) {
  val names: List<String> get() = listOf(name) + aliases

  val beanClass: PsiClass? get() = element as? PsiClass ?: (element as? PsiMethod)?.containingClass

  /** Returns true when the bean has the qualifier value [value] of the annotation [annotation], or the name [value]. */
  fun matchesQualifier(annotation: String, value: String?): Boolean {
    if (annotation == SpringNames.QUALIFIER || annotation in SpringNames.NAMED) {
      if (value != null && value in names) return true
      return qualifiers.any { (it.annotation == SpringNames.QUALIFIER || it.annotation in SpringNames.NAMED) && it.value == value }
    }
    return qualifiers.any { it.annotation == annotation && (value == null || it.value == null || it.value == value) }
  }

  override fun toString(): String = "$name: ${type.canonicalText}"
}
