package dev.restudio.go.run

import com.intellij.openapi.vfs.VirtualFile
import dev.restudio.core.dap.DapLaunch
import dev.restudio.core.dap.jsonObject
import dev.restudio.core.entrypoints.EntryPoint
import dev.restudio.core.sdk.ToolLocator
import dev.restudio.go.GoBundle

/** Builds the Delve launch for a Go entry point. */
object GoLaunches {
  /** Delve prints this line when its DAP server is ready. LSP4IJ reads the port from it. */
  private const val DELVE_READY_PATTERN = "DAP server listening at: \${address}:\${port}"

  /** Returns the run configuration name and the launch for [entryPoint] in [file]. */
  fun create(file: VirtualFile, entryPoint: EntryPoint): Pair<String, DapLaunch> {
    val packageDir = file.parent.path
    val moduleRoot = moduleRoot(file)?.path ?: packageDir
    val dlv = ToolLocator.find("dlv")?.toString() ?: "dlv"
    val environment = mapOf("PATH" to ToolLocator.childPath())
    return when (entryPoint.kind) {
      EntryPoint.Kind.MAIN -> GoBundle.message("run.configuration.main", file.parent.name) to DapLaunch(
        adapterName = DELVE,
        command = "$dlv dap",
        readyPattern = DELVE_READY_PATTERN,
        workingDirectory = moduleRoot,
        file = file.path,
        launch = jsonObject(
          "type" to "go",
          "name" to file.parent.name,
          "request" to "launch",
          "mode" to "debug",
          "program" to packageDir,
          "cwd" to moduleRoot,
        ),
        environment = environment,
      )
      EntryPoint.Kind.TEST -> GoBundle.message("run.configuration.test", entryPoint.name) to DapLaunch(
        adapterName = DELVE,
        command = "$dlv dap",
        readyPattern = DELVE_READY_PATTERN,
        workingDirectory = packageDir,
        file = file.path,
        launch = jsonObject(
          "type" to "go",
          "name" to entryPoint.name,
          "request" to "launch",
          "mode" to "test",
          "program" to packageDir,
          "cwd" to packageDir,
          "args" to listOf("-test.run", "^${entryPoint.name}$"),
        ),
        environment = environment,
      )
    }
  }

  /** Returns the nearest parent directory with a `go.mod` file. */
  private fun moduleRoot(file: VirtualFile): VirtualFile? =
    generateSequence(file.parent) { it.parent }.firstOrNull { it.findChild("go.mod") != null }

  private const val DELVE = "Go - Delve"
}
