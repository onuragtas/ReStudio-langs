import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("org.jetbrains.kotlin.jvm")
  id("org.jetbrains.intellij.platform.module")
}

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
