package dev.restudio.dotnet.project

import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * The parts of a `.csproj` file that run, debug and test need.
 *
 * [parse] reads the file as text, so it does not see a property from an imported file.
 * [withMsbuild] adds the values that MSBuild evaluates (see [DotnetMsbuild]).
 * Without these values, [targetPath] uses the defaults of the SDK.
 */
data class DotnetProject(
  val file: Path,
  val assemblyName: String,
  val targetFramework: String?,
  val outputType: String?,
  val sdk: String?,
  val evaluatedTargetPath: Path? = null,
  val targetFrameworks: List<String> = listOfNotNull(targetFramework),
  val isTestProject: Boolean = false,
  val projectReferences: List<Path> = emptyList(),
  val packageReferences: List<PackageReference> = emptyList(),
) {
  val directory: Path get() = file.parent

  val name: String get() = file.fileName.toString().removeSuffix(".csproj")

  /**
   * `true` for a console app, a worker or a web app.
   *
   * A test project is not executable. An explicit `OutputType` decides. Else the Web and Worker SDKs make an executable.
   */
  val isExecutable: Boolean
    get() = !isTestProject &&
            (outputType?.let { it.equals("Exe", ignoreCase = true) || it.equals("WinExe", ignoreCase = true) } ?: (sdk in EXECUTABLE_SDKS))

  /** The Debug build output. MSBuild gives it. Else it is `bin/Debug/<framework>/<assembly>.dll`. */
  val targetPath: Path? get() = evaluatedTargetPath ?: targetFramework?.let { directory.resolve("bin/Debug/$it/$assemblyName.dll") }

  /** Adds the values that MSBuild evaluated. */
  fun withMsbuild(msbuild: MsbuildProject): DotnetProject {
    val frameworks = msbuild.targetFrameworks.ifEmpty { listOfNotNull(msbuild.targetFramework) }.ifEmpty { targetFrameworks }
    return copy(
      assemblyName = msbuild.assemblyName ?: assemblyName,
      targetFramework = msbuild.targetFramework ?: frameworks.firstOrNull() ?: targetFramework,
      outputType = msbuild.outputType ?: outputType,
      evaluatedTargetPath = msbuild.targetPath,
      targetFrameworks = frameworks,
      isTestProject = msbuild.isTestProject,
      projectReferences = msbuild.projectReferences,
      packageReferences = msbuild.packageReferences,
    )
  }

  companion object {
    private val EXECUTABLE_SDKS = setOf("Microsoft.NET.Sdk.Web", "Microsoft.NET.Sdk.Worker")

    /**
     * Returns the nearest `.csproj` file in the directory of [file] or above it.
     *
     * It does not block on MSBuild. It uses the cached MSBuild values when they exist, and starts an evaluation when not.
     */
    fun find(file: VirtualFile): DotnetProject? =
      generateSequence(file.parent) { it.parent }
        .firstNotNullOfOrNull { dir -> dir.children.firstOrNull { it.extension == "csproj" } }
        ?.let { parse(Path.of(it.path), String(it.contentsToByteArray(), Charsets.UTF_8)) }
        ?.let { project -> DotnetMsbuild.cached(project.file)?.let(project::withMsbuild) ?: project }

    /** Reads [file] and evaluates it with MSBuild. It blocks, so call it from a background thread only. */
    fun load(file: Path): DotnetProject {
      val project = parse(file, Files.readString(file))
      return DotnetMsbuild.evaluate(file)?.let(project::withMsbuild) ?: project
    }

    fun parse(file: Path, text: String): DotnetProject {
      fun property(name: String) = Regex("<$name>\\s*([^<]+?)\\s*</$name>").find(text)?.groupValues?.get(1)
      val frameworks = property("TargetFramework")?.let(::listOf) ?: property("TargetFrameworks")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
      val sdk = Regex("<Project\\s+Sdk=\"([^\"]+)\"").find(text)?.groupValues?.get(1)
      return DotnetProject(
        file = file,
        assemblyName = property("AssemblyName") ?: file.fileName.toString().removeSuffix(".csproj"),
        targetFramework = frameworks.firstOrNull(),
        outputType = property("OutputType"),
        sdk = sdk,
        targetFrameworks = frameworks,
        isTestProject = property("IsTestProject").equals("true", ignoreCase = true),
      )
    }
  }
}
