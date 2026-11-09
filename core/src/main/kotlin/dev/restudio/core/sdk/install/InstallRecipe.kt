package dev.restudio.core.sdk.install

/**
 * Tells [ToolInstaller] how to install one pinned version of a tool into `~/.restudio-dev/tools/<tool id>/<version>/`.
 *
 * A language plugin gives the recipe in [dev.restudio.core.sdk.Tool.recipe]. Core has no recipe of a language tool.
 */
sealed interface InstallRecipe {
  /** The pinned version. It is also the name of the version directory. */
  val version: String

  /** The path of the executable, relative to the version directory. */
  val executable: String

  /** The ID of the tool that the install command needs, for example `npm`. */
  val requires: String?

  /** Runs `npm install --prefix <version directory> <packageName>@<version> <extraPackages>`. */
  data class Npm(
    val packageName: String,
    override val version: String,
    /** The command in `node_modules/.bin`. */
    val bin: String,
    /** More pinned packages, for example `typescript@5.9.3`. */
    val extraPackages: List<String> = emptyList(),
  ) : InstallRecipe {
    override val executable: String get() = "node_modules/.bin/$bin"
    override val requires: String get() = "npm"
  }

  /** Runs `go install <module>@<version>` with `GOBIN=<version directory>`. */
  data class GoInstall(
    /** The package path, for example `golang.org/x/tools/gopls`. */
    val module: String,
    override val version: String,
    val binary: String = module.substringAfterLast('/'),
  ) : InstallRecipe {
    override val executable: String get() = binary
    override val requires: String get() = "go"
  }

  /** Runs `dotnet tool install <packageId> --version <version> --tool-path <version directory>`. */
  data class DotnetTool(
    val packageId: String,
    override val version: String,
    /** The command that the package installs. */
    val command: String,
  ) : InstallRecipe {
    override val executable: String get() = command
    override val requires: String get() = "dotnet"
  }

  /**
   * Downloads [url] into the version directory and checks the SHA-256 of the file.
   *
   * When [archive] is not [Archive.NONE], it unpacks the file and removes it.
   */
  data class Download(
    val url: String,
    /** The SHA-256 of the downloaded file, as hexadecimal digits. */
    val sha256: String,
    override val version: String,
    val fileName: String = url.substringAfterLast('/'),
    override val executable: String = fileName,
    val archive: Archive = Archive.NONE,
  ) : InstallRecipe {
    override val requires: String? get() = null
  }

  enum class Archive { NONE, ZIP, TAR }
}
