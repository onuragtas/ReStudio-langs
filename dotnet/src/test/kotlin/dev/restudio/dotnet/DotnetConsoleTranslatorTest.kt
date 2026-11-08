package dev.restudio.dotnet

import dev.restudio.core.testing.TestOutput
import dev.restudio.dotnet.test.DotnetConsoleTranslator
import jetbrains.buildServer.messages.serviceMessages.ServiceMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feeds the recorded console output in `src/test/resources/console/live.txt` (pass, fail, skip and theory rows). */
class DotnetConsoleTranslatorTest {
  private fun translate(translator: DotnetConsoleTranslator): Pair<List<ServiceMessage>, List<String>> {
    val messages = mutableListOf<ServiceMessage>()
    val plain = mutableListOf<String>()
    javaClass.getResource("/console/live.txt")!!.readText().lines().forEach { line ->
      val outputs = translator.translate(line)
      if (outputs == null) plain += line
      outputs?.forEach { if (it is TestOutput.Message) messages += ServiceMessage.parse(it.builder.toString())!! }
    }
    return messages to plain
  }

  @Test
  fun `the console rows fill the tree`() {
    val translator = DotnetConsoleTranslator { className, method -> "dotnettest:///work#$className.$method" }
    val (messages, plain) = translate(translator)
    assertTrue(translator.sawResults)
    val rendered = messages.joinToString("\n") { m ->
      listOfNotNull(m.messageName, m.attributes["name"], m.attributes["parentNodeId"]?.substringBefore(':'),
                    m.attributes["message"]?.lineSequence()?.first()?.takeIf { it.isNotEmpty() }, m.attributes["duration"],
                    m.attributes["locationHint"]?.substringAfter('#')).joinToString(" | ")
    }
    assertEquals(
      """
      testSuiteStarted | LiveProbeTests | 0
      testStarted | Skipped | class | Shop.Domain.Tests.LiveProbeTests.Skipped
      testIgnored | Skipped
      testFinished | Skipped | 1
      testSuiteStarted | ProductTests | 0
      testStarted | PriceWithTaxAddsTheRate | class | Shop.Domain.Tests.ProductTests.PriceWithTaxAddsTheRate
      testFinished | PriceWithTaxAddsTheRate | 5
      testStarted | Positive(value: 2) | class | Shop.Domain.Tests.LiveProbeTests.Positive
      testFinished | Positive(value: 2) | 4
      testStarted | Positive(value: 1) | class | Shop.Domain.Tests.LiveProbeTests.Positive
      testFinished | Positive(value: 1) | 1
      testStarted | Fails | class | Shop.Domain.Tests.LiveProbeTests.Fails
      testFailed | Fails | Assert.Equal() Failure: Values differ
      testFinished | Fails | 2
      testSuiteFinished | LiveProbeTests
      testSuiteFinished | ProductTests
      """.trimIndent(),
      rendered,
    )
    val failed = messages.single { it.messageName == "testFailed" }
    assertEquals("Assert.Equal() Failure: Values differ\nExpected: 1\nActual:   2", failed.attributes["message"])
    assertTrue(failed.attributes["details"], failed.attributes["details"]!!.startsWith("at Shop.Domain.Tests.LiveProbeTests.Fails() in /work/tests/Shop.Domain.Tests/LiveProbeTests.cs:line 8"))
    // The build lines and the xUnit lines stay in the console.
    assertTrue(plain.any { it.contains("Shop.Domain.Tests -> /work") })
  }

  @Test
  fun `a run without result rows leaves the tree to TRX`() {
    val translator = DotnetConsoleTranslator()
    assertEquals(null, translator.translate("  Determining projects to restore..."))
    assertFalse(translator.sawResults)
  }
}
