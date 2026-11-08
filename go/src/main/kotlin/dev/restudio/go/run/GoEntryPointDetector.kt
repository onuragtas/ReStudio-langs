package dev.restudio.go.run

import dev.restudio.core.entrypoints.EntryPoint
import dev.restudio.core.entrypoints.EntryPointDetector

/**
 * Finds `func main()` in `package main` and `func TestX(t *testing.T)` in `_test.go` files.
 *
 * It reads only top-level declarations at the start of a line, as `gofmt` writes them.
 */
object GoEntryPointDetector : EntryPointDetector {
  private val packageClause = Regex("""^package\s+(\w+)""", RegexOption.MULTILINE)
  private val mainFunc = Regex("""^func\s+main\s*\(\s*\)""", RegexOption.MULTILINE)
  private val testFunc = Regex("""^func\s+(Test\w*)\s*\(\s*\w+\s+\*testing\.T\s*\)""", RegexOption.MULTILINE)

  override fun detect(fileName: String, text: CharSequence): List<EntryPoint> {
    val packageName = packageClause.find(text)?.groupValues?.get(1) ?: return emptyList()
    if (fileName.endsWith("_test.go")) {
      return testFunc.findAll(text).map { EntryPoint(EntryPoint.Kind.TEST, it.groupValues[1], it.range.first) }.toList()
    }
    if (packageName != "main") return emptyList()
    return mainFunc.findAll(text).map { EntryPoint(EntryPoint.Kind.MAIN, "main", it.range.first) }.toList()
  }
}
