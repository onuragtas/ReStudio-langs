rootProject.name = "restudio-langs"

pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

include("core", "go", "dotnet")
