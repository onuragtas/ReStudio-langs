package dev.restudio.go

import dev.restudio.core.entrypoints.EntryPoint
import dev.restudio.go.run.GoEntryPointDetector
import org.junit.Assert.assertEquals
import org.junit.Test

class GoEntryPointDetectorTest {
  @Test
  fun `finds main in package main`() {
    val text = "package main\n\nimport \"fmt\"\n\nfunc main() {\n\tfmt.Println()\n}\n"
    assertEquals(listOf(EntryPoint(EntryPoint.Kind.MAIN, "main", text.indexOf("func main"))), GoEntryPointDetector.detect("main.go", text))
  }

  @Test
  fun `ignores main outside package main`() {
    assertEquals(emptyList<EntryPoint>(), GoEntryPointDetector.detect("lib.go", "package greet\n\nfunc main() {}\n"))
  }

  @Test
  fun `finds test functions only in test files`() {
    val text = "package greet\n\nimport \"testing\"\n\nfunc TestHello(t *testing.T) {}\n\nfunc helper(t *testing.T) {}\n\nfunc TestBye(t *testing.T) {}\n"
    assertEquals(listOf("TestHello", "TestBye"), GoEntryPointDetector.detect("greet_test.go", text).map { it.name })
    assertEquals(emptyList<EntryPoint>(), GoEntryPointDetector.detect("greet.go", text))
  }

  @Test
  fun `ignores benchmarks and indented declarations`() {
    val text = "package greet\n\nimport \"testing\"\n\nfunc BenchmarkHello(b *testing.B) {}\n\n\tfunc TestNested(t *testing.T) {}\n"
    assertEquals(emptyList<EntryPoint>(), GoEntryPointDetector.detect("greet_test.go", text))
  }
}
