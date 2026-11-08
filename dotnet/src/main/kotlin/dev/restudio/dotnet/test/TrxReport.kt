package dev.restudio.dotnet.test

import com.intellij.execution.testframework.sm.ServiceMessageBuilder
import dev.restudio.core.testing.TestEventTranslator
import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * Turns a TRX file of `dotnet test` into ID-based test tree messages.
 *
 * Each test class is a suite. A test gets a location hint when [locationHint] gives one for its
 * class and method. TRX is written at the end of the run, so the tree fills at the end.
 */
object TrxReport {
  fun messages(trx: String, locationHint: (className: String, method: String) -> String? = { _, _ -> null }): List<ServiceMessageBuilder> {
    val root = parse(trx)
    val classes = HashMap<String, Pair<String, String>>()
    for (unitTest in root.elements("UnitTest")) {
      val method = unitTest.elements("TestMethod").firstOrNull() ?: continue
      classes[unitTest.getAttribute("id")] = method.getAttribute("className") to method.getAttribute("name")
    }
    val result = mutableListOf<ServiceMessageBuilder>()
    val startedSuites = LinkedHashSet<String>()
    val results = root.elements("UnitTestResult").sortedBy { classes[it.getAttribute("testId")]?.first.orEmpty() }
    for (testResult in results) {
      val (className, method) = classes[testResult.getAttribute("testId")] ?: continue
      val suiteId = "class:$className"
      if (startedSuites.add(suiteId)) {
        startedSuites.filter { it != suiteId }.forEach { result += ServiceMessageBuilder.testSuiteFinished(it.substringAfterLast('.')).node(it) }
        startedSuites.retainAll(setOf(suiteId))
        result += ServiceMessageBuilder.testSuiteStarted(className.substringAfterLast('.')).node(suiteId, TestEventTranslator.ROOT_ID)
      }
      val testName = testResult.getAttribute("testName").removePrefix("$className.")
      val id = "test:${testResult.getAttribute("executionId")}"
      val started = ServiceMessageBuilder.testStarted(testName).node(id, suiteId)
      locationHint(className, method)?.let { started.addAttribute("locationHint", it) }
      result += started
      testResult.elements("StdOut").firstOrNull()?.textContent?.takeIf { it.isNotBlank() }?.let {
        result += ServiceMessageBuilder.testStdOut(testName).addAttribute("out", it.trimEnd() + "\n").node(id)
      }
      when (testResult.getAttribute("outcome")) {
        "Passed" -> {}
        "NotExecuted" -> result += ServiceMessageBuilder.testIgnored(testName).addAttribute("message", errorMessage(testResult)).node(id)
        else -> result += ServiceMessageBuilder.testFailed(testName)
          .addAttribute("message", errorMessage(testResult))
          .addAttribute("details", testResult.elements("StackTrace").firstOrNull()?.textContent?.trim().orEmpty())
          .node(id)
      }
      result += ServiceMessageBuilder.testFinished(testName).addAttribute("duration", durationMillis(testResult.getAttribute("duration")).toString()).node(id)
    }
    startedSuites.forEach { result += ServiceMessageBuilder.testSuiteFinished(it.substringAfterLast('.')).node(it) }
    return result
  }

  private fun errorMessage(testResult: Element) = testResult.elements("Message").firstOrNull()?.textContent?.trim().orEmpty()

  /** Reads `hh:mm:ss.fffffff`. */
  private fun durationMillis(text: String): Long {
    val parts = text.split(':')
    if (parts.size != 3) return 0
    return ((parts[0].toLongOrNull() ?: 0) * 3600 + (parts[1].toLongOrNull() ?: 0) * 60) * 1000 + ((parts[2].toDoubleOrNull() ?: 0.0) * 1000).toLong()
  }

  private fun ServiceMessageBuilder.node(id: String, parent: String? = null): ServiceMessageBuilder {
    addAttribute("nodeId", id)
    if (parent != null) addAttribute("parentNodeId", parent)
    return this
  }

  private fun parse(trx: String): Element {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    return factory.newDocumentBuilder().parse(InputSource(StringReader(trx.removePrefix("﻿")))).documentElement
  }

  private fun Element.elements(localName: String): List<Element> {
    val nodes = getElementsByTagName(localName)
    return (0 until nodes.length).map { nodes.item(it) as Element }
  }
}
