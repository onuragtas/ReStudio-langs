package dev.restudio.spring.boot

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiUtil
import dev.restudio.spring.model.SpringAnnotations
import dev.restudio.spring.model.SpringNames

/** One property from `spring-configuration-metadata.json`, or from a `@ConfigurationProperties` class of the project. */
data class ConfigProperty(
  val name: String,
  val type: String?,
  val description: String?,
  val defaultValue: String?,
  val sourceType: String?,
  val deprecated: Boolean,
  /** The values from the `hints` section. */
  val values: List<String>,
)

/** The configuration properties that a module can use. */
class ConfigMetadata(val properties: Map<String, ConfigProperty>) {
  private val byCanonicalName = properties.values.associateBy { canonical(it.name) }

  /** The first segments of all names, for example `spring` and `server`. */
  val roots: Set<String> = properties.keys.mapTo(mutableSetOf()) { canonical(it).substringBefore('.') }

  fun find(key: String): ConfigProperty? = byCanonicalName[canonical(key)]

  /**
   * Returns true when Spring can bind [key]: it is a property, a part of a property name,
   * or an element of a map or a collection property.
   */
  fun isKnown(key: String): Boolean {
    val canonicalKey = canonical(key)
    if (canonicalKey in byCanonicalName) return true
    if (byCanonicalName.keys.any { it.startsWith("$canonicalKey.") }) return true
    var prefix = canonicalKey
    while (prefix.contains('.')) {
      prefix = prefix.substringBeforeLast('.')
      val property = byCanonicalName[prefix] ?: continue
      if (isContainerOrObject(property.type)) return true
    }
    return false
  }

  private fun isContainerOrObject(type: String?): Boolean {
    if (type == null) return true
    val raw = type.substringBefore('<')
    return raw in CONTAINERS || raw.endsWith("[]") || !(raw.startsWith("java.lang.") || raw in SCALARS)
  }

  companion object {
    private val LOG = logger<ConfigMetadata>()
    private val KEY = Key.create<CachedValue<ConfigMetadata>>("restudio.spring.configMetadata")
    private val CONTAINERS = setOf(
      "java.util.Map", "java.util.List", "java.util.Set", "java.util.Collection", "java.util.Properties",
      "java.util.LinkedHashMap", "java.util.HashMap", "java.util.TreeMap", "java.util.SortedMap",
    )
    private val SCALARS = setOf(
      "boolean", "int", "long", "short", "byte", "char", "float", "double",
      "java.time.Duration", "java.time.Period", "org.springframework.util.unit.DataSize",
      "java.nio.charset.Charset", "java.util.Locale", "java.net.InetAddress", "java.net.URI", "java.net.URL",
      "org.springframework.core.io.Resource", "java.io.File", "java.nio.file.Path", "java.util.TimeZone",
    )
    const val METADATA = "spring-configuration-metadata.json"
    const val ADDITIONAL_METADATA = "additional-spring-configuration-metadata.json"

    /**
     * Returns the canonical form of a property name for relaxed binding: lower case,
     * no dashes and no underscores. Spring accepts `server.max-http-header-size`, `server.maxHttpHeaderSize` and
     * `server.max_http_header_size` for the same property.
     */
    fun canonical(name: String): String = name.lowercase().replace("-", "").replace("_", "").replace(Regex("\\[[^]]*]"), "")

    fun of(element: PsiElement): ConfigMetadata? {
      val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return null
      return of(module)
    }

    fun of(module: Module): ConfigMetadata =
      CachedValuesManager.getManager(module.project).getCachedValue(module, KEY, {
        CachedValueProvider.Result.create(load(module), PsiModificationTracker.getInstance(module.project),
                                          ProjectRootModificationTracker.getInstance(module.project))
      }, false)

    private fun load(module: Module): ConfigMetadata {
      val scope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, true)
      val result = linkedMapOf<String, ConfigProperty>()
      for (name in listOf(METADATA, ADDITIONAL_METADATA)) {
        for (file in FilenameIndex.getVirtualFilesByName(name, scope)) {
          if (file.parent?.name != "META-INF") continue
          runCatching { parse(VfsUtilCore.loadText(file), result) }.onFailure { LOG.debug("Cannot read $file", it) }
        }
      }
      fromProjectClasses(module, result)
      return ConfigMetadata(result)
    }

