package dev.restudio.spring.inspection

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import dev.restudio.spring.SpringBundle
import dev.restudio.spring.model.SpringNames

/** Adds `@Qualifier("name")` to the field or the parameter of the problem. */
class AddQualifierFix(private val beanName: String) : LocalQuickFix {
  override fun getFamilyName(): String = SpringBundle.message("fix.add.qualifier.family")

  override fun getName(): String = SpringBundle.message("fix.add.qualifier", beanName)

  override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
    val owner = PsiTreeUtil.getParentOfType(descriptor.psiElement, PsiModifierListOwner::class.java, false) ?: return
    val modifiers = owner.modifierList ?: return
    modifiers.findAnnotation(SpringNames.QUALIFIER)?.delete()
    val text = "@${SpringNames.QUALIFIER}(\"${beanName.replace("\\", "\\\\").replace("\"", "\\\"")}\")"
    val annotation = JavaPsiFacade.getElementFactory(project).createAnnotationFromText(text, owner)
    val added = modifiers.addAfter(annotation, null)
    JavaCodeStyleManager.getInstance(project).shortenClassReferences(added)
  }
}

/** Adds `@Primary` to a bean class or a `@Bean` method. The bean can be in a different file. */
class MarkPrimaryFix(bean: PsiElement, private val beanName: String) : LocalQuickFix {
  private val pointer = SmartPointerManager.createPointer(bean)

  override fun getFamilyName(): String = SpringBundle.message("fix.mark.primary.family")

  override fun getName(): String = SpringBundle.message("fix.mark.primary", beanName)

  override fun startInWriteAction(): Boolean = true

  override fun getElementToMakeWritable(currentFile: com.intellij.psi.PsiFile): PsiElement? = pointer.element

  override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

  override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
    val owner = pointer.element as? PsiModifierListOwner ?: return
    val modifiers = owner.modifierList ?: return
    if (modifiers.hasAnnotation(SpringNames.PRIMARY)) return
    val added = modifiers.addAnnotation(SpringNames.PRIMARY)
    JavaCodeStyleManager.getInstance(project).shortenClassReferences(added)
  }
}
