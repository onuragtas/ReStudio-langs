package dev.restudio.spring.model

import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope

/**
 * The beans of one application context. The root is a class with `@ComponentScan`, for example
 * the `@SpringBootApplication` class.
 */
class SpringContext(
  val root: PsiClass,
  val scope: GlobalSearchScope,
  val beans: List<SpringBean>,
  /** The configuration classes that the model read, also the scanned components ("lite" configuration). */
  val configurations: Set<PsiClass>,
  val scanPackages: Set<String>,
  /** True when `@ImportResource` adds XML bean definitions that the model does not read. */
  val hasXmlConfiguration: Boolean,
  /** True when an import selector or a registrar adds beans that the model does not see. */
  val hasOpaqueRegistrars: Boolean,
  /** True when a `@Bean` method or a `FactoryBean` has a type that the model cannot find. */
  val hasUntypedBeans: Boolean,
  /** True when a scan package is not a compile-time constant. */
  val hasUnknownScanPackages: Boolean,
  val autoConfigured: Boolean,
) {
  private val beanClasses: Set<PsiClass> = beans.filter { it.kind == BeanKind.COMPONENT || it.kind == BeanKind.CONFIGURATION_PROPERTIES }
    .mapNotNull { it.element as? PsiClass }.toSet()

  /** Returns true when the class [psiClass] is a bean of this context. */
  fun isBeanClass(psiClass: PsiClass): Boolean = psiClass in beanClasses

  fun isConfiguration(psiClass: PsiClass): Boolean = psiClass in configurations

  fun beansOf(element: com.intellij.psi.PsiElement): List<SpringBean> = beans.filter { it.element == element }

  override fun toString(): String = "SpringContext(${root.qualifiedName}, ${beans.size} beans)"
}
