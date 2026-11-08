package dev.restudio.dotnet

import dev.restudio.dotnet.test.TrxReport
import jetbrains.buildServer.messages.serviceMessages.ServiceMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reads the recorded TRX files in `src/test/resources/trx`. */
class TrxReportTest {
  private fun messages(resource: String): List<ServiceMessage> {
    val trx = javaClass.getResource("/trx/$resource")!!.readText()
    return TrxReport.messages(trx) { className, method -> "dotnettest:///work#$className.$method" }.map { ServiceMessage.parse(it.toString())!! }
  }

  private fun List<ServiceMessage>.render() = joinToString("\n") { m ->
    listOfNotNull(m.messageName, m.attributes["name"], m.attributes["nodeId"]?.substringBefore(':'), m.attributes["parentNodeId"]?.substringBefore(':'),
                  m.attributes["message"]?.lineSequence()?.first(), m.attributes["locationHint"]).joinToString(" | ")
  }

  @Test
  fun `a failed test with its message and stack`() {
    val messages = messages("fail.trx")
    assertEquals(
      """
      testSuiteStarted | FailProbeTests | class | 0
      testStarted | ProbeFails | test | class | dotnettest:///work#Shop.Domain.Tests.FailProbeTests.ProbeFails
      testFailed | ProbeFails | test | Assert.Equal() Failure: Values differ
      testFinished | ProbeFails | test
      testSuiteFinished | FailProbeTests | class
      """.trimIndent(),
      messages.filter { it.messageName != "testStdOut" }.render(),
    )
    val details = messages.single { it.messageName == "testFailed" }.attributes["details"]!!
    assertTrue(details, details.contains("FailProbeTests.cs:line 8"))
  }

  @Test
  fun `every test of a full run passes`() {
    val messages = messages("all.trx")
    val started = messages.filter { it.messageName == "testStarted" }
    assertTrue("no tests", started.isNotEmpty())
    assertEquals(emptyList<ServiceMessage>(), messages.filter { it.messageName == "testFailed" })
    assertEquals(started.size, messages.count { it.messageName == "testFinished" })
    assertEquals(messages.count { it.messageName == "testSuiteStarted" }, messages.count { it.messageName == "testSuiteFinished" })
  }
}
