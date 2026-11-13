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
    bundledPlugin("com.intellij.java")
    bundledPlugin("com.intellij.properties")
    bundledPlugin("org.jetbrains.plugins.yaml")
    bundledModule("intellij.platform.ui.jcef")
    bundledModule("intellij.libraries.jcef")
    testFramework(TestFrameworkType.Platform)
    testFramework(TestFrameworkType.Plugin.Java)
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
    id = "dev.restudio.spring"
    name = "ReStudio Spring"
    version = project.version.toString()
    ideaVersion {
      sinceBuild = "263"
      untilBuild = provider { null }
    }
  }
}

// The test IDE is the unified IntelliJ IDEA distribution. Its closed-source Spring plugins are not in ReStudio
// and they break the light fixture. Disable them; the dependent plugins stop with them.
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>("prepareTestSandbox") {
  disabledPlugins.addAll("com.intellij.spring", "com.intellij.spring.boot", "com.intellij.micronaut")
}

// Starts the local fork build with this plugin.
val forkIdePath = providers.gradleProperty("restudio.forkIdePath")
  .orElse("${System.getProperty("user.home")}/.restudio-dev/ide/IntelliJ IDEA OSS.app/Contents")

intellijPlatformTesting.runIde.register("runFork") {
  localPath = file(forkIdePath.get())
}
