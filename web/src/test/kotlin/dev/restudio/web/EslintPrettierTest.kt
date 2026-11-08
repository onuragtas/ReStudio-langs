package dev.restudio.web

import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.formatting.service.FormattingServiceUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.restudio.web.ide.Prettier
import dev.restudio.web.ide.PrettierFormattingService
import dev.restudio.web.lsp.EslintLanguageClient
import dev.restudio.web.lsp.EslintTypeScriptMatcher
import dev.restudio.web.lsp.EslintTypeScriptReactMatcher
import java.nio.charset.StandardCharsets
import java.nio.file.Path

class EslintPrettierTest : BasePlatformTestCase() {
  private val web = Path.of("../testdata/web").toAbsolutePath().normalize()

  private fun sampleFile(path: String): VirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(web.resolve(path))!!

  fun `test ESLint starts only where ESLint is installed`() {
    assertTrue(EslintTypeScriptMatcher().match(sampleFile("vue/src/main.ts"), project))
    assertFalse(EslintTypeScriptReactMatcher().match(sampleFile("react/src/main.tsx"), project))
    assertFalse(EslintTypeScriptMatcher().match(sampleFile("vue/src/App.vue"), project))
    // The settings of the LSP probe, case eslint-vue-ts.
    assertEquals("on", EslintLanguageClient.SETTINGS["validate"])
    assertEquals(true, EslintLanguageClient.SETTINGS["useFlatConfig"])
  }

  fun `test Prettier is found only in a project that has it`() {
    assertNotNull(Prettier.find(sampleFile("angular/src/app/app.ts")))
    assertNull(Prettier.find(sampleFile("react/src/main.tsx")))
  }

  fun `test the platform picks Prettier for a file of a Prettier project`() {
    val file = PsiManager.getInstance(project).findFile(sampleFile("angular/src/app/app.ts"))!!
    assertTrue(FormattingServiceUtil.findService(file, true, true) is PrettierFormattingService)
  }

  fun `test Prettier formats with the project settings`() {
    val file = sampleFile("angular/src/app/app.ts")
    val handler = CapturingProcessHandler(Prettier.find(file)!!.commandLine(file.parent.path + "/probe.ts"))
    handler.processInput.use { it.write("const  a = {b:1,   c:\"x\"}\n".toByteArray(StandardCharsets.UTF_8)) }
    val output = handler.runProcess(60_000)
    assertEquals(output.stderr, 0, output.exitCode)
    // The sample .prettierrc has singleQuote.
    assertEquals("const a = { b: 1, c: 'x' };\n", output.stdout)
  }
}
