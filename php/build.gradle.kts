import java.time.Duration
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("org.jetbrains.kotlin.jvm")
  id("org.jetbrains.intellij.platform")
}

version = providers.gradleProperty("restudio.version").get()

repositories {
  mavenCentral()
  intellijPlatform {
    defaultRepositories()
  }
}

dependencies {
  intellijPlatform {
    intellijIdea(providers.gradleProperty("restudio.platformVersion"))
    plugin("com.redhat.devtools.lsp4ij", providers.gradleProperty("restudio.lsp4ijVersion").get())
    bundledModule("intellij.platform.smRunner")
    bundledModule("intellij.platform.testRunner")
    bundledModule("intellij.libraries.teamcity.service.messages")
    bundledPlugin("org.jetbrains.plugins.textmate")
    localPlugin(project(":core"))
    testFramework(TestFrameworkType.Platform)
  }
  testImplementation("junit:junit:4.13.2")
}

kotlin {
  jvmToolchain(25)
  compilerOptions {
    allWarningsAsErrors = true
  }
}

intellijPlatform {
  buildSearchableOptions = false
  pluginConfiguration {
    id = "dev.restudio.php"
    name = "ReStudio PHP"
    version = project.version.toString()
    ideaVersion {
      sinceBuild = "263"
      untilBuild = provider { null }
    }
  }
}

// Starts the local fork build with this plugin and LSP4IJ.
val forkIdePath = providers.gradleProperty("restudio.forkIdePath")
  .orElse("${System.getProperty("user.home")}/.restudio-dev/ide/IntelliJ IDEA OSS.app/Contents")

intellijPlatformTesting.runIde.register("runFork") {
  localPath = file(forkIdePath.get())
  plugins {
    plugin("com.redhat.devtools.lsp4ij", providers.gradleProperty("restudio.lsp4ijVersion").get())
  }
}

tasks.test {
  // A platform test can wait for a language server forever. Stop it early.
  timeout.set(Duration.ofMinutes(15))
}
