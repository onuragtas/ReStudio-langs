package dev.restudio.dotnet.run

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.util.execution.ParametersListUtil
import java.nio.file.Files
import java.nio.file.Path

/**
 * A profile of `Properties/launchSettings.json`.
 *
 * `dotnet run --launch-profile` applies a profile by itself. The debugger starts the assembly without `dotnet run`,
 * so [DotnetLaunches] applies [commandLineArgs], [environmentVariables] and [applicationUrl] itself.
 */
data class LaunchProfile(
  val name: String,
  val commandName: String?,
  val commandLineArgs: String?,
  val environmentVariables: Map<String, String>,
  val applicationUrl: String?,
  val workingDirectory: String?,
) {
  /** `dotnet run` uses only the profiles with `"commandName": "Project"`. */
  val isProject: Boolean get() = commandName == null || commandName == "Project"

  val arguments: List<String> get() = commandLineArgs?.let(ParametersListUtil::parse).orEmpty()

  /** The environment of the program. ASP.NET Core reads the URLs from `ASPNETCORE_URLS`. */
  val environment: Map<String, String>
    get() = buildMap {
      applicationUrl?.takeIf { it.isNotBlank() }?.let { put("ASPNETCORE_URLS", it) }
      putAll(environmentVariables)
    }
}

object LaunchSettings {
  /** The `launchSettings.json` path of the project in [projectDirectory]. */
  fun file(projectDirectory: Path): Path = projectDirectory.resolve("Properties/launchSettings.json")

  /** Returns the profiles of the project in [projectDirectory], in file order. It reads a file, so do not call it on the EDT. */
  fun read(projectDirectory: Path): List<LaunchProfile> {
    val file = file(projectDirectory)
    if (!Files.isRegularFile(file)) return emptyList()
    return runCatching { parse(Files.readString(file)) }.getOrDefault(emptyList())
  }

  /** Returns the profile with [name]. An empty [name] selects the first `Project` profile, as `dotnet run` does. */
  fun select(profiles: List<LaunchProfile>, name: String?): LaunchProfile? =
    if (name.isNullOrEmpty()) profiles.firstOrNull { it.isProject } else profiles.firstOrNull { it.name == name }

  fun parse(json: String): List<LaunchProfile> {
    val root = JsonParser.parseString(json.removePrefix("﻿")).asJsonObject
    val profiles = root.getAsJsonObject("profiles") ?: return emptyList()
    return profiles.entrySet().mapNotNull { (name, value) ->
      val profile = value as? JsonObject ?: return@mapNotNull null
      fun text(key: String) = profile.get(key)?.takeIf { it.isJsonPrimitive }?.asString
      val environment = profile.getAsJsonObject("environmentVariables")?.entrySet()
        ?.filter { it.value.isJsonPrimitive }?.associate { it.key to it.value.asString }.orEmpty()
      LaunchProfile(name, text("commandName"), text("commandLineArgs"), environment, text("applicationUrl"), text("workingDirectory"))
    }
  }
}
