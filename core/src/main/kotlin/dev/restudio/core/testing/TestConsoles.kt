package dev.restudio.core.testing

import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.util.Key
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageVisitor

/** Connects a test runner process to the IntelliJ test tree through a [TestEventTranslator]. */
object TestConsoles {
  /**
   * Creates the test console for [processHandler] and attaches it.
   *
   * [translator] creates a new translator for each run. [locator] finds the source of a test from its location hint.
   */
  fun attach(
    frameworkName: String,
    configuration: RunConfiguration,
    executor: Executor,
    processHandler: ProcessHandler,
    locator: SMTestLocator?,
    translator: () -> TestEventTranslator,
  ): ConsoleView {
    val properties = object : SMTRunnerConsoleProperties(configuration, frameworkName, executor), SMCustomMessagesParsing {
      override fun createTestEventsConverter(testFrameworkName: String, consoleProperties: TestConsoleProperties): OutputToGeneralTestEventsConverter =
        TranslatingTestEventsConverter(testFrameworkName, consoleProperties, translator())

      override fun getTestLocator(): SMTestLocator? = locator
    }
    properties.isIdBasedTestTree = true
    return SMTestRunnerConnectionUtil.createAndAttachConsole(frameworkName, processHandler, properties)
  }
}

/** Sends each translated line to the test tree, and each other line to the console. */
class TranslatingTestEventsConverter(
  testFrameworkName: String,
  consoleProperties: TestConsoleProperties,
  private val translator: TestEventTranslator,
) : OutputToGeneralTestEventsConverter(testFrameworkName, consoleProperties) {
  override fun processServiceMessages(text: String, outputType: Key<*>, visitor: ServiceMessageVisitor): Boolean {
    val outputs = translator.translate(text) ?: return super.processServiceMessages(text, outputType, visitor)
    for (output in outputs) {
      when (output) {
        is TestOutput.Message -> super.processServiceMessages(output.builder.toString(), outputType, visitor)
        is TestOutput.Text -> fireOnUncapturedOutput(output.text, outputType)
      }
    }
    return true
  }
}
