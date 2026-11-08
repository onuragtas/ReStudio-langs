package dev.restudio.core.entrypoints

import com.intellij.execution.PsiLocation
import com.intellij.psi.PsiElement

/**
 * Points to one [entryPoint] inside [element].
 *
 * A TextMate file is one PSI leaf. Thus the element alone does not tell which entry point the user clicked.
 */
class EntryPointLocation(element: PsiElement, val entryPoint: EntryPoint) : PsiLocation<PsiElement>(element)
