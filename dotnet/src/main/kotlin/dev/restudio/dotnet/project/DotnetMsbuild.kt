package dev.restudio.dotnet.project

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import dev.restudio.dotnet.sdk.DotnetCli
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** The values of one project that `dotnet msbuild -getProperty -getItem` gives. */
data class MsbuildProject(
  val targetFramework: String?,
  val targetFrameworks: List<String>,
  val targetPath: Path?,
  val assemblyName: String?,
  val outputType: String?,
  val isTestProject: Boolean,
  val projectReferences: List<Path>,
  val packageReferences: List<PackageReference>,
)

/** A `PackageReference` item. [version] is `null` when central package management sets it. */
data class PackageReference(val id: String, val version: String?)

/**
 * Evaluates a project file with MSBuild and keeps the result.
 *
 * MSBuild sees the imported files (`Directory.Build.props`, the SDK), so the values are correct.
 * One evaluation takes about 0.6 s. Thus the cache keeps a result until the project file changes.
 * A change in an imported file does not clear the cache. Use [invalidate] for that.
 */
object DotnetMsbuild {
  private val PROPERTIES = listOf("TargetFramework", "TargetFrameworks", "TargetPath", "AssemblyName", "OutputType", "IsTestProject")
  private val ITEMS = listOf("ProjectReference", "PackageReference")
  private const val TIMEOUT_MS = 60 * 1000

  private class Entry(val stamp: Long, val project: MsbuildProject)

  private val cache = ConcurrentHashMap<Path, Entry>()
  private val scheduled: MutableSet<Path> = ConcurrentHashMap.newKeySet()
  private val LOG = logger<DotnetMsbuild>()

  /**
   * Returns the cached result for [file], or `null`. It does not block.
   * When the result is missing or old, it starts an evaluation in the background.
   */
  fun cached(file: Path): MsbuildProject? {
    val entry = cache[file]
    if (entry != null && entry.stamp == stamp(file)) return entry.project
    val application = ApplicationManager.getApplication() ?: return null
    if (scheduled.add(file)) {
      application.executeOnPooledThread {
        try {
          evaluate(file)
        }
        finally {
          scheduled.remove(file)
        }
      }
    }
    return null
  }

  /** Evaluates [file] now. It blocks, so call it from a background thread only. */
  fun evaluate(file: Path): MsbuildProject? {
    val stamp = stamp(file)
    cache[file]?.takeIf { it.stamp == stamp }?.let { return it.project }
    val arguments = listOf("msbuild", file.toString(), "-nologo") + PROPERTIES.map { "-getProperty:$it" } + ITEMS.map { "-getItem:$it" }
    val output = DotnetCli.run(file.parent, arguments, TIMEOUT_MS) ?: return null
    if (output.isTimeout || output.exitCode != 0) {
      LOG.info("dotnet msbuild failed for $file: ${output.stderr.ifEmpty { output.stdout }.take(1000)}")
      return null
    }
    val project = parse(output.stdout) ?: return null
    cache[file] = Entry(stamp, project)
    return project
  }

  /** Removes the cached result for [file]. */
  fun invalidate(file: Path) {
    cache.remove(file)
  }

  /** Reads the JSON that `dotnet msbuild` writes for more than one `-getProperty` or `-getItem`. */
  fun parse(json: String): MsbuildProject? {
    val root = runCatching { JsonParser.parseString(json.substring(json.indexOf('{').coerceAtLeast(0))).asJsonObject }.getOrNull() ?: return null
    val properties = root.getAsJsonObject("Properties") ?: JsonObject()
    val items = root.getAsJsonObject("Items") ?: JsonObject()
    fun property(name: String) = properties.get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
    fun items(name: String) = items.getAsJsonArray(name)?.mapNotNull { it as? JsonObject }.orEmpty()
    fun JsonObject.text(name: String) = get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
    return MsbuildProject(
      targetFramework = property("TargetFramework"),
      targetFrameworks = property("TargetFrameworks")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
      targetPath = property("TargetPath")?.let { Path.of(it) },
      assemblyName = property("AssemblyName"),
      outputType = property("OutputType"),
      isTestProject = property("IsTestProject").equals("true", ignoreCase = true),
      projectReferences = items("ProjectReference").mapNotNull { item -> item.text("FullPath")?.let { Path.of(it).normalize() } },
      packageReferences = items("PackageReference").mapNotNull { item -> item.text("Identity")?.let { PackageReference(it, item.text("Version")) } },
    )
  }

  private fun stamp(file: Path): Long = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(-1L)
}
