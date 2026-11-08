package dev.restudio.dotnet.lsp

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** Selects what Roslyn LS loads for a project directory. */
sealed interface RoslynWorkspace {
  data class Solution(val file: Path) : RoslynWorkspace

  data class Projects(val files: List<Path>) : RoslynWorkspace

  companion object {
    private val SKIPPED = setOf("bin", "obj", "node_modules", ".git", ".idea")

    /** A solution in [root] wins. Else all the `.csproj` files under [root]. Else `null`. */
    fun find(root: Path): RoslynWorkspace? {
      if (!root.isDirectory()) return null
      val solution = Files.list(root).use { files ->
        files.filter { it.extension == "sln" || it.extension == "slnx" }.sorted().findFirst().orElse(null)
      }
      if (solution != null) return Solution(solution)
      val projects = mutableListOf<Path>()
      Files.walk(root, 6).use { files ->
        files.filter { path -> path.extension == "csproj" && root.relativize(path).none { it.name in SKIPPED } }.sorted().forEach(projects::add)
      }
      return if (projects.isEmpty()) null else Projects(projects)
    }
  }
}
