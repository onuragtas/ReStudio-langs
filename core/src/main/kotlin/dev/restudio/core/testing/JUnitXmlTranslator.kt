package dev.restudio.core.testing

import com.intellij.execution.testframework.sm.ServiceMessageBuilder
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Translates a JUnit XML report into the events of the test tree.
 *
 * A runner can write the report to the console, for example `vitest --reporter=junit`. Then the translator collects the
 * lines from `<?xml`, `<testsuites` or `<testsuite` to the end tag of the root, and translates the full document.
 * A plugin that reads the report from a file calls [translateReport].
 *
 * Each `<testsuite>` is a suite node, and a nested `<testsuite>` is a child suite. Each `<testcase>` is a test node.
 * `<failure>` and `<error>` fail the test, `<skipped>` ignores it. `time` is in seconds.
 * `<system-out>` and `<system-err>` of a test case go to the output of the test.
 * [locationHint] gives the hint for a test from its `classname`, its `name` and its `file` attribute.
 */
class JUnitXmlTranslator(
  private val locationHint: (className: String, name: String, file: String?) -> String? = { _, _, _ -> null },
) : TestEventTranslator {
  private val buffer = StringBuilder()
  private var rootTag: String? = null
  private var nextId = 0

  override fun translate(line: String): List<TestOutput>? {
    val text = line.trimEnd('\n', '\r')
    if (rootTag == null) {
      val trimmed = text.trimStart()
      if (!trimmed.startsWith("<?xml") && !trimmed.startsWith("<testsuites") && !trimmed.startsWith("<testsuite")) return null
      rootTag = if (trimmed.startsWith("<testsuites")) "testsuites" else if (trimmed.startsWith("<testsuite")) "testsuite" else ""
    }
    buffer.append(text).append('\n')
    if (rootTag == "") rootTag = ROOT.find(buffer)?.groupValues?.get(1) ?: return emptyList()
    if (!isComplete()) return emptyList()
    val document = buffer.toString()
    buffer.setLength(0)
    rootTag = null
    return translateReport(document)
  }

  /** Translates a full JUnit XML [report]. It returns no output when the report is not valid XML. */
  fun translateReport(report: String): List<TestOutput> {
    val root = runCatching { parse(report) }.getOrNull() ?: return emptyList()
    val result = mutableListOf<TestOutput>()
    when (root.tagName) {
      "testsuites" -> root.children("testsuite").forEach { suite(it, TestEventTranslator.ROOT_ID, result) }
      "testsuite" -> suite(root, TestEventTranslator.ROOT_ID, result)
    }
    return result
  }

  private fun isComplete(): Boolean {
    val tag = rootTag ?: return false
    val starts = Regex("<$tag\\b[^>]*>").findAll(buffer).toList()
    if (starts.isEmpty()) return false
    if (starts.first().value.endsWith("/>")) return true
    val opened = starts.count { !it.value.endsWith("/>") }
    return opened == Regex("</$tag\\s*>").findAll(buffer).count()
  }

  private fun suite(element: Element, parent: String, result: MutableList<TestOutput>) {
    val name = element.getAttribute("name").ifEmpty { element.getAttribute("classname") }
    val id = "suite:${nextId++}"
    result += message(ServiceMessageBuilder.testSuiteStarted(name), id, parent)
    for (child in element.children()) {
      when (child.tagName) {
        "testsuite" -> suite(child, id, result)
        "testcase" -> case(child, id, result)
      }
    }
    result += message(ServiceMessageBuilder.testSuiteFinished(name), id)
  }

  private fun case(element: Element, parent: String, result: MutableList<TestOutput>) {
    val name = element.getAttribute("name")
    val className = element.getAttribute("classname")
    val id = "test:${nextId++}"
    val started = ServiceMessageBuilder.testStarted(name)
    locationHint(className, name, element.getAttribute("file").ifEmpty { null })?.let { started.addAttribute("locationHint", it) }
    result += message(started, id, parent)
    element.children("system-out").text()?.let { result += message(ServiceMessageBuilder.testStdOut(name).addAttribute("out", it), id) }
    element.children("system-err").text()?.let { result += message(ServiceMessageBuilder.testStdErr(name).addAttribute("out", it), id) }
    val problem = element.children().firstOrNull { it.tagName == "failure" || it.tagName == "error" }
    val skipped = element.children("skipped").firstOrNull()
    when {
      problem != null -> {
        val failed = ServiceMessageBuilder.testFailed(name)
          .addAttribute("message", problem.getAttribute("message").ifEmpty { problem.getAttribute("type") })
          .addAttribute("details", problem.textContent.trim())
        if (problem.tagName == "error") failed.addAttribute("error", "true")
        result += message(failed, id)
      }
      skipped != null -> result += message(ServiceMessageBuilder.testIgnored(name).addAttribute("message", skipped.getAttribute("message")), id)
    }
    val finished = ServiceMessageBuilder.testFinished(name)
    durationMillis(element.getAttribute("time"))?.let { finished.addAttribute("duration", it.toString()) }
    result += message(finished, id)
  }

  private fun message(builder: ServiceMessageBuilder, id: String, parent: String? = null): TestOutput.Message {
    builder.addAttribute("nodeId", id)
    if (parent != null) builder.addAttribute("parentNodeId", parent)
    return TestOutput.Message(builder)
  }

  /** Reads `0.012`, `1,234.5` and `0,012` seconds. */
  private fun durationMillis(seconds: String): Long? {
    val text = if ('.' in seconds) seconds.replace(",", "") else seconds.replace(',', '.')
    return text.trim().toDoubleOrNull()?.let { (it * 1000).toLong() }
  }

  private fun Element.children(tag: String? = null): List<Element> {
    val nodes = childNodes
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { tag == null || it.tagName == tag }
  }

  private fun List<Element>.text(): String? =
    joinToString("") { it.textContent }.takeIf { it.isNotBlank() }?.let { it.trimEnd() + "\n" }

  private companion object {
    val ROOT = Regex("<(testsuites|testsuite)\\b")

    fun parse(xml: String): Element {
      val factory = DocumentBuilderFactory.newInstance()
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      factory.isExpandEntityReferences = false
      return factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
    }
  }
}
