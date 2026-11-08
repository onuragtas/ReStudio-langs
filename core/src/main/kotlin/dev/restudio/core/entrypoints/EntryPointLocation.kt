package dev.restudio.core.entrypoints

import com.intellij.execution.PsiLocation
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.psi.PsiElement

/**
 * Points to one [entryPoint] inside [element].
 *
 * A TextMate file is one PSI leaf. Thus the element alone does not tell which entry point the user clicked,
 * and its text offset is the start of the file. Navigation opens the file at [EntryPoint.offset].
 */
class EntryPointLocation(element: PsiElement, val entryPoint: EntryPoint) : PsiLocation<PsiElement>(element) {
  override fun getOpenFileDescriptor(): OpenFileDescriptor? =
    virtualFile?.let { OpenFileDescriptor(project, it, entryPoint.offset) }
}
