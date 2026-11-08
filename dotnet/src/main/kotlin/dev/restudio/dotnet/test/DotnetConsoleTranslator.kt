package dev.restudio.dotnet.test

import com.intellij.execution.testframework.sm.ServiceMessageBuilder
import dev.restudio.core.testing.TestEventTranslator
import dev.restudio.core.testing.TestOutput

/**
 * Translates the `dotnet test` console logger output (`console;verbosity=normal`, English) into the test tree.
 *
 * VSTest prints one result line for each test, for example `  Passed Shop.Tests.ProductTests.Adds [5 ms]`.
 * A failed test has `  Error Message:` and `  Stack Trace:` blocks after its line. The blocks end at an empty line.
 * Each test class is a suite. [locationHint] gives the hint for a class and a method.
 *
 * [sawResults] tells the run state if the TRX file must fill the tree instead.
 */
class DotnetConsoleTranslator(private val locationHint: (className: String, method: String) -> String? = { _, _ -> null }) : TestEventTranslator {
  @Volatile
  var sawResults: Boolean = false
    private set

  private val startedSuites = LinkedHashSet<String>()
  private var failure: Failure? = null
  private var nextId = 0

  private class Failure(val id: String, val name: String, val duration: Long) {
    val message = StringBuilder()
    val details = StringBuilder()
    var section: StringBuilder? = null
  }

  override fun translate(line: String): List<TestOutput>? {
    val text = line.trimEnd('\n', '\r')
    val result = mutableListOf<TestOutput>()
    failure?.let { open ->
      when {
        text.isEmpty() || RESULT.matches(text) || SUMMARY.matches(text) -> finishFailure(result)
        text == "  Error Message:" -> { open.section = open.message; return emptyList() }
        text == "  Stack Trace:" -> { open.section = open.details; return emptyList() }
        else -> { open.section?.append(text.trim())?.append('\n'); return emptyList() }
      }
    }
    if (SUMMARY.matches(text)) {
      startedSuites.forEach { result += message(ServiceMessageBuilder.testSuiteFinished(it.substringAfterLast('.')), suiteId(it)) }
      startedSuites.clear()
      return result.ifEmpty { null }
    }
    val match = RESULT.matchEntire(text) ?: return result.ifEmpty { null }
    sawResults = true
    val (outcome, fullName, duration) = match.destructured
    val methodPath = fullName.substringBefore('(')
    val className = methodPath.substringBeforeLast('.')
    val method = methodPath.substringAfterLast('.')
    val testName = fullName.removePrefix("$className.")
    if (startedSuites.add(className)) {
      result += message(ServiceMessageBuilder.testSuiteStarted(className.substringAfterLast('.')), suiteId(className), TestEventTranslator.ROOT_ID)
    }
    val id = "test:${nextId++}"
    val started = ServiceMessageBuilder.testStarted(testName)
    locationHint(className, method)?.let { started.addAttribute("locationHint", it) }
    result += message(started, id, suiteId(className))
    val millis = durationMillis(duration)
    when (outcome) {
      "Passed" -> result += finished(testName, id, millis)
      "Skipped" -> {
        result += message(ServiceMessageBuilder.testIgnored(testName), id)
        result += finished(testName, id, millis)
      }
      else -> failure = Failure(id, testName, millis)
    }
    return result
  }

  private fun finishFailure(result: MutableList<TestOutput>) {
    val open = failure ?: return
    failure = null
    val failed = ServiceMessageBuilder.testFailed(open.name)
      .addAttribute("message", open.message.toString().trim())
      .addAttribute("details", open.details.toString().trim())
    result += message(failed, open.id)
    result += finished(open.name, open.id, open.duration)
  }

  private fun finished(name: String, id: String, millis: Long) =
    message(ServiceMessageBuilder.testFinished(name).addAttribute("duration", millis.toString()), id)

  private fun message(builder: ServiceMessageBuilder, id: String, parent: String? = null): TestOutput.Message {
    builder.addAttribute("nodeId", id)
    if (parent != null) builder.addAttribute("parentNodeId", parent)
    return TestOutput.Message(builder)
  }

  private fun suiteId(className: String) = "class:$className"

  /** Reads `5 ms`, `< 1 ms`, `2 s` and `1 m 3 s`. */
  private fun durationMillis(text: String): Long {
    var total = 0L
    for ((value, unit) in Regex("""(\d+)\s*(ms|s|m|h)\b""").findAll(text).map { it.destructured }) {
      total += value.toLong() * when (unit) { "h" -> 3_600_000; "m" -> 60_000; "s" -> 1000; else -> 1 }
    }
    return total
  }

  private companion object {
    val RESULT = Regex("""^ {2}(Passed|Failed|Skipped) (\S.*?) \[([^\]]+)]$""")
    val SUMMARY = Regex("""^(Test Run (Successful|Failed|Aborted)\.|Total tests:.*)$""")
  }
}
