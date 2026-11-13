package dev.restudio.spring.boot

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.properties.psi.Property
import com.intellij.lang.properties.parsing.PropertiesTokenTypes
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import dev.restudio.spring.SpringIcons
import org.jetbrains.yaml.YAMLUtil
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/** Completes Spring Boot property keys and values in `application.properties` and `application.yml`. */
class ConfigCompletionContributor : CompletionContributor() {
  override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
    val file = parameters.originalFile
    if (!ConfigFiles.isConfigFile(file)) return
    val metadata = ConfigMetadata.of(file) ?: return
    if (metadata.properties.isEmpty()) return
    val position = parameters.position
    when (position.node.elementType) {
      PropertiesTokenTypes.KEY_CHARACTERS -> propertiesKeys(position, parameters, metadata, result)
      PropertiesTokenTypes.VALUE_CHARACTERS -> {
        val key = PsiTreeUtil.getParentOfType(position, Property::class.java)?.key ?: return
        values(metadata.find(key), result)
      }
      else -> if (file is YAMLFile) yaml(position, parameters, metadata, result)
    }
  }

  private fun propertiesKeys(position: PsiElement, parameters: CompletionParameters, metadata: ConfigMetadata, result: CompletionResultSet) {
    val prefix = position.text.substring(0, parameters.offset - position.textRange.startOffset)
    val set = result.withPrefixMatcher(prefix)
    for (property in metadata.properties.values) set.addElement(lookup(property, property.name, PROPERTIES_INSERT))
    set.stopHere()
  }

  private fun yaml(position: PsiElement, parameters: CompletionParameters, metadata: ConfigMetadata, result: CompletionResultSet) {
    val scalar = PsiTreeUtil.getParentOfType(position, YAMLScalar::class.java, false)
    val keyValue = PsiTreeUtil.getParentOfType(position, YAMLKeyValue::class.java, false)
    val document = parameters.editor.document

    // `key: <caret>` on the same line: complete the values of the key.
    if (scalar != null && keyValue != null && keyValue.value == scalar) {
      val keyLine = keyValue.key?.let { document.getLineNumber(it.textRange.startOffset) }
      if (keyLine == document.getLineNumber(scalar.textRange.startOffset)) {
        values(metadata.find(YAMLUtil.getConfigFullName(keyValue)), result)
        return
      }
    }

    val parentKey: String? = when {
      // A new line below `spring:` is parsed as the value of `spring`.
      scalar != null && keyValue != null && keyValue.value == scalar -> YAMLUtil.getConfigFullName(keyValue)
      keyValue != null && keyValue.key == position -> (keyValue.parent as? YAMLMapping)?.let { enclosingKey(it) }
      scalar != null -> enclosingKey(scalar)
      else -> return
    }
    val prefix = position.text.substring(0, parameters.offset - position.textRange.startOffset)
    val set = result.withPrefixMatcher(prefix)
    val parentCanonical = parentKey?.let(ConfigMetadata::canonical)
    val depth = parentKey?.split('.')?.size ?: 0
    val seen = mutableSetOf<String>()
    for (property in metadata.properties.values) {
      if (parentCanonical != null && !ConfigMetadata.canonical(property.name).startsWith("$parentCanonical.")) continue
      val rest = property.name.split('.').drop(depth).joinToString(".")
      if (rest.isEmpty() || !seen.add(rest)) continue
      set.addElement(lookup(property, rest, YAML_INSERT))
    }
    set.stopHere()
  }

  private fun enclosingKey(element: PsiElement): String? {
    var current: PsiElement? = element.parent
    while (current != null && current !is YAMLDocument) {
      if (current is YAMLKeyValue) return YAMLUtil.getConfigFullName(current)
      current = current.parent
    }
    return null
  }

  private fun values(property: ConfigProperty?, result: CompletionResultSet) {
    property ?: return
    val values = property.values.ifEmpty { if (property.type == "java.lang.Boolean" || property.type == "boolean") listOf("true", "false") else emptyList() }
    for (value in values) result.addElement(LookupElementBuilder.create(value))
    if (values.isNotEmpty()) result.stopHere()
  }

  private fun lookup(property: ConfigProperty, text: String, insert: InsertHandler<LookupElement>): LookupElement =
    LookupElementBuilder.create(property, text)
      .withIcon(SpringIcons.Bean)
      .withTypeText(property.type?.let(::shortType), true)
      .withTailText(property.defaultValue?.let { " = $it" }, true)
      .withStrikeoutness(property.deprecated)
      .withInsertHandler(insert)

  private companion object {
    /** Returns the type without package names: `java.util.List<java.lang.String>` gives `List<String>`. */
    fun shortType(type: String): String = type.replace(Regex("(?:[a-z_][\\w]*\\.)+"), "")

    val PROPERTIES_INSERT = InsertHandler<LookupElement> { context, _ ->
      val document = context.document
      val tail = context.tailOffset
      if (tail >= document.textLength || document.charsSequence[tail] != '=') {
        document.insertString(tail, "=")
      }
      context.editor.caretModel.moveToOffset(tail + 1)
    }
    val YAML_INSERT = InsertHandler<LookupElement> { context, _ ->
      val document = context.document
      val tail = context.tailOffset
      if (tail >= document.textLength || document.charsSequence[tail] != ':') {
        document.insertString(tail, ": ")
        context.editor.caretModel.moveToOffset(tail + 2)
      }
    }
  }
}
