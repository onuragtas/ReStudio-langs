package dev.restudio.spring.boot

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.properties.psi.Property
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YamlPsiElementVisitor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import dev.restudio.spring.SpringBundle
import org.jetbrains.yaml.YAMLUtil
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

/**
 * Reports keys in `application.properties` and `application.yml` that no Spring Boot property has.
 * To prevent false alarms, it checks only keys below the roots of the library metadata (`spring`, `server`, ...):
 * the project can bind its own keys. A key that a `@Value` placeholder uses is also not reported.
 */
open class ConfigKeyInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    if (!ConfigFiles.isConfigFile(holder.file)) return PsiElementVisitor.EMPTY_VISITOR
    val metadata = ConfigMetadata.of(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
    if (metadata.properties.isEmpty()) return PsiElementVisitor.EMPTY_VISITOR
    val module = ConfigFiles.moduleOf(holder.file)
    fun check(key: String?, anchor: PsiElement) {
      if (key.isNullOrBlank() || key.contains("\${")) return
      val root = ConfigMetadata.canonical(key).substringBefore('.')
      if (root !in metadata.roots || metadata.isKnown(key)) return
      if (module != null && ConfigFiles.valueUsages(module, key).isNotEmpty()) return
      holder.registerProblem(anchor, SpringBundle.message("inspection.config.unknown.key", key), ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
    }
    if (holder.file is YAMLFile) {
      return object : YamlPsiElementVisitor() {
        override fun visitKeyValue(keyValue: YAMLKeyValue) {
          if (keyValue.value !is YAMLMapping) check(YAMLUtil.getConfigFullName(keyValue), keyValue.key ?: keyValue)
        }
      }
    }
    return object : PsiElementVisitor() {
      override fun visitElement(element: PsiElement) {
        if (element is Property) check(element.key, element.firstChild ?: element)
      }
    }
  }
}

/** The same check for `application.yml`. The inspection needs its own class to have its own short name. */
class YamlConfigKeyInspection : ConfigKeyInspection()