    internal fun parse(text: String, result: MutableMap<String, ConfigProperty>) {
      val root = JsonParser.parseString(text).asJsonObject
      val hints = mutableMapOf<String, List<String>>()
      root.getAsJsonArray("hints")?.forEach { hint ->
        val item = hint.asJsonObject
        val name = item.string("name") ?: return@forEach
        hints[name] = item.getAsJsonArray("values")?.mapNotNull { value ->
          value.asJsonObject.get("value")?.takeIf { it.isJsonPrimitive }?.asString
        }.orEmpty()
      }
      root.getAsJsonArray("properties")?.forEach { property ->
        val item = property.asJsonObject
        val name = item.string("name") ?: return@forEach
        val existing = result[name]
        result[name] = ConfigProperty(
          name = name,
          type = item.string("type") ?: existing?.type,
          description = item.string("description") ?: existing?.description,
          defaultValue = item.get("defaultValue")?.takeIf { !it.isJsonNull }?.toString()?.removeSurrounding("\"") ?: existing?.defaultValue,
          sourceType = item.string("sourceType") ?: existing?.sourceType,
          deprecated = item.has("deprecation") || item.get("deprecated")?.asBoolean == true,
          values = hints[name] ?: existing?.values.orEmpty(),
        )
      }
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

    /**
     * Adds the properties of the `@ConfigurationProperties` classes in the project sources. The annotation processor
     * writes them to the metadata file only at build time; this makes them available before the first build.
     */
    private fun fromProjectClasses(module: Module, result: MutableMap<String, ConfigProperty>) {
      val project = module.project
      val annotation = JavaPsiFacade.getInstance(project)
        .findClass(SpringNames.CONFIGURATION_PROPERTIES, GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, true)) ?: return
      val scope = GlobalSearchScope.moduleWithDependenciesScope(module)
      for (owner in AnnotatedElementsSearch.searchPsiClasses(annotation, scope).findAll()) {
        val prefix = owner.getAnnotation(SpringNames.CONFIGURATION_PROPERTIES)?.let { SpringAnnotations.firstString(it, "prefix", "value") } ?: continue
        addFields(owner, prefix, result, mutableSetOf())
      }
    }

    private fun addFields(psiClass: PsiClass, prefix: String, result: MutableMap<String, ConfigProperty>, visited: MutableSet<PsiClass>) {
      if (!visited.add(psiClass) || visited.size > 8) return
      for (field in psiClass.allFields) {
        if (field.hasModifierProperty(PsiModifier.STATIC)) continue
        val name = "$prefix.${kebab(field.name)}"
        val type = field.type.canonicalText
        val fieldClass = PsiUtil.resolveClassInClassTypeOnly(field.type)
        val nested = fieldClass != null && fieldClass.containingClass == psiClass && !fieldClass.isEnum
        if (nested) {
          addFields(fieldClass, name, result, visited)
          continue
        }
        if (name in result) continue
        result[name] = ConfigProperty(name, type, field.docComment?.descriptionElements?.joinToString("") { it.text }?.trim(),
                                      null, psiClass.qualifiedName, field.isDeprecated, enumValues(fieldClass))
      }
    }

    private fun enumValues(psiClass: PsiClass?): List<String> =
      if (psiClass?.isEnum == true) psiClass.fields.filterIsInstance<com.intellij.psi.PsiEnumConstant>().map { it.name } else emptyList()

    /** Converts a Java field name to the kebab-case property name: `maxSize` gives `max-size`. */
    fun kebab(name: String): String = buildString {
      for ((index, char) in name.withIndex()) {
        if (char.isUpperCase() && index > 0) append('-')
        append(char.lowercaseChar())
      }
    }

    /** Returns the field of a property in its source type, or null. */
    fun sourceField(project: com.intellij.openapi.project.Project, property: ConfigProperty, scope: GlobalSearchScope): PsiField? {
      val sourceType = property.sourceType ?: return null
      val psiClass = JavaPsiFacade.getInstance(project).findClass(sourceType.replace('$', '.'), scope) ?: return null
      val last = canonical(property.name.substringAfterLast('.'))
      return psiClass.allFields.firstOrNull { canonical(it.name) == last }
    }
  }
}
