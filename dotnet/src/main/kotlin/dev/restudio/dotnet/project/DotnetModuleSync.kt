package dev.restudio.dotnet.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.GeneralModuleType
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VfsUtilCore
import dev.restudio.dotnet.lsp.RoslynWorkspace
import java.nio.file.Path

/**
 * Gives each .NET project of the opened solution its own IDE module.
 *
 * The content root of a module is the project directory, and `bin` and `obj` are excluded.
 * A `ProjectReference` becomes a module dependency. The sync does not remove or change a module that exists.
 */
object DotnetModuleSync {
  private val LOG = logger<DotnetModuleSync>()

  /** Reads the solution and evaluates the projects. It blocks, so call it from a background thread only. */
  fun sync(project: Project) {
    val basePath = project.basePath ?: return
    val workspace = RoslynWorkspace.find(Path.of(basePath)) ?: return
    val projects = DotnetSolution.projects(workspace).map { DotnetProject.load(it) }
    if (projects.isEmpty()) return
    ApplicationManager.getApplication().invokeLater({ WriteAction.run<RuntimeException> { apply(project, Path.of(basePath), projects) } }, project.disposed)
  }

  private fun apply(project: Project, basePath: Path, projects: List<DotnetProject>) {
    val manager = ModuleManager.getInstance(project)
    val modules = HashMap<Path, Module>()
    val created = ArrayList<Pair<DotnetProject, Module>>()
    val model = manager.getModifiableModel()
    for (dotnetProject in projects) {
      val url = VfsUtilCore.pathToUrl(dotnetProject.directory.toString())
      val existing = manager.modules.firstOrNull { url in ModuleRootManager.getInstance(it).contentRootUrls }
      if (existing != null) {
        modules[key(dotnetProject.file)] = existing
        continue
      }
      var name = dotnetProject.name
      var index = 2
      while (model.findModuleByName(name) != null) name = "${dotnetProject.name}$index".also { index++ }
      val module = model.newModule(basePath.resolve(".idea/modules/$name.iml"), GeneralModuleType.TYPE_ID)
      modules[key(dotnetProject.file)] = module
      created += dotnetProject to module
    }
    if (created.isEmpty()) {
      model.dispose()
      return
    }
    model.commit()
    for ((dotnetProject, module) in created) {
      val url = VfsUtilCore.pathToUrl(dotnetProject.directory.toString())
      ModuleRootModificationUtil.updateModel(module) { root ->
        val entry = root.addContentEntry(url)
        entry.addExcludeFolder("$url/bin")
        entry.addExcludeFolder("$url/obj")
        for (reference in dotnetProject.projectReferences) {
          val target = modules[key(reference)] ?: continue
          if (target != module) root.addModuleOrderEntry(target)
        }
      }
    }
    LOG.info("Created ${created.size} modules for the .NET projects of ${project.name}")
  }

  /** MSBuild gives real paths (`/private/tmp`), the solution can give a link (`/tmp`). The key makes them equal. */
  private fun key(file: Path): Path = runCatching { file.toRealPath() }.getOrDefault(file.toAbsolutePath().normalize())
}

/** Runs [DotnetModuleSync] when a project opens. */
class DotnetModuleSyncActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    ApplicationManager.getApplication().executeOnPooledThread { DotnetModuleSync.sync(project) }
  }
}
