package dev.restudio.go

import dev.restudio.go.test.GoTestJsonTranslator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feeds the recorded `go test -json` output (see `src/test/resources/gotest`) through the translator. */
class GoTestJsonTranslatorTest {
  private fun tree(resource: String): TestTree {
    val lines = javaClass.getResource("/gotest/$resource")!!.readText().lineSequence().filter { it.isNotEmpty() }
    return TestTree(GoTestJsonTranslator()).feed(lines)
  }

  @Test
  fun `passing tests`() {
    val tree = tree("all.jsonl")
    assertEquals(emptyList<String>(), tree.problems)
    assertEquals(emptyList<String>(), tree.unfinished())
    assertEquals(
      """
      example.com/shop/lib/greet: done
        TestHello: done
      example.com/shop/api/cmd/server: done
        TestHandler: done
      """.trimIndent(),
      tree.render(),
    )
  }

  @Test
  fun `subtests, skip, panic and build failure`() {
    val tree = tree("edge.jsonl")
    assertEquals(emptyList<String>(), tree.problems)
    assertEquals(emptyList<String>(), tree.unfinished())
    assertEquals(
      """
      example.com/trace/broken: done
        Build failed: failed [# example.com/trace/broken [example.com/trace/broken.test]
      broken/broken_test.go:5:28: undefined: undefined]
      example.com/trace/ok: done
        TestTable: failed
          one: done
          two_words: failed [ok_test.go:10: bad two words]
        TestSkip: ignored
        TestPanic: failed [panic: boom [recovered, repanicked]]
      """.trimIndent(),
      tree.render(),
    )
  }

  @Test
  fun `package lines go to the console`() {
    val text = tree("edge.jsonl").text.toString()
    assertTrue(text, text.contains("FAIL\texample.com/trace/ok\t0.450s"))
    assertTrue(text, text.contains("undefined: undefined"))
  }

  @Test
  fun `a plain line is not translated`() {
    assertEquals(null, GoTestJsonTranslator().translate("ok  \texample.com/x\t0.1s"))
  }
}
