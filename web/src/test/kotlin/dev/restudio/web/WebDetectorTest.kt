package dev.restudio.web

import dev.restudio.web.run.NpmScriptDetector
import dev.restudio.web.test.VitestEntryPointDetector
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class WebDetectorTest {
  private fun lineOf(text: String, offset: Int) = text.substring(offset, text.indexOf('\n', offset).let { if (it < 0) text.length else it }).trim()

  @Test
  fun `npm scripts of the React sample`() {
    val text = Files.readString(Path.of("../testdata/web/react/package.json"))
    assertEquals(
      listOf("dev" to "\"dev\": \"vite\",", "build" to "\"build\": \"tsc -b && vite build\",", "lint" to "\"lint\": \"oxlint\",", "preview" to "\"preview\": \"vite preview\""),
      NpmScriptDetector.detect("package.json", text).map { it.name to lineOf(text, it.offset) },
    )
  }

  @Test
  fun `a script value with braces and a nested object end the scan`() {
    val text = "{\"scripts\": {\"a\": \"echo {x}\", \"b\": \"y\"}, \"other\": {\"c\": \"z\"}}"
    assertEquals(listOf("a", "b"), NpmScriptDetector.detect("package.json", text).map { it.name })
  }

  @Test
  fun `vitest names of the Vue sample`() {
    val text = Files.readString(Path.of("../testdata/web/vue/src/lib/total.test.ts"))
    assertEquals(
      listOf("total" to "describe('total', () => {", "total > adds the prices" to "it('adds the prices', () => {", "total > returns zero for no prices" to "it('returns zero for no prices', () => {"),
      VitestEntryPointDetector.detect("total.test.ts", text).map { it.name to lineOf(text, it.offset) },
    )
  }

  @Test
  fun `modifiers, nesting and strings with parentheses`() {
    val text = """
      describe("outer (a)", () => {
        describe.each([1])('inner', () => {
          test.only(`works`, () => { expect(")").toBe(")") })
        })
        it('after', () => {})
      })
    """.trimIndent()
    assertEquals(
      listOf("outer (a)", "outer (a) > inner", "outer (a) > inner > works", "outer (a) > after"),
      VitestEntryPointDetector.detect("x.spec.tsx", text).map { it.name },
    )
    assertEquals(emptyList<String>(), VitestEntryPointDetector.detect("total.ts", text).map { it.name })
  }
}
