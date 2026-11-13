package dev.restudio.spring.inject

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiType
import com.intellij.psi.util.PropertyUtilBase
import dev.restudio.spring.model.SpringAnnotations
import dev.restudio.spring.model.SpringNames

enum class InjectionKind { FIELD, CONSTRUCTOR, METHOD, BEAN_METHOD }

/** A place where Spring injects a bean: a field, a constructor parameter, a method parameter or a `@Bean` method parameter. */
class InjectionPoint(
  /** The element that has the qualifier annotations. For a Lombok constructor this is the field. */
  val owner: PsiModifierListOwner,
  /** The element that the inspection highlights and that has the gutter icon. */
  val anchor: PsiElement,
  val type: PsiType,
  val name: String,
  val kind: InjectionKind,
  val required: Boolean,
  /** True for `@Resource`: Spring tries the bean name first. */
  val byName: Boolean,
  /** The `name` of `@Resource`. Spring does not fall back to the type when it is set. */
  val resourceName: String?,
) {
  /** Returns the qualifier on the injection point: the annotation name and the value, or null. */
  fun qualifier(): Pair<PsiAnnotation, String?>? {
    for (annotation in owner.annotations) {
      val name = annotation.qualifiedName ?: continue
      if (name == SpringNames.QUALIFIER || name in SpringNames.NAMED) {
        return annotation to SpringAnnotations.firstString(annotation, "value")
      }
      if (SpringAnnotations.isMetaAnnotated(annotation, SpringNames.QUALIFIER) ||
          SpringNames.INJECT_QUALIFIER.any { SpringAnnotations.isMetaAnnotated(annotation, it) }) {
        return annotation to SpringAnnotations.firstString(annotation, "value")
      }
    }
    return null
  }

  override fun toString(): String = "$kind $name: ${type.canonicalText}"
}

/** Finds the injection points of a bean class or a `@Bean` method. */
object InjectionPoints {
  /** Returns the injection points of the bean class [psiClass]: fields, constructors and methods. */
  fun ofClass(psiClass: PsiClass): List<InjectionPoint> {
    val result = mutableListOf<InjectionPoint>()
    for (field in psiClass.fields) {
      if (field.hasModifierProperty(PsiModifier.STATIC) || field.hasAnnotation(SpringNames.VALUE)) continue
      val autowired = SpringAnnotations.findAny(field, SpringNames.AUTOWIRING)
      val resource = SpringAnnotations.findAny(field, SpringNames.RESOURCE)
      if (autowired == null && resource == null) continue
      result += InjectionPoint(
        field, anchorOf(field), field.type, field.name, InjectionKind.FIELD,
        required = isRequired(field, autowired), byName = resource != null,
        resourceName = resource?.let { SpringAnnotations.firstString(it, "name") },
      )
    }
    for (method in psiClass.methods) {
      if (method.isConstructor || method.hasModifierProperty(PsiModifier.STATIC)) continue
      val autowired = SpringAnnotations.findAny(method, SpringNames.AUTOWIRING)
      val resource = SpringAnnotations.findAny(method, SpringNames.RESOURCE)
      if (resource != null && method.parameterList.parametersCount == 1) {
        val parameter = method.parameterList.parameters[0]
        val property = PropertyUtilBase.getPropertyNameBySetter(method)
        result += InjectionPoint(parameter, anchorOf(method), parameter.type, property, InjectionKind.METHOD,
                                 required = true, byName = true, resourceName = SpringAnnotations.firstString(resource, "name"))
      }
      else if (autowired != null) {
        result += parameters(method, InjectionKind.METHOD, isRequired(method, autowired), psiClass)
      }
    }
    result += constructorPoints(psiClass)
    return result
  }

  /** Returns the parameters of the `@Bean` method [method]. */
  fun ofBeanMethod(method: PsiMethod): List<InjectionPoint> = parameters(method, InjectionKind.BEAN_METHOD, true, null)

  private fun constructorPoints(psiClass: PsiClass): List<InjectionPoint> {
    val constructors = psiClass.constructors
    val autowired = constructors.filter { SpringAnnotations.hasAny(it, SpringNames.AUTOWIRING) }
    val chosen = when {
      autowired.isNotEmpty() -> autowired
      constructors.size == 1 -> constructors.toList()
      else -> emptyList()
    }
    if (chosen.isEmpty() && constructors.isEmpty() && psiClass.isRecord) {
      return psiClass.recordComponents.map { component ->
        InjectionPoint(component, component.nameIdentifier ?: component, component.type, component.name,
                       InjectionKind.CONSTRUCTOR, required = true, byName = false, resourceName = null)
      }
    }
    return chosen.flatMap { constructor ->
      val autowiredAnnotation = SpringAnnotations.findAny(constructor, SpringNames.AUTOWIRING)
      parameters(constructor, InjectionKind.CONSTRUCTOR, isRequired(constructor, autowiredAnnotation), psiClass)
    }
  }

  private fun parameters(method: PsiMethod, kind: InjectionKind, required: Boolean, psiClass: PsiClass?): List<InjectionPoint> {
    val result = mutableListOf<InjectionPoint>()
    for (parameter in method.parameterList.parameters) {
      if (parameter.hasAnnotation(SpringNames.VALUE)) continue
      val name = parameter.name
      val physical = parameter.isPhysical && parameter.nameIdentifier != null
      // A record or a Lombok constructor has no source. Use the record component or the field with the same name.
      val field: PsiModifierListOwner? = if (physical) null else {
        psiClass?.recordComponents?.firstOrNull { it.name == name }
          ?: psiClass?.findFieldByName(name, false)?.takeIf { it.isPhysical }
      }
      if (field?.hasAnnotation(SpringNames.VALUE) == true) continue
      val owner: PsiModifierListOwner = field ?: parameter
      val anchor = if (physical) parameter.nameIdentifier!! else field?.let(::anchorOf) ?: anchorOf(method)
      result += InjectionPoint(owner, anchor, parameter.type, name, kind,
                               required = required && !isNullable(parameter) && !isNullable(owner),
                               byName = false, resourceName = null)
    }
    return result
  }

  private fun isRequired(owner: PsiModifierListOwner, autowired: PsiAnnotation?): Boolean {
    if (isNullable(owner)) return false
    if (autowired == null || autowired.qualifiedName != SpringNames.AUTOWIRED) return true
    return SpringAnnotations.boolean(autowired, "required", true)
  }

  private fun isNullable(owner: PsiModifierListOwner): Boolean =
    owner.annotations.any { it.qualifiedName?.substringAfterLast('.') == "Nullable" }

  private fun anchorOf(element: PsiElement): PsiElement = (element as? PsiNameIdentifierOwner)?.nameIdentifier ?: element

  /** Returns the field, constructor parameter or method parameter of [point] as a PSI element for navigation. */
  fun target(point: InjectionPoint): PsiElement = when (val owner = point.owner) {
    is PsiField, is PsiParameter -> owner
    else -> point.anchor
  }
}
