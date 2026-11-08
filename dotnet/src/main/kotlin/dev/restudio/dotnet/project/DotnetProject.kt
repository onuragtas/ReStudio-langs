package dev.restudio.dotnet.project

import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * The parts of a `.csproj` file that run, debug and test need.
 *
 * It reads the file as text. It does not run MSBuild, so a property from an imported file is not seen.
 * Then [targetPath] uses the defaults of the SDK.
 */
data class DotnetProject(val file: Path, val assemblyName: String, val targetFramework: String?, val outputType: String?, val sdk: String?) {
  val directory: Path get() = file.parent

  val name: String get() = file.fileName.toString().removeSuffix(".csproj")

  /** `true` for a console app, a worker or a web app. */
  val isExecutable: Boolean get() = outputType.equals("Exe", ignoreCase = true) || outputType.equals("WinExe", ignoreCase = true) || sdk == "Microsoft.NET.Sdk.Web"

  /** The Debug build output: `bin/Debug/<framework>/<assembly>.dll`. */
  val targetPath: Path? get() = targetFramework?.let { directory.resolve("bin/Debug/$it/$assemblyName.dll") }

  companion object {
    /** Returns the nearest `.csproj` file in the directory of [file] or above it. */
    fun find(file: VirtualFile): DotnetProject? =
      generateSequence(file.parent) { it.parent }
        .firstNotNullOfOrNull { dir -> dir.children.firstOrNull { it.extension == "csproj" } }
        ?.let { parse(Path.of(it.path), String(it.contentsToByteArray(), Charsets.UTF_8)) }

    fun parse(file: Path, text: String): DotnetProject {
      fun property(name: String) = Regex("<$name>\\s*([^<]+?)\\s*</$name>").find(text)?.groupValues?.get(1)
      val targetFramework = property("TargetFramework") ?: property("TargetFrameworks")?.substringBefore(';')
      val sdk = Regex("<Project\\s+Sdk=\"([^\"]+)\"").find(text)?.groupValues?.get(1)
      return DotnetProject(file, property("AssemblyName") ?: file.fileName.toString().removeSuffix(".csproj"), targetFramework, property("OutputType"), sdk)
    }
  }
}
