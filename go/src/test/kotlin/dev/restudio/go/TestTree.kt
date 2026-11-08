package dev.restudio.go

import dev.restudio.core.testing.TestEventTranslator
import dev.restudio.core.testing.TestOutput
import jetbrains.buildServer.messages.serviceMessages.ServiceMessage

/** Builds a text picture of the test tree from translated messages, one node on each line. */
class TestTree(private val translator: TestEventTranslator) {
  private class Node(val name: String, val parent: String?) {
    var status = "running"
    var message: String? = null
  }

  private val nodes = LinkedHashMap<String, Node>()
  val text = StringBuilder()
  val problems = mutableListOf<String>()

  fun feed(lines: Sequence<String>): TestTree {
    for (line in lines) {
      val outputs = translator.translate(line)
      if (outputs == null) {
        text.append(line).append('\n')
        continue
      }
      outputs.forEach(::apply)
    }
    return this
  }

  private fun apply(output: TestOutput) {
    when (output) {
      is TestOutput.Text -> text.append(output.text)
      is TestOutput.Message -> {
        val msg = ServiceMessage.parse(output.builder.toString()) ?: error("bad message: ${output.builder}")
        val id = msg.attributes["nodeId"] ?: return problems.plusAssign("no nodeId: $msg")
        when (msg.messageName) {
          "testSuiteStarted", "testStarted" -> {
            val parent = msg.attributes["parentNodeId"]
            if (parent != TestEventTranslator.ROOT_ID && nodes[parent]?.status != "running") problems += "parent not running: $msg"
            nodes[id] = Node(msg.attributes.getValue("name"), parent)
          }
          "testFailed" -> nodes.getValue(id).apply { status = "failed"; message = msg.attributes["message"] }
          "testIgnored" -> nodes.getValue(id).status = "ignored"
          "testFinished", "testSuiteFinished" -> nodes.getValue(id).let { if (it.status == "running") it.status = "done" }
          "testStdOut", "testStdErr" -> if (id !in nodes) problems += "output for unknown node: $msg"
          else -> problems += "unexpected: $msg"
        }
      }
    }
  }

  /** Returns lines such as `  TestHello: done`. The indent shows the depth. */
  fun render(): String = nodes.entries.joinToString("\n") { (id, node) ->
    val depth = generateSequence(node.parent) { nodes[it]?.parent }.count { it != TestEventTranslator.ROOT_ID }
    "  ".repeat(depth) + node.name + ": " + node.status + (node.message?.takeIf { it.isNotEmpty() }?.let { " [$it]" } ?: "")
  }

  fun unfinished(): List<String> = nodes.filterValues { it.status == "running" }.keys.toList()
}
