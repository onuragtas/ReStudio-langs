// A Vitest reporter for the IntelliJ test tree. ReStudio passes it with --reporter.
// It writes ID-based TeamCity messages: each message has nodeId and parentNodeId. The root node ID is "0".
// Location hints are file://<absolute path>:<line>:<column>; they need --includeTaskLocation.

const escape = (value) => String(value ?? '')
  .replace(/\|/g, '||').replace(/'/g, "|'").replace(/\n/g, '|n').replace(/\r/g, '|r')
  .replace(/\[/g, '|[').replace(/]/g, '|]')

const message = (name, attributes) => {
  const text = Object.entries(attributes)
    .filter(([, value]) => value !== undefined && value !== null)
    .map(([key, value]) => `${key}='${escape(value)}'`).join(' ')
  process.stdout.write(`\n##teamcity[${name} ${text}]\n`)
}

const hint = (task) => {
  const file = task.module?.moduleId ?? task.moduleId
  return task.location ? `file://${file}:${task.location.line}:${task.location.column}` : `file://${file}`
}

export default class ReStudioReporter {
  started = new Set()
  // Vitest can report a console line before the start of its test. The tree takes output only for a started node.
  pendingLogs = new Map()

  start(name, attributes) {
    message(name, attributes)
    this.started.add(attributes.nodeId)
    for (const log of this.pendingLogs.get(attributes.nodeId) ?? []) this.log(log)
    this.pendingLogs.delete(attributes.nodeId)
  }

  log(log) {
    message(log.type === 'stderr' ? 'testStdErr' : 'testStdOut', { name: '', nodeId: log.taskId, out: log.content })
  }

  onTestModuleStart(module) {
    this.start('testSuiteStarted', { name: module.relativeModuleId, nodeId: module.id, parentNodeId: '0', locationHint: hint(module) })
  }

  onTestSuiteReady(suite) {
    this.start('testSuiteStarted', { name: suite.name, nodeId: suite.id, parentNodeId: suite.parent.id, locationHint: hint(suite) })
  }

  onTestCaseReady(test) {
    this.start('testStarted', { name: test.name, nodeId: test.id, parentNodeId: test.parent.id, locationHint: hint(test) })
  }

  onTestCaseResult(test) {
    if (!this.started.has(test.id)) this.onTestCaseReady(test)
    const result = test.result()
    const duration = Math.round(test.diagnostic()?.duration ?? 0)
    if (result.state === 'failed') {
      const error = result.errors?.[0] ?? {}
      const comparison = error.expected !== undefined && error.actual !== undefined
      message('testFailed', {
        name: test.name, nodeId: test.id,
        message: error.message, details: error.stack,
        type: comparison ? 'comparisonFailure' : undefined,
        expected: comparison ? error.expected : undefined,
        actual: comparison ? error.actual : undefined,
      })
    }
    else if (result.state === 'skipped' || result.state === 'pending') {
      message('testIgnored', { name: test.name, nodeId: test.id, message: result.note })
    }
    message('testFinished', { name: test.name, nodeId: test.id, duration })
  }

  onTestSuiteResult(suite) {
    message('testSuiteFinished', { name: suite.name, nodeId: suite.id })
  }

  onTestModuleEnd(module) {
    // An error outside the tests, for example a failed import, gets its own failed node.
    for (const [index, error] of module.errors().entries()) {
      const nodeId = `${module.id}:error:${index}`
      message('testStarted', { name: 'Module error', nodeId, parentNodeId: module.id, locationHint: hint(module) })
      message('testFailed', { name: 'Module error', nodeId, message: error.message, details: error.stack })
      message('testFinished', { name: 'Module error', nodeId })
    }
    message('testSuiteFinished', { name: module.relativeModuleId, nodeId: module.id })
  }

  onUserConsoleLog(log) {
    if (!log.taskId) process.stdout.write(log.content)
    else if (this.started.has(log.taskId)) this.log(log)
    else this.pendingLogs.set(log.taskId, [...(this.pendingLogs.get(log.taskId) ?? []), log])
  }

  onTestRunEnd() {
    // A line of a task that never started stays in the console.
    for (const logs of this.pendingLogs.values()) for (const log of logs) process.stdout.write(log.content)
    this.pendingLogs.clear()
  }
}
