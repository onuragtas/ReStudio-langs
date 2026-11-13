package dev.restudio.spring.boot

import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.lang.properties.psi.Property
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.util.TextRange
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.PsiTreeUtil
import dev.restudio.spring.model.SpringNames
import org.jetbrains.yaml.YAMLUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue

/** Finds Spring Boot configuration files and the keys in them. */
object ConfigFiles {
  private val NAME = Regex("(application|bootstrap)(-[\\w.-]+)?\\.(properties|ya?ml)")

  /** Returns true when the file name is `application.properties`, `application-dev.yml`, `bootstrap.yaml` and so on. */
  fun isConfigFile(file: PsiFile?): Boolean = file != null && NAME.matches(file.name)

  /** Returns the configuration files in the source and resource roots of [module] and its dependencies. */
  fun configFiles(module: Module): List<PsiFile> {
    val project = module.project
    val scope = GlobalSearchScope.moduleWithDependenciesScope(module)
    val manager = PsiManager.getInstance(project)
    return listOf("properties", "yml", "yaml")
      .flatMap { FilenameIndex.getAllFilesByExt(project, it, scope) }
      .filter { NAME.matches(it.name) }
      .mapNotNull { manager.findFile(it) }
  }

  /** Returns the elements that define [key] in [files]: properties or YAML key-value pairs. */
  fun definitions(files: List<PsiFile>, key: String): List<PsiElement> {
    val canonical = ConfigMetadata.canonical(key)
    return keys(files).filter { ConfigMetadata.canonical(it.first) == canonical }.map { it.second }
  }

  /** Returns the elements of the keys that start with [prefix]. */
  fun definitionsWithPrefix(files: List<PsiFile>, prefix: String): List<PsiElement> {
    val canonical = ConfigMetadata.canonical(prefix)
    return keys(files).filter { ConfigMetadata.canonical(it.first).startsWith("$canonical.") }.map { it.second }
  }

  /** Returns all leaf keys of [files] with their full names. */
  fun keys(files: List<PsiFile>): List<Pair<String, PsiElement>> = files.flatMap { file ->
    when (file) {
      is PropertiesFile -> file.properties.mapNotNull { property -> property.key?.let { it to property.psiElement } }
      is YAMLFile -> PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)
        .filter { it.value !is org.jetbrains.yaml.psi.YAMLMapping }
        .map { YAMLUtil.getConfigFullName(it) to it }
      else -> emptyList()
    }
  }

  /** Returns the full key of a property or a YAML key-value pair, or null. */
  fun keyOf(element: PsiElement): String? = when (element) {
    is Property -> element.key
    is YAMLKeyValue -> YAMLUtil.getConfigFullName(element)
    else -> null
  }

  /** A `${key:default}` placeholder in a string, with the range of the key in the string. */
  data class Placeholder(val key: String, val range: TextRange)

  /** Returns the placeholders in [text]. Nested placeholders in default values are also returned. */
  fun placeholders(text: String): List<Placeholder> {
    val result = mutableListOf<Placeholder>()
    var index = text.indexOf("\${")
    while (index >= 0) {
      val start = index + 2
      var end = start
      while (end < text.length && text[end] != ':' && text[end] != '}' && text[end] != '$') end++
      if (end > start) result += Placeholder(text.substring(start, end).trim(), TextRange(start, end))
      index = text.indexOf("\${", start)
    }
    return result
  }

  /** Returns the `@Value` literals in the project that use [key]. */
  fun valueUsages(module: Module, key: String): List<PsiLiteralExpression> {
    val project = module.project
    val valueClass = JavaPsiFacade.getInstance(project)
      .findClass(SpringNames.VALUE, GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, true)) ?: return emptyList()
    val canonical = ConfigMetadata.canonical(key)
    val result = mutableListOf<PsiLiteralExpression>()
    val scope = GlobalSearchScope.projectScope(project)
    for (owner in AnnotatedElementsSearch.searchElements(valueClass, scope, PsiModifierListOwner::class.java).findAll()) {
      val literal = owner.getAnnotation(SpringNames.VALUE)?.findDeclaredAttributeValue("value") as? PsiLiteralExpression ?: continue
      val text = literal.value as? String ?: continue
      if (placeholders(text).any { ConfigMetadata.canonical(it.key) == canonical }) result += literal
    }
    return result
  }

  /** Returns the annotation that has [literal] as an attribute value, or null. */
  fun annotationOf(literal: PsiLiteralExpression): PsiAnnotation? =
    PsiTreeUtil.getParentOfType(literal, PsiAnnotation::class.java)?.takeIf { annotation ->
      annotation.parameterList.attributes.any { PsiTreeUtil.isAncestor(it, literal, false) }
    }

  fun moduleOf(element: PsiElement): Module? = ModuleUtilCore.findModuleForPsiElement(element)
}
