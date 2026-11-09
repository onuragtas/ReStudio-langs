package dev.restudio.dotnet.lsp

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** Selects what Roslyn LS loads for a project directory. */
sealed interface RoslynWorkspace {
  data class Solution(val file: Path) : RoslynWorkspace

  data class Projects(val files: List<Path>) : RoslynWorkspace

  companion object {
    private val SKIPPED = setOf("bin", "obj", "node_modules", ".git", ".idea")
    private const val MAX_DEPTH = 6

    /**
     * A solution in [root] wins. Else all the `.csproj` files under [root]. Else `null`.
     *
     * The search does not go into the [SKIPPED] directories, so a large `node_modules` costs nothing.
     */
    fun find(root: Path): RoslynWorkspace? {
      if (!root.isDirectory()) return null
      val solution = Files.list(root).use { files ->
        files.filter { it.extension == "sln" || it.extension == "slnx" }.sorted().findFirst().orElse(null)
      }
      if (solution != null) return Solution(solution)
      val projects = mutableListOf<Path>()
      Files.walkFileTree(root, EnumSet.noneOf(java.nio.file.FileVisitOption::class.java), MAX_DEPTH, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
          if (dir != root && dir.name in SKIPPED) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
          if (file.extension == "csproj") projects.add(file)
          return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
      })
      return if (projects.isEmpty()) null else Projects(projects.sorted())
    }
  }
}
