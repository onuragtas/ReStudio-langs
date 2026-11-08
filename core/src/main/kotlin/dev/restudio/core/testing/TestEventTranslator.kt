package dev.restudio.core.testing

import com.intellij.execution.testframework.sm.ServiceMessageBuilder

/** One result of [TestEventTranslator.translate]. */
sealed interface TestOutput {
  /** A TeamCity service message for the test tree. It must carry `nodeId` and `parentNodeId`. */
  data class Message(val builder: ServiceMessageBuilder) : TestOutput

  /** Text for the console. It does not go into a test node. */
  data class Text(val text: String) : TestOutput
}

/**
 * Translates one output line of a test runner into the events of the IntelliJ test tree.
 *
 * Each language plugin gives one translator for its runner format, for example `go test -json`.
 * The test tree is ID based: each message carries `nodeId` and `parentNodeId`. The root node ID is [ROOT_ID].
 * A translator keeps the state of one run. Create a new one for each run.
 */
interface TestEventTranslator {
  /** Returns `null` when [line] is not in the runner format. Then the console shows the line as it is. */
  fun translate(line: String): List<TestOutput>?

  companion object {
    const val ROOT_ID: String = "0"
  }
}
