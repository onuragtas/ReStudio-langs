package dev.restudio.spring

import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.pom.java.LanguageLevel
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

/**
 * A light fixture test with a compiled library of Spring API stubs (`src/test/resources/stubs`).
 * The stubs are compiled classes, so the model sees them as library code, as in a real project.
 */
abstract class SpringTestCase : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor(): LightProjectDescriptor = SpringStubsDescriptor

  protected fun addSpringBootApplication(pkg: String = "com.example") {
    myFixture.addClass("""
      package $pkg;
      @org.springframework.boot.autoconfigure.SpringBootApplication
      public class DemoApplication {
        public static void main(String[] args) { org.springframework.boot.SpringApplication.run(DemoApplication.class, args); }
      }
    """.trimIndent())
  }
}

object SpringStubsDescriptor : LightJavaCodeInsightFixtureTestCase.ProjectDescriptor(LanguageLevel.JDK_21) {
  private val classes: Path by lazy(::compileStubs)

  override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
    super.configureModule(module, model, contentEntry)
    val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(classes) ?: error("No stub directory: $classes")
    // The Lombok plugin works only when it finds the Lombok library by its Maven coordinates.
    for (name in listOf("spring-stubs", "Gradle: org.projectlombok:lombok:1.18.36")) {
      val libraryModel = model.moduleLibraryTable.createLibrary(name).modifiableModel
      libraryModel.addRoot(root, OrderRootType.CLASSES)
      libraryModel.commit()
    }
  }

  @OptIn(kotlin.io.path.ExperimentalPathApi::class)
  private fun compileStubs(): Path {
    val source = Path.of(SpringStubsDescriptor::class.java.getResource("/stubs")!!.toURI())
    val output = Files.createTempDirectory("restudio-spring-stubs")
    val files = source.walk().filter { it.extension == "java" }.map { it.absolutePathString() }.toList()
    val compiler = ToolProvider.getSystemJavaCompiler() ?: error("The tests need a JDK with javac")
    val status = compiler.run(null, null, System.err, "-d", output.absolutePathString(), *files.toTypedArray())
    check(status == 0) { "The stubs do not compile" }
    for (file in source.walk().filter { it.extension != "java" && Files.isRegularFile(it) }) {
      val target = output.resolve(file.relativeTo(source).toString())
      target.parent.createDirectories()
      file.copyTo(target, overwrite = true)
    }
    output.toFile().deleteOnExit()
    return output
  }
}
