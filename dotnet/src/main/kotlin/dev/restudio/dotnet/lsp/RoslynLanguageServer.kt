package dev.restudio.dotnet.lsp

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.services.LanguageServer

/**
 * The Roslyn LS messages outside the LSP standard.
 *
 * Roslyn LS loads no project by itself. Without one of these notifications each file stays a "misc file",
 * and no feature works. The LSP probe recorded this (see the plan, phase 0).
 */
interface RoslynLanguageServer : LanguageServer {
  @JsonNotification("solution/open")
  fun openSolution(params: SolutionOpenParams)

  @JsonNotification("project/open")
  fun openProjects(params: ProjectOpenParams)
}

/** [solution] is the URI of a `.sln` or `.slnx` file. */
data class SolutionOpenParams(val solution: String)

/** [projects] are the URIs of `.csproj` files. */
data class ProjectOpenParams(val projects: List<String>)
