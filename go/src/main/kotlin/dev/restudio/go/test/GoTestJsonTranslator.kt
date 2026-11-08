package dev.restudio.go.test

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.restudio.core.testing.TestEventTranslator
import dev.restudio.core.testing.TestOutput
import dev.restudio.go.GoBundle
import com.intellij.execution.testframework.sm.ServiceMessageBuilder

/**
 * Translates the `go test -json` output into the test tree.
 *
 * Each package is a suite. Each test is a node under its package. A subtest such as `TestTable/one` is a node
 * under its parent test. A package that does not compile gets one failed node with the build output.
 * A package without tests does not get a suite.
 */
class GoTestJsonTranslator : TestEventTranslator {
  private val startedPackages = HashSet<String>()
  private val runningTests = HashMap<String, LinkedHashSet<String>>()
  private val failureLines = HashMap<String, StringBuilder>()
  private val buildOutput = HashMap<String, StringBuilder>()

  override fun translate(line: String): List<TestOutput>? {
    val event = parse(line) ?: return null
    val action = event.string("Action") ?: return null
    val pkg = event.string("Package")
    val test = event.string("Test")
    val result = mutableListOf<TestOutput>()
    when (action) {
      "build-output" -> {
        val output = event.string("Output").orEmpty()
        event.string("ImportPath")?.let { buildOutput.getOrPut(it.substringBefore(" [")) { StringBuilder() }.append(output) }
        result += TestOutput.Text(output)
      }
      "run" -> if (pkg != null && test != null) {
        startPackage(pkg, result)
        startTest(pkg, test, result)
      }
      "output" -> output(pkg, test, event, result)
      "pass" -> if (pkg != null) {
        if (test != null) finishTest(pkg, test, event, result) else finishPackage(pkg, result)
      }
      "skip" -> if (pkg != null) {
        if (test != null) {
          result += message(ServiceMessageBuilder.testIgnored(test), testId(pkg, test))
          finishTest(pkg, test, event, result)
        }
        else {
          finishPackage(pkg, result)
        }
      }
      "fail" -> if (pkg != null) {
        if (test != null) {
          failTest(pkg, test, event, result)
        }
        else {
          if (event.has("FailedBuild")) buildFailure(pkg, result)
          finishPackage(pkg, result)
        }
      }
      else -> {
        // "start", "pause", "cont", "build-fail": the tree does not need them.
      }
    }
    return result
  }

  private fun output(pkg: String?, test: String?, event: JsonObject, result: MutableList<TestOutput>) {
    val output = event.string("Output").orEmpty()
    if (pkg == null || test == null || test !in runningTests[pkg].orEmpty()) {
      result += TestOutput.Text(output)
      return
    }
    // The test tree shows the start of a test. The "=== RUN" lines only repeat it.
    if (event.string("OutputType") == "frame" && output.startsWith("=== ")) return
    val id = testId(pkg, test)
    val isError = event.string("OutputType") == "error"
    if (isError || output.startsWith("panic: ")) failureLines.getOrPut(id) { StringBuilder() }.append(output.trim()).append('\n')
    val builder = if (isError) ServiceMessageBuilder.testStdErr(test) else ServiceMessageBuilder.testStdOut(test)
    result += message(builder.addAttribute("out", output), id)
  }

  private fun startPackage(pkg: String, result: MutableList<TestOutput>) {
    if (!startedPackages.add(pkg)) return
    result += message(ServiceMessageBuilder.testSuiteStarted(pkg), packageId(pkg), TestEventTranslator.ROOT_ID)
  }

  private fun startTest(pkg: String, test: String, result: MutableList<TestOutput>) {
    val tests = runningTests.getOrPut(pkg) { LinkedHashSet() }
    // A subtest name can hold "/" in its own name. Thus look for the nearest running parent.
    val parent = generateSequence(test.substringBeforeLast('/', "")) { it.substringBeforeLast('/', "").ifEmpty { null } }
      .firstOrNull { it.isNotEmpty() && it in tests }
    tests += test
    val builder = ServiceMessageBuilder.testStarted(test.substringAfterLast('/'))
    result += message(builder, testId(pkg, test), parent?.let { testId(pkg, it) } ?: packageId(pkg))
  }

  private fun failTest(pkg: String, test: String, event: JsonObject, result: MutableList<TestOutput>) {
    val id = testId(pkg, test)
    val text = failureLines.remove(id)?.toString()?.trim().orEmpty()
    result += message(ServiceMessageBuilder.testFailed(test).addAttribute("message", text), id)
    finishTest(pkg, test, event, result)
  }

  private fun finishTest(pkg: String, test: String, event: JsonObject, result: MutableList<TestOutput>) {
    if (runningTests[pkg]?.remove(test) != true) return
    failureLines.remove(testId(pkg, test))
    val builder = ServiceMessageBuilder.testFinished(test)
    event.get("Elapsed")?.asDouble?.let { builder.addAttribute("duration", (it * 1000).toLong().toString()) }
    result += message(builder, testId(pkg, test))
  }

  private fun buildFailure(pkg: String, result: MutableList<TestOutput>) {
    startPackage(pkg, result)
    val name = GoBundle.message("test.build.failed")
    val id = "${packageId(pkg)}#build"
    result += message(ServiceMessageBuilder.testStarted(name), id, packageId(pkg))
    val details = buildOutput.remove(pkg)?.toString()?.trim().orEmpty()
    result += message(ServiceMessageBuilder.testFailed(name).addAttribute("message", details), id)
    result += message(ServiceMessageBuilder.testFinished(name), id)
  }

  private fun finishPackage(pkg: String, result: MutableList<TestOutput>) {
    if (pkg !in startedPackages) return
    // The process can stop a test without its "fail" event, for example after a panic in another goroutine.
    runningTests.remove(pkg)?.reversed()?.forEach { result += message(ServiceMessageBuilder.testFinished(it), testId(pkg, it)) }
    result += message(ServiceMessageBuilder.testSuiteFinished(pkg), packageId(pkg))
  }

  private fun message(builder: ServiceMessageBuilder, nodeId: String, parentNodeId: String? = null): TestOutput.Message {
    builder.addAttribute("nodeId", nodeId)
    if (parentNodeId != null) builder.addAttribute("parentNodeId", parentNodeId)
    return TestOutput.Message(builder)
  }

  private fun packageId(pkg: String) = pkg

  private fun testId(pkg: String, test: String) = "$pkg#$test"

  private fun parse(line: String): JsonObject? {
    val text = line.trim()
    if (!text.startsWith("{")) return null
    return try {
      JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
    }
    catch (_: com.google.gson.JsonParseException) {
      null
    }
  }

  private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
}
