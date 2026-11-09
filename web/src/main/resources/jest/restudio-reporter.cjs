// A Jest reporter for the IntelliJ test tree. ReStudio passes it with --reporters.
// It writes ID-based TeamCity messages: each message has nodeId and parentNodeId. The root node ID is "0".
// Location hints are file://<absolute path>:<line>:<column>; they need --testLocationInResults.
// Jest gives no source position for a describe block, so a suite hint has only the file.
// A test starts in onTestCaseResult: onTestCaseStart has no location (recorded with Jest 30.5.2).
// Jest writes the console output of the tests itself, so the reporter does not repeat it.

const escape = (value) => String(value ?? '')
  .replace(/\|/g, '||').replace(/'/g, "|'").replace(/\n/g, '|n').replace(/\r/g, '|r')
  .replace(/\[/g, '|[').replace(/]/g, '|]')

const message = (name, attributes) => {
  const text = Object.entries(attributes)
    .filter(([, value]) => value !== undefined && value !== null)
    .map(([key, value]) => `${key}='${escape(value)}'`).join(' ')
  process.stdout.write(`\n##teamcity[${name} ${text}]\n`)
}

const text = (value) => typeof value === 'string' ? value : JSON.stringify(value, null, 2)

class ReStudioReporter {
  constructor() {
    // The open suites of each test file: suite key -> node ID.
    this.suites = new Map()
    this.started = new Set()
  }

  // A node ID is the JSON array of the file path and the names, so it is unique and has no control characters.
  fileId(path) {
    return JSON.stringify([path])
  }

  onTestFileStart(test) {
    const nodeId = this.fileId(test.path)
    const name = test.context?.config?.rootDir ? test.path.slice(test.context.config.rootDir.length + 1) : test.path
    message('testSuiteStarted', { name, nodeId, parentNodeId: '0', locationHint: `file://${test.path}` })
    this.suites.set(test.path, new Map())
  }

  // The node ID of the suite for the describe names [ancestors]. The suite nodes start on first use.
  suite(path, ancestors) {
    const suites = this.suites.get(path) ?? new Map()
    this.suites.set(path, suites)
    let parent = this.fileId(path)
    for (let index = 0; index < ancestors.length; index++) {
      const key = JSON.stringify([path, ...ancestors.slice(0, index + 1)])
      let nodeId = suites.get(key)
      if (!nodeId) {
        nodeId = key
        message('testSuiteStarted', { name: ancestors[index], nodeId, parentNodeId: parent, locationHint: `file://${path}` })
        suites.set(key, nodeId)
      }
      parent = nodeId
    }
    return parent
  }

  testId(path, result) {
    return JSON.stringify([path, ...result.ancestorTitles, result.title, 'test'])
  }

  startTest(path, result) {
    const nodeId = this.testId(path, result)
    if (this.started.has(nodeId)) return nodeId
    this.started.add(nodeId)
    const location = result.location
    const locationHint = location ? `file://${path}:${location.line}:${location.column}` : `file://${path}`
    message('testStarted', { name: result.title, nodeId, parentNodeId: this.suite(path, result.ancestorTitles), locationHint })
    return nodeId
  }

  onTestCaseResult(test, result) {
    const nodeId = this.startTest(test.path, result)
    if (result.status === 'failed') {
      const matcher = result.failureDetails?.[0]?.matcherResult
      const comparison = matcher && matcher.expected !== undefined && matcher.actual !== undefined
      const details = (result.failureMessages ?? []).join('\n')
      message('testFailed', {
        name: result.title, nodeId,
        message: details.split('\n')[0], details,
        type: comparison ? 'comparisonFailure' : undefined,
        expected: comparison ? text(matcher.expected) : undefined,
        actual: comparison ? text(matcher.actual) : undefined,
      })
    }
    else if (result.status === 'pending' || result.status === 'skipped' || result.status === 'todo' || result.status === 'disabled') {
      message('testIgnored', { name: result.title, nodeId })
    }
    message('testFinished', { name: result.title, nodeId, duration: Math.round(result.duration ?? 0) })
  }

  onTestFileResult(test, testResult) {
    // A Jest before 29.6 has no onTestCaseResult: report the results here.
    for (const result of testResult.testResults ?? []) {
      if (!this.started.has(this.testId(test.path, result))) this.onTestCaseResult(test, result)
    }
    // An error outside the tests, for example a failed import, gets its own failed node.
    if (testResult.testExecError) {
      const nodeId = JSON.stringify([test.path, 'error'])
      const error = testResult.testExecError
      message('testStarted', { name: 'Test file error', nodeId, parentNodeId: this.fileId(test.path), locationHint: `file://${test.path}` })
      message('testFailed', { name: 'Test file error', nodeId, message: error.message, details: testResult.failureMessage ?? error.stack })
      message('testFinished', { name: 'Test file error', nodeId })
    }
    const suites = [...(this.suites.get(test.path) ?? new Map()).values()].reverse()
    for (const nodeId of suites) message('testSuiteFinished', { name: '', nodeId })
    this.suites.delete(test.path)
    message('testSuiteFinished', { name: '', nodeId: this.fileId(test.path) })
  }

  getLastError() {
    return undefined
  }
}

module.exports = ReStudioReporter
