package dev.restudio.go.test

import dev.restudio.core.testing.DirectoryTestLocator
import dev.restudio.go.run.GoEntryPointDetector

/** Finds a Go test function from the hint `gotest://<package directory>#<TestName>`. */
val GoTestLocator: DirectoryTestLocator = DirectoryTestLocator("gotest", GoEntryPointDetector) { it.endsWith("_test.go") }
