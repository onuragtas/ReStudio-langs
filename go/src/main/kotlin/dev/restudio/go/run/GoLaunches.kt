package dev.restudio.go.run

import com.intellij.openapi.vfs.VirtualFile
import dev.restudio.core.dap.DapLaunch
import dev.restudio.core.dap.jsonObject
import dev.restudio.core.entrypoints.EntryPoint
import dev.restudio.core.sdk.ToolLocator
import dev.restudio.go.GoBundle
import dev.restudio.go.debug.DelveDescriptorFactory

/** Builds the Delve launch for a Go entry point. */
object GoLaunches {
  // Delve runs "go build" and "go test -c" in its own working directory, not in "cwd".
  // LSP4IJ does not start Delve in the project. Thus each launch sets "dlvCwd" to the package directory.
  /** Delve prints this line when its DAP server is ready. LSP4IJ reads the port from it. */
  private const val DELVE_READY_PATTERN = "DAP server listening at: \${address}:\${port}"

  /** Returns the run configuration name and the launch for [entryPoint] in [file]. */
  fun create(file: VirtualFile, entryPoint: EntryPoint): Pair<String, DapLaunch> {
    val packageDir = file.parent.path
    val moduleRoot = moduleRoot(file)?.path ?: packageDir
    val dlv = ToolLocator.find("dlv")?.toString() ?: "dlv"
    val environment = mapOf("PATH" to ToolLocator.childPath())
    return when (entryPoint.kind) {
      EntryPoint.Kind.MAIN -> GoBundle.message("run.configuration.main", file.parent.name) to application(
        name = file.parent.name,
        file = file.path,
        packageDirectory = packageDir,
        workingDirectory = moduleRoot,
        arguments = emptyList(),
        environment = emptyMap(),
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
          "dlvCwd" to packageDir,
          "args" to listOf("-test.run", testPattern(entryPoint)),
        ),
        environment = environment,
        serverId = DelveDescriptorFactory.SERVER_ID,
      )
    }
  }

  /**
   * Returns the Delve launch for the `main` package in [packageDirectory].
   * The program runs in [workingDirectory] with [arguments] and the [environment] of the user.
   */
  fun application(
    name: String,
    file: String,
    packageDirectory: String,
    workingDirectory: String,
    arguments: List<String>,
    environment: Map<String, String>,
  ): DapLaunch {
    val dlv = ToolLocator.find("dlv")?.toString() ?: "dlv"
    val launch = mutableListOf<Pair<String, Any>>(
      "type" to "go",
      "name" to name,
      "request" to "launch",
      "mode" to "debug",
      "program" to packageDirectory,
      "cwd" to workingDirectory,
      "dlvCwd" to packageDirectory,
    )
    if (arguments.isNotEmpty()) launch += "args" to arguments
    return DapLaunch(
      adapterName = DELVE,
      command = "$dlv dap",
      readyPattern = DELVE_READY_PATTERN,
      workingDirectory = workingDirectory,
      file = file,
      launch = jsonObject(*launch.toTypedArray()),
      environment = childEnvironment(environment),
      serverId = DelveDescriptorFactory.SERVER_ID,
    )
  }

  /** Returns [environment] with the PATH that finds `go`, unless the user sets PATH. */
  fun childEnvironment(environment: Map<String, String>): Map<String, String> =
    if ("PATH" in environment) environment else environment + ("PATH" to ToolLocator.childPath())

  /** Returns the `-run` pattern that matches only [entryPoint]. */
  fun testPattern(entryPoint: EntryPoint): String = "^${entryPoint.name}$"

  /** Returns the nearest parent directory with a `go.mod` file. */
  fun moduleRoot(file: VirtualFile): VirtualFile? =
    generateSequence(file.parent) { it.parent }.firstOrNull { it.findChild("go.mod") != null }

  private const val DELVE = "Go - Delve"
}
