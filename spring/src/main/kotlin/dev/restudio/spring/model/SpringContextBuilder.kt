package dev.restudio.spring.model

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiTypes
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiUtil
import java.beans.Introspector
import java.io.StringReader
import java.util.Properties

/** Builds the [SpringContext] of one root class. Call it in a read action. */
class SpringContextBuilder(private val root: PsiClass) {
  private val project = root.project
  private val facade = JavaPsiFacade.getInstance(project)
  private val fileIndex = ProjectFileIndex.getInstance(project)
  private val scope: GlobalSearchScope = run {
    val module = ModuleUtilCore.findModuleForPsiElement(root)
    val file = root.containingFile?.virtualFile
    val inTests = file != null && fileIndex.isInTestSourceContent(file)
    if (module != null) GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, inTests)
    else GlobalSearchScope.allScope(project)
  }

  private val beans = mutableListOf<SpringBean>()
  private val configurations = linkedSetOf<PsiClass>()
  private val componentClasses = mutableSetOf<PsiClass>()
  private val scanPackages = linkedSetOf<String>()
  private val propertiesPackages = linkedSetOf<String>()
  private var xml = false
  private var opaque = false
  private var untyped = false
  private var unknownPackages = false
  private var autoConfigured = false
  private var repositoriesEnabled = false
  private val autoConfigurationExcludes = mutableSetOf<String>()

  private val stereotypes: List<PsiClass> by lazy { findStereotypes() }

  fun build(): SpringContext {
    component(root, Origin(conditional = false, auto = false))
    if (autoConfigured) autoConfigurations()
    if (autoConfigured || repositoriesEnabled) repositories()
    return SpringContext(
      root, scope, beans.toList(), configurations.toSet(), scanPackages.toSet(),
      xml, opaque, untyped, unknownPackages, autoConfigured,
    )
  }

  private data class Origin(val conditional: Boolean, val auto: Boolean)

  // Classes

  private fun component(psiClass: PsiClass, origin: Origin, scanned: Boolean = false) {
    if (!componentClasses.add(psiClass)) return
    if (psiClass.isInterface || psiClass.isAnnotationType || psiClass.hasModifierProperty(PsiModifier.ABSTRACT)) return
    if (isExcludedByClassCondition(psiClass)) return
    val conditional = origin.conditional || isConditional(psiClass)
    val name = componentName(psiClass) ?: if (scanned) defaultName(psiClass) else psiClass.qualifiedName ?: defaultName(psiClass)
    addBean(name, emptyList(), psiClass, typeOfClass(psiClass), BeanKind.COMPONENT, conditional, origin.auto)
    configuration(psiClass, origin.copy(conditional = conditional))
  }

  private fun configuration(psiClass: PsiClass, origin: Origin) {
    if (!configurations.add(psiClass)) return

    for (scan in componentScans(psiClass)) {
      val filtered = hasFilters(scan, psiClass)
      val packages = scanPackages(scan, psiClass)
      for (pkg in packages) {
        if (scanPackages.add(pkg)) {
          for (found in scan(pkg)) component(found, origin.copy(conditional = origin.conditional || filtered), scanned = true)
        }
      }
    }

    for (import in SpringAnnotations.findAllMeta(psiClass, SpringNames.IMPORT)) {
      for (imported in SpringAnnotations.classes(import, "value")) {
        when {
          imported == null -> opaque(origin)
          imported.qualifiedName == SpringNames.AUTO_CONFIGURATION_PACKAGES_REGISTRAR -> {}
          imported.qualifiedName.orEmpty().endsWith("RepositoriesRegistrar") -> repositoriesEnabled = true
          InheritanceUtil.isInheritor(imported, SpringNames.AUTO_CONFIGURATION_IMPORT_SELECTOR) ||
            imported.qualifiedName == SpringNames.AUTO_CONFIGURATION_IMPORT_SELECTOR -> enableAutoConfiguration(psiClass)
          isInfrastructureImport(imported) -> {}
          InheritanceUtil.isInheritor(imported, SpringNames.IMPORT_SELECTOR) ||
            InheritanceUtil.isInheritor(imported, SpringNames.IMPORT_REGISTRAR) -> opaque(origin)
          else -> component(imported, origin)
        }
      }
    }

    if (SpringAnnotations.findAllMeta(psiClass, SpringNames.IMPORT_RESOURCE).isNotEmpty()) xml = true

    for (enable in SpringAnnotations.findAllMeta(psiClass, SpringNames.ENABLE_CONFIGURATION_PROPERTIES)) {
      for (propertiesClass in SpringAnnotations.classes(enable, "value")) {
        if (propertiesClass == null) opaque(origin) else configurationProperties(propertiesClass, origin)
      }
    }
    for (propertiesScan in SpringAnnotations.findAllMeta(psiClass, SpringNames.CONFIGURATION_PROPERTIES_SCAN)) {
      for (pkg in packagesOf(propertiesScan, psiClass, listOf("value", "basePackages"), "basePackageClasses")) {
        if (propertiesPackages.add(pkg)) scanConfigurationProperties(pkg, origin)
      }
    }

    beanMethods(psiClass, origin)

    for (inner in psiClass.innerClasses) {
      if ((inner.hasModifierProperty(PsiModifier.STATIC) || inner.containingClass?.isInterface == true) &&
          SpringAnnotations.hasMeta(inner, SpringNames.COMPONENT)) {
        component(inner, origin)
      }
    }
  }

  /**
   * Returns true for the selectors and registrars of the Spring Framework and Spring Boot that add only infrastructure beans
   * (`@EnableCaching`, `@EnableAsync`, `@EnableTransactionManagement`, `@EnableConfigurationProperties`, ...).
   * They add no beans that the application code injects by an interface type of the project.
   */
  private fun isInfrastructureImport(imported: PsiClass): Boolean {
    val name = imported.qualifiedName ?: return false
    if (name in INFRASTRUCTURE_REGISTRARS) return true
    val framework = INFRASTRUCTURE_PACKAGES.any { name.startsWith(it) }
    return framework && InheritanceUtil.isInheritor(imported, SpringNames.IMPORT_SELECTOR) &&
           !InheritanceUtil.isInheritor(imported, SpringNames.IMPORT_REGISTRAR)
  }

  /**
   * Records beans that the model cannot see. The registrars of the auto-configurations add repositories,
   * which [repositories] adds itself, so they do not count.
   */
  private fun opaque(origin: Origin) {
    if (!origin.auto) opaque = true
  }

  private fun configurationProperties(psiClass: PsiClass, origin: Origin) {
    if (!componentClasses.add(psiClass)) return
    val prefix = psiClass.getAnnotation(SpringNames.CONFIGURATION_PROPERTIES)
      ?.let { SpringAnnotations.firstString(it, "prefix", "value") }
    val name = if (prefix.isNullOrEmpty()) psiClass.qualifiedName ?: defaultName(psiClass) else "$prefix-${psiClass.qualifiedName}"
    addBean(name, emptyList(), psiClass, typeOfClass(psiClass), BeanKind.CONFIGURATION_PROPERTIES,
            origin.conditional || isConditional(psiClass), origin.auto)
  }

  private fun beanMethods(psiClass: PsiClass, origin: Origin) {
    val seen = mutableSetOf<String>()
    for (method in psiClass.allMethods) {
      if (method.containingClass?.qualifiedName == "java.lang.Object") continue
      val key = method.name + "/" + method.parameterList.parametersCount
      if (!seen.add(key)) continue
      val annotation = method.getAnnotation(SpringNames.BEAN) ?: continue
      if (isExcludedByClassCondition(method)) continue
      val names = (SpringAnnotations.strings(annotation, "name").orEmpty() + SpringAnnotations.strings(annotation, "value").orEmpty())
        .filter { it.isNotEmpty() }
      val returnType = method.returnType ?: continue
      if (returnType == PsiTypes.voidType()) continue
      addBean(names.firstOrNull() ?: method.name, names.drop(1), method, productType(returnType), BeanKind.BEAN_METHOD,
              origin.conditional || isConditional(method), origin.auto)
    }
  }

  private fun addBean(name: String, aliases: List<String>, element: PsiModifierListOwner, type: PsiType?, kind: BeanKind,
                      conditional: Boolean, auto: Boolean) {
    if (type == null) {
      untyped = true
      return
    }
    val qualifiers = qualifiersOf(element)
    val profiles = SpringAnnotations.findAllMeta(element, SpringNames.PROFILE).flatMap { SpringAnnotations.strings(it, "value").orEmpty() }
    beans += SpringBean(
      name = name,
      aliases = aliases,
      type = type,
      element = element,
      kind = kind,
      primary = SpringAnnotations.hasMeta(element, SpringNames.PRIMARY),
      fallback = SpringAnnotations.hasMeta(element, SpringNames.FALLBACK),
      priority = SpringAnnotations.hasAny(element, SpringNames.PRIORITY),
      conditional = conditional || auto,
      profiles = profiles,
      qualifiers = qualifiers,
      autoConfiguration = auto,
    )
  }

  // Types

  private fun typeOfClass(psiClass: PsiClass): PsiType? =
    productType(facade.elementFactory.createType(psiClass))

  /** Returns the product type of a `FactoryBean`, or [type] itself. Returns null when the type is not known. */
  private fun productType(type: PsiType): PsiType? {
    // A class that is not on the class path matches no injection point. Keep the bean; it does no harm.
    val psiClass = (type as? PsiClassType)?.resolve() ?: return type
    if (psiClass is PsiTypeParameter) return null
    if (!InheritanceUtil.isInheritor(psiClass, SpringNames.FACTORY_BEAN)) return type
    val product = PsiUtil.substituteTypeParameter(type, SpringNames.FACTORY_BEAN, 0, false) ?: return null
    return if ((product as? PsiClassType)?.resolve() is PsiTypeParameter) null else product
  }

  // Names and qualifiers

  private fun componentName(psiClass: PsiClass): String? {
    for (annotation in psiClass.annotations) {
      val name = annotation.qualifiedName ?: continue
      val stereotype = name == SpringNames.COMPONENT || SpringAnnotations.isMetaAnnotated(annotation, SpringNames.COMPONENT) ||
                       name in SpringNames.NAMED || name in SpringNames.MANAGED_BEAN
      if (stereotype) SpringAnnotations.firstString(annotation, "value")?.let { return it }
    }
    return null
  }

  private fun defaultName(psiClass: PsiClass): String {
    val names = generateSequence(psiClass) { it.containingClass }.mapNotNull { it.name }.toList().reversed()
    return Introspector.decapitalize(names.joinToString("."))
  }

  private fun qualifiersOf(element: PsiModifierListOwner): List<BeanQualifier> {
    val result = mutableListOf<BeanQualifier>()
    for (annotation in element.annotations) {
      val name = annotation.qualifiedName ?: continue
      when {
        name == SpringNames.QUALIFIER || name in SpringNames.NAMED ->
          result += BeanQualifier(name, SpringAnnotations.firstString(annotation, "value"))
        SpringAnnotations.isMetaAnnotated(annotation, SpringNames.QUALIFIER) ||
          SpringNames.INJECT_QUALIFIER.any { SpringAnnotations.isMetaAnnotated(annotation, it) } ->
          result += BeanQualifier(name, SpringAnnotations.firstString(annotation, "value"))
      }
    }
    return result
  }

  // Conditions

  private fun isConditional(element: PsiModifierListOwner): Boolean =
    SpringAnnotations.hasMeta(element, SpringNames.CONDITIONAL) || element.hasAnnotation(SpringNames.CONDITIONAL)

  /** Returns true when `@ConditionalOnClass` or `@ConditionalOnMissingClass` is false for certain. */
  private fun isExcludedByClassCondition(element: PsiModifierListOwner): Boolean {
    for (condition in SpringAnnotations.findAllMeta(element, SpringNames.CONDITIONAL_ON_CLASS)) {
      if (SpringAnnotations.classes(condition, "value").any { it == null }) return true
      if (SpringAnnotations.strings(condition, "name").orEmpty().any { facade.findClass(it, scope) == null }) return true
    }
    for (condition in SpringAnnotations.findAllMeta(element, SpringNames.CONDITIONAL_ON_MISSING_CLASS)) {
      if (SpringAnnotations.strings(condition, "value").orEmpty().any { facade.findClass(it, scope) != null }) return true
    }
    return false
  }

  // Component scan

  private fun componentScans(psiClass: PsiClass): List<PsiAnnotation> {
    val result = SpringAnnotations.findAllMeta(psiClass, SpringNames.COMPONENT_SCAN).toMutableList()
    for (container in SpringAnnotations.findAllMeta(psiClass, SpringNames.COMPONENT_SCANS)) {
      val value = container.findDeclaredAttributeValue("value")
      val items = if (value is PsiArrayInitializerMemberValue) value.initializers.toList() else listOfNotNull(value)
      result += items.filterIsInstance<PsiAnnotation>()
    }
    return result
  }

  /** Returns true when the `@ComponentScan` that [psiClass] declares itself has filters. */
  private fun hasFilters(scan: PsiAnnotation, psiClass: PsiClass): Boolean {
    if (scan.owner != psiClass.modifierList) return false
    return scan.findDeclaredAttributeValue("includeFilters") != null ||
           scan.findDeclaredAttributeValue("excludeFilters") != null ||
           !SpringAnnotations.boolean(scan, "useDefaultFilters", true)
  }

  private fun scanPackages(scan: PsiAnnotation, psiClass: PsiClass): List<String> {
    val packages = packagesOf(scan, psiClass, listOf("value", "basePackages"), "basePackageClasses", useDefault = false)
    val aliased = aliasedScanPackages(psiClass)
    val all = packages + aliased
    return all.ifEmpty { listOf(packageOf(psiClass)) }
  }

  /** Reads `@AliasFor` attributes of the annotations of [psiClass] that point to `@ComponentScan`, for example `scanBasePackages`. */
  private fun aliasedScanPackages(psiClass: PsiClass): List<String> {
    val result = mutableListOf<String>()
    for (annotation in psiClass.annotations) {
      if (!SpringAnnotations.isMetaAnnotated(annotation, SpringNames.COMPONENT_SCAN)) continue
      val type = annotation.resolveAnnotationType() ?: continue
      for (method in type.methods) {
        val alias = method.getAnnotation("org.springframework.core.annotation.AliasFor") ?: continue
        val target = SpringAnnotations.classes(alias, "annotation").firstOrNull()?.qualifiedName
        if (target != SpringNames.COMPONENT_SCAN) continue
        val attribute = SpringAnnotations.firstString(alias, "attribute", "value") ?: method.name
        when (attribute) {
          "basePackages", "value" -> result += splitPackages(SpringAnnotations.strings(annotation, method.name))
          "basePackageClasses" -> result += SpringAnnotations.classes(annotation, method.name).map { it?.let(::packageOf) ?: unknown() }
        }
      }
    }
    return result.filter { it != UNKNOWN }
  }

  private fun packagesOf(annotation: PsiAnnotation, owner: PsiClass, stringAttributes: List<String>, classAttribute: String,
                         useDefault: Boolean = true): List<String> {
    val result = mutableListOf<String>()
    for (attribute in stringAttributes) result += splitPackages(SpringAnnotations.strings(annotation, attribute))
    for (psiClass in SpringAnnotations.classes(annotation, classAttribute)) result += psiClass?.let(::packageOf) ?: unknown()
    val known = result.filter { it != UNKNOWN }
    return if (known.isEmpty() && useDefault && result.isEmpty()) listOf(packageOf(owner)) else known
  }

  private fun splitPackages(values: List<String>?): List<String> {
    if (values == null) return listOf(unknown())
    return values.flatMap { it.split(',', ';', ' ', '\t', '\n') }.map { it.trim() }.filter { it.isNotEmpty() }.map {
      if (it.contains("\${") || it.contains('*')) unknown() else it
    }
  }

  private fun unknown(): String {
    unknownPackages = true
    return UNKNOWN
  }

  private fun packageOf(psiClass: PsiClass): String = (psiClass.containingFile as? PsiClassOwner)?.packageName.orEmpty()

  private fun inPackage(psiClass: PsiClass, pkg: String): Boolean {
    val name = packageOf(psiClass)
    return pkg.isEmpty() || name == pkg || name.startsWith("$pkg.")
  }

  private fun scan(pkg: String): List<PsiClass> {
    val result = linkedSetOf<PsiClass>()
    for (stereotype in stereotypes) {
      AnnotatedElementsSearch.searchPsiClasses(stereotype, scope).forEach { found ->
        if (!found.isAnnotationType && inPackage(found, pkg) && isTopLevelOrStatic(found)) result += found
      }
    }
    return result.toList()
  }

  private fun isTopLevelOrStatic(psiClass: PsiClass): Boolean =
    psiClass.containingClass == null || psiClass.hasModifierProperty(PsiModifier.STATIC)

  /** Returns `@Component` and all annotation types that have it as a meta-annotation, also `@Named` and `@ManagedBean`. */
  private fun findStereotypes(): List<PsiClass> {
    val result = linkedSetOf<PsiClass>()
    val queue = ArrayDeque<PsiClass>()
    facade.findClass(SpringNames.COMPONENT, scope)?.let { queue += it }
    (SpringNames.NAMED + SpringNames.MANAGED_BEAN).mapNotNullTo(result) { facade.findClass(it, scope) }
    while (queue.isNotEmpty()) {
      val next = queue.removeFirst()
      if (!result.add(next)) continue
      AnnotatedElementsSearch.searchPsiClasses(next, scope).forEach { found ->
        if (found.isAnnotationType && found !in result) queue += found
      }
    }
    return result.toList()
  }

  private fun scanConfigurationProperties(pkg: String, origin: Origin) {
    val annotation = facade.findClass(SpringNames.CONFIGURATION_PROPERTIES, scope) ?: return
    AnnotatedElementsSearch.searchPsiClasses(annotation, scope).forEach { found ->
      if (!found.isAnnotationType && inPackage(found, pkg) && !SpringAnnotations.hasMeta(found, SpringNames.COMPONENT)) {
        configurationProperties(found, origin)
      }
    }
  }

  // Spring Boot

  private fun enableAutoConfiguration(declaringClass: PsiClass) {
    autoConfigured = true
    for (annotation in declaringClass.annotations) {
      val name = annotation.qualifiedName ?: continue
      if (name != SpringNames.ENABLE_AUTO_CONFIGURATION && !SpringAnnotations.isMetaAnnotated(annotation, SpringNames.ENABLE_AUTO_CONFIGURATION)) continue
      SpringAnnotations.classes(annotation, "exclude").mapNotNullTo(autoConfigurationExcludes) { it?.qualifiedName }
      autoConfigurationExcludes += SpringAnnotations.strings(annotation, "excludeName").orEmpty()
    }
  }

  private fun autoConfigurations() {
    val origin = Origin(conditional = true, auto = true)
    for (name in AutoConfigurations.classNames(project, scope)) {
      if (name in autoConfigurationExcludes) continue
      val psiClass = facade.findClass(name, scope) ?: continue
      component(psiClass, origin)
    }
  }

  /** Adds the Spring Data repository interfaces in the project. Spring Boot enables the repository scan. */
  private fun repositories() {
    val repository = facade.findClass(SpringNames.DATA_REPOSITORY, scope) ?: return
    val packages = scanPackages + packageOf(root)
    val projectScope = scope.intersectWith(GlobalSearchScope.projectScope(project))
    ClassInheritorsSearch.search(repository, projectScope, true).forEach { found ->
      if (found.isInterface && !found.hasAnnotation(SpringNames.NO_REPOSITORY_BEAN) && packages.any { inPackage(found, it) }) {
        addBean(Introspector.decapitalize(found.name ?: ""), emptyList(), found, facade.elementFactory.createType(found),
                BeanKind.REPOSITORY, conditional = true, auto = false)
      }
    }
  }

  private companion object {
    val INFRASTRUCTURE_PACKAGES = listOf(
      "org.springframework.context.", "org.springframework.cache.", "org.springframework.scheduling.",
      "org.springframework.transaction.", "org.springframework.boot.", "org.springframework.aop.",
      "org.springframework.web.servlet.", "org.springframework.web.reactive.", "org.springframework.retry.",
    )
    val INFRASTRUCTURE_REGISTRARS = setOf(
      "org.springframework.boot.context.properties.EnableConfigurationPropertiesRegistrar",
      "org.springframework.boot.context.properties.ConfigurationPropertiesScanRegistrar",
      "org.springframework.context.annotation.AspectJAutoProxyRegistrar",
      "org.springframework.context.annotation.AutoProxyRegistrar",
    )
    const val UNKNOWN = "\u0000unknown"
  }
}

/** Reads the auto-configuration class names from the jars of a scope. */
object AutoConfigurations {
  fun classNames(project: com.intellij.openapi.project.Project, scope: GlobalSearchScope): List<String> {
    val result = linkedSetOf<String>()
    for (file in FilenameIndex.getVirtualFilesByName(SpringNames.AUTO_CONFIGURATION_IMPORTS, scope)) {
      if (file.parent?.name != "spring") continue
      VfsUtilCore.loadText(file).lineSequence().map { it.substringBefore('#').trim() }.filterTo(result) { it.isNotEmpty() }
    }
    for (file in FilenameIndex.getVirtualFilesByName("spring.factories", scope)) {
      val properties = Properties()
      runCatching { properties.load(StringReader(VfsUtilCore.loadText(file))) }
      properties.getProperty(SpringNames.ENABLE_AUTO_CONFIGURATION)?.split(',')?.map { it.trim() }?.filterTo(result) { it.isNotEmpty() }
    }
    if (project.isDisposed) return emptyList()
    return result.toList()
  }
}
