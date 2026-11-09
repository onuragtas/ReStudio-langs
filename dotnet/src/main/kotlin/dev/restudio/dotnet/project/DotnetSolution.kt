package dev.restudio.dotnet.project

import dev.restudio.dotnet.lsp.RoslynWorkspace
import dev.restudio.dotnet.sdk.DotnetCli
import java.nio.file.Files
import java.nio.file.Path

/** Lists the projects of a solution or of a project directory. */
object DotnetSolution {
  private val PROJECT_EXTENSIONS = listOf(".csproj", ".fsproj", ".vbproj")

  /**
   * Returns the project files of [workspace] that exist.
   *
   * For a solution it runs `dotnet sln list`, because the CLI reads both `.sln` and `.slnx`. It blocks.
   */
  fun projects(workspace: RoslynWorkspace): List<Path> = when (workspace) {
    is RoslynWorkspace.Projects -> workspace.files
    is RoslynWorkspace.Solution -> {
      val output = DotnetCli.run(workspace.file.parent, listOf("sln", workspace.file.toString(), "list"))
      if (output == null || output.isTimeout || output.exitCode != 0) emptyList()
      else parseList(workspace.file, output.stdout).filter { Files.isRegularFile(it) }
    }
  }

  /** Reads the output of `dotnet sln list`: a header, a line of dashes, then one relative path on each line. */
  fun parseList(solution: Path, output: String): List<Path> =
    output.lines()
      .map { it.trim() }
      .dropWhile { !it.startsWith("---") }
      .drop(1)
      .filter { line -> PROJECT_EXTENSIONS.any { line.endsWith(it, ignoreCase = true) } }
      .map { solution.parent.resolve(it.replace('\\', '/')).normalize() }
}
