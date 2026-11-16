package dev.restudio.go

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.util.DiffUtil
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.plugins.textmate.TextMateFileType

class GoFileTypeTest : BasePlatformTestCase() {
  fun `test the file name alone gives the TextMate file type`() {
    assertSame(TextMateFileType.INSTANCE, FileTypeManager.getInstance().getFileTypeByFileName("main.go"))
  }

  fun `test a diff of a file that is not on disk has syntax colors`() {
    val text = "package main\n\nfunc main() {}\n"
    val path = LocalFilePath("/restudio-missing/main.go", false)
    val content = DiffContentFactory.getInstance().createFromBytes(project, text.toByteArray(), path) as DocumentContent
    val highlighter = DiffUtil.initEditorHighlighter(project, content, text)!!
    val iterator = highlighter.createIterator(0)
    assertTrue(iterator.tokenType.toString(), iterator.tokenType.javaClass.name.contains("TextMate"))
  }
}
