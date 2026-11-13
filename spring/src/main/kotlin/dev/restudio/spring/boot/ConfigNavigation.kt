package dev.restudio.spring.boot

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.properties.psi.Property
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.PsiTreeUtil
import dev.restudio.spring.SpringBundle
import dev.restudio.spring.model.SpringAnnotations
import dev.restudio.spring.model.SpringNames
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Ctrl+click navigation:
 * - from a `${key}` in `@Value` to the key in the configuration files,
 * - from the prefix of `@ConfigurationProperties` to the keys with that prefix,
 * - from a key in a configuration file to the `@ConfigurationProperties` field and to the `@Value` usages.
 */
class ConfigGotoDeclarationHandler : GotoDeclarationHandler {
  override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
    sourceElement ?: return null
    val literal = PsiTreeUtil.getParentOfType(sourceElement, PsiLiteralExpression::class.java, false)
    if (literal != null) return fromJava(literal, offset)?.toTypedArray()
    if (!ConfigFiles.isConfigFile(sourceElement.containingFile)) return null
    val definition = PsiTreeUtil.getParentOfType(sourceElement, Property::class.java, false)
      ?: PsiTreeUtil.getParentOfType(sourceElement, YAMLKeyValue::class.java, false)?.takeIf { kv ->
        kv.key?.let { PsiTreeUtil.isAncestor(it, sourceElement, false) } == true
      } ?: return null
    if (definition is Property && definition.firstChild?.let { PsiTreeUtil.isAncestor(it, sourceElement, false) } != true) return null
    val key = ConfigFiles.keyOf(definition) ?: return null
    return javaTargets(definition, key).takeIf { it.isNotEmpty() }?.toTypedArray()
  }

  private fun fromJava(literal: PsiLiteralExpression, offset: Int): List<PsiElement>? {
    val annotation = ConfigFiles.annotationOf(literal) ?: return null
    val text = literal.value as? String ?: return null
    val module = ConfigFiles.moduleOf(literal) ?: return null
    return when (annotation.qualifiedName) {
      SpringNames.VALUE -> {
        // The literal text starts with a quote.
        val inLiteral = offset - literal.textRange.startOffset - 1
        val placeholder = ConfigFiles.placeholders(text).firstOrNull { inLiteral >= it.range.startOffset && inLiteral <= it.range.endOffset }
          ?: return null
        val files = ConfigFiles.configFiles(module)
        val targets = ConfigFiles.definitions(files, placeholder.key).toMutableList()
        ConfigMetadata.of(module).find(placeholder.key)?.let { property ->
          ConfigMetadata.sourceField(literal.project, property, literal.resolveScope)?.let(targets::add)
        }
        targets
      }
      SpringNames.CONFIGURATION_PROPERTIES -> ConfigFiles.definitionsWithPrefix(ConfigFiles.configFiles(module), text).take(MAX_TARGETS)
      else -> null
    }
  }

  private fun javaTargets(definition: PsiElement, key: String): List<PsiElement> {
    val module = ConfigFiles.moduleOf(definition) ?: return emptyList()
    val result = mutableListOf<PsiElement>()
    val metadata = ConfigMetadata.of(module)
    metadata.find(key)?.let { property ->
      ConfigMetadata.sourceField(definition.project, property, GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, true))
        ?.let(result::add)
    }
    if (result.isEmpty()) projectField(definition, key)?.let(result::add)
    result += ConfigFiles.valueUsages(module, key)
    return result.distinct()
  }

  /** Returns the field of a `@ConfigurationProperties` class in the project for [key], also when the metadata has no source type. */
  private fun projectField(context: PsiElement, key: String): PsiField? {
    val project = context.project
    val annotation = JavaPsiFacade.getInstance(project).findClass(SpringNames.CONFIGURATION_PROPERTIES, context.resolveScope) ?: return null
    val canonical = ConfigMetadata.canonical(key)
    for (owner in AnnotatedElementsSearch.searchPsiClasses(annotation, GlobalSearchScope.projectScope(project)).findAll()) {
      val prefix = owner.getAnnotation(SpringNames.CONFIGURATION_PROPERTIES)?.let { SpringAnnotations.firstString(it, "prefix", "value") } ?: continue
      val canonicalPrefix = ConfigMetadata.canonical(prefix)
      if (!canonical.startsWith("$canonicalPrefix.")) continue
      var psiClass = owner
      var field: PsiField? = null
      for (segment in canonical.removePrefix("$canonicalPrefix.").split('.')) {
        field = psiClass.allFields.firstOrNull { ConfigMetadata.canonical(it.name) == segment } ?: break
        psiClass = com.intellij.psi.util.PsiUtil.resolveClassInClassTypeOnly(field.type) ?: break
      }
      if (field != null) return field
    }
    return null
  }

  private companion object {
    const val MAX_TARGETS = 100
  }
}

/** Shows the description, the type and the default value of a Spring Boot property. */
class ConfigDocumentationProvider : AbstractDocumentationProvider() {
  override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
    if (element is ConfigPropertyElement) return render(element.property)
    element ?: return null
    if (!ConfigFiles.isConfigFile(element.containingFile)) return null
    val key = ConfigFiles.keyOf(element) ?: return null
    val property = ConfigMetadata.of(element)?.find(key) ?: return null
    return render(property)
  }

  override fun getDocumentationElementForLookupItem(psiManager: PsiManager, item: Any?, element: PsiElement?): PsiElement? {
    val property = item as? ConfigProperty ?: return null
    return ConfigPropertyElement(psiManager, property, element)
  }

  private fun render(property: ConfigProperty): String = buildString {
    append("<div class='definition'><pre><b>").append(StringUtil.escapeXmlEntities(property.name)).append("</b>")
    property.type?.let { append("\n").append(StringUtil.escapeXmlEntities(it)) }
    append("</pre></div><div class='content'>")
    property.description?.let { append(it) }
    append("</div><table class='sections'>")
    property.defaultValue?.let { section(SpringBundle.message("doc.default"), StringUtil.escapeXmlEntities(it)) }
    property.sourceType?.let { section(SpringBundle.message("doc.source"), StringUtil.escapeXmlEntities(it)) }
    if (property.deprecated) section(SpringBundle.message("doc.deprecated"), SpringBundle.message("doc.deprecated.yes"))
    append("</table>")
  }

  private fun StringBuilder.section(name: String, value: String) {
    append("<tr><td valign='top' class='section'><p>").append(name).append("</td><td valign='top'>").append(value).append("</td></tr>")
  }
}

/** A PSI element for the documentation popup of a completion item. */
class ConfigPropertyElement(private val manager: PsiManager, val property: ConfigProperty, private val context: PsiElement?) : FakePsiElement() {
  override fun getParent(): PsiElement? = context
  override fun getManager(): PsiManager = manager
  override fun getName(): String = property.name
  override fun getPresentableText(): String = property.name
  override fun isValid(): Boolean = true
}
