package dev.restudio.spring.inspection

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiMethod
import dev.restudio.spring.SpringBundle
import dev.restudio.spring.inject.InjectionPoint
import dev.restudio.spring.inject.InjectionPoints
import dev.restudio.spring.inject.InjectionProblem
import dev.restudio.spring.inject.InjectionResolver
import dev.restudio.spring.model.SpringBean
import dev.restudio.spring.model.SpringModels
import dev.restudio.spring.model.SpringNames

/**
 * Reports injection points that Spring cannot fill: no bean of the type, more than one bean, or an unknown qualifier.
 * The inspection reports a problem only when all the contexts of the class have it.
 */
class SpringAutowiringInspection : AbstractBaseJavaLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    if (!SpringModels.isSpringAvailable(holder.file)) return PsiElementVisitor.EMPTY_VISITOR
    return object : JavaElementVisitor() {
      override fun visitClass(aClass: PsiClass) {
        val contexts = SpringModels.contextsOf(aClass)
        if (contexts.isEmpty()) return
        val points = mutableListOf<InjectionPoint>()
        if (contexts.any { it.isBeanClass(aClass) }) points += InjectionPoints.ofClass(aClass)
        for (method in aClass.methods) {
          if (method.hasAnnotation(SpringNames.BEAN)) points += InjectionPoints.ofBeanMethod(method)
        }
        for (point in points) check(point, holder)
      }
    }
  }

  private fun check(point: InjectionPoint, holder: ProblemsHolder) {
    if (!point.anchor.isPhysical || point.anchor.containingFile != holder.file) return
    val results = InjectionResolver.resolve(point)
    when (val problem = InjectionResolver.commonProblem(results)) {
      null -> {}
      is InjectionProblem.NoBeans ->
        holder.registerProblem(point.anchor, SpringBundle.message("inspection.autowiring.no.beans", problem.type.presentableText))
      is InjectionProblem.MultipleBeans -> {
        val fixes = mutableListOf<LocalQuickFix>()
        problem.beans.forEach { fixes += AddQualifierFix(it.name) }
        problem.beans.filter { it.element.isWritable && it.element.isPhysical }.forEach { fixes += MarkPrimaryFix(it.element, it.name) }
        holder.registerProblem(point.anchor, SpringBundle.message("inspection.autowiring.multiple.beans",
                                                                  problem.type.presentableText, describe(problem.beans)),
                               *fixes.toTypedArray())
      }
      is InjectionProblem.UnknownQualifier -> {
        val target = problem.annotation.findDeclaredAttributeValue("value") ?: problem.annotation
        if (target.containingFile == holder.file) {
          holder.registerProblem(target, SpringBundle.message("inspection.autowiring.unknown.qualifier", problem.value))
        }
      }
    }
  }

  private fun describe(beans: List<SpringBean>): String =
    beans.sortedBy { it.name }.joinToString(", ") { bean -> "${bean.name} (${bean.element.containingFile?.name ?: "?"})" }
}
