package dev.restudio.spring

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.pom.java.LanguageLevel
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.restudio.spring.boot.ConfigKeyInspection
import dev.restudio.spring.boot.YamlConfigKeyInspection
import dev.restudio.spring.graph.BeanGraphBuilder
import dev.restudio.spring.graph.BeanGraphFilter
import dev.restudio.spring.graph.EdgeStatus
import dev.restudio.spring.inspection.SpringAutowiringInspection
import dev.restudio.spring.model.SpringModels
import java.io.File

private val SAMPLE = File("../testdata/spring").absoluteFile
private val LIBS = File(SAMPLE, "build/runtime-libs")

/**
 * Runs the inspections on `testdata/spring` with the real Spring Boot jars and a real JDK.
 * The test needs `./gradlew copyLibs` in `testdata/spring` first. Without the jars it does nothing.
 */
class PetclinicIntegrationTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor(): LightProjectDescriptor = PetclinicDescriptor

  override fun getTestDataPath(): String = SAMPLE.path

  private val available get() = LIBS.isDirectory && LIBS.list().orEmpty().any { it.startsWith("spring-boot-autoconfigure") }

  override fun setUp() {
    super.setUp()
    if (!available) return
    myFixture.enableInspections(SpringAutowiringInspection(), ConfigKeyInspection(), YamlConfigKeyInspection())
    myFixture.copyDirectoryToProject("src/main/java", "")
    myFixture.copyDirectoryToProject("src/main/resources", "")
  }

  private fun springProblems(path: String): List<String> {
    myFixture.configureFromTempProjectFile(path)
    return myFixture.doHighlighting(HighlightSeverity.WARNING)
      .filter { info ->
        val text = info.description.orEmpty()
        text.startsWith("Could not autowire") || text.startsWith("Cannot find bean") || text.startsWith("Cannot resolve configuration property")
      }
      .map { "${it.text}: ${it.description}" }
  }

  fun `test the petclinic application has no problems`() {
    if (!available) return println("Skipped: no jars in $LIBS")
    val sources = File(SAMPLE, "src/main/java/org/restudio/petclinic").walkTopDown().filter { it.extension == "java" }
      .map { it.relativeTo(File(SAMPLE, "src/main/java")).invariantSeparatorsPath }.toList()
    val problems = (sources + listOf("application.properties", "application-dev.yml")).flatMap { path -> springProblems(path).map { "$path: $it" } }
    assertEmpty(problems)
  }

  fun `test the bean graph of the sample with the real Spring Boot jars`() {
    if (!available) return println("Skipped: no jars in $LIBS")
    val roots = SpringModels.roots(project).associateBy { it.name }
    var start = System.nanoTime()
    val casesContext = SpringModels.context(roots.getValue("CasesApplication"))
    val petclinicContext = SpringModels.context(roots.getValue("PetClinicApplication"))
    val model = (System.nanoTime() - start) / 1_000_000
    start = System.nanoTime()
    val cases = BeanGraphBuilder(casesContext).build().graph
    val petclinic = BeanGraphBuilder(petclinicContext).build().graph
    println("Bean graph: ${cases.nodes.size} + ${petclinic.nodes.size} nodes, ${cases.edges.size} + ${petclinic.edges.size} edges " +
            "in ${(System.nanoTime() - start) / 1_000_000} ms; bean model $model ms")
    start = System.nanoTime()
    BeanGraphBuilder(petclinicContext).build()
    println("Bean graph again: ${(System.nanoTime() - start) / 1_000_000} ms")
    // The same problems as the inspection, as edges to missing nodes and ambiguous edges.
    val problems = cases.edges.filter { it.from == "autowiringCases" && it.status != EdgeStatus.RESOLVED }
      .map { "${it.to} ${it.status} ${it.points}" }.sorted()
    assertEquals(listOf(
      "mailNotifier AMBIGUOUS [ambiguous, mailNotifier]",
      "missing:@org.springframework.beans.factory.annotation.Qualifier:pager UNRESOLVED [unknownQualifier]",
      "missing:org.restudio.cases.NotABean UNRESOLVED [missing]",
      "missing:org.springframework.web.client.RestTemplate UNRESOLVED [restTemplate]",
      "smsNotifier AMBIGUOUS [ambiguous, qualified]",
    ), problems)
    assertEmpty(petclinic.edges.filter { it.status != EdgeStatus.RESOLVED })
    assertTrue(petclinic.filter(BeanGraphFilter()).edges.any { it.from == "ownerController" && it.to == "ownerRepository" })
  }

  fun `test the cases give the results of IntelliJ IDEA Ultimate`() {
    if (!available) return println("Skipped: no jars in $LIBS")
    val problems = springProblems("org/restudio/cases/AutowiringCases.java").sorted()
    assertEquals(listOf(
      "\"pager\": Cannot find bean with qualifier 'pager'",
      "ambiguous: Could not autowire. There is more than one bean of 'Notifier' type. Beans: mailNotifier (MailNotifier.java), smsNotifier (SmsNotifier.java)",
      "missing: Could not autowire. No beans of 'NotABean' type found.",
      "restTemplate: Could not autowire. No beans of 'RestTemplate' type found.",
    ), problems)
  }
}

object PetclinicDescriptor : LightJavaCodeInsightFixtureTestCase.ProjectDescriptor(LanguageLevel.JDK_21) {
  override fun getSdk(): Sdk = JavaSdk.getInstance().createJdk("jdk", System.getProperty("java.home"), false)

  override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
    super.configureModule(module, model, contentEntry)
    val jars = LIBS.listFiles().orEmpty().filter { it.extension == "jar" }.sortedBy { it.name }
    if (jars.isEmpty()) return
    for (jar in jars) {
      val local = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(jar) ?: continue
      val root = JarFileSystem.getInstance().getJarRootForLocalFile(local) ?: continue
      // The Lombok plugin finds Lombok by the Maven coordinates in the library name.
      val name = if (jar.name.startsWith("lombok-")) "Gradle: org.projectlombok:lombok:1.18.40" else jar.nameWithoutExtension
      val library = model.moduleLibraryTable.createLibrary(name).modifiableModel
      library.addRoot(root, OrderRootType.CLASSES)
      library.commit()
    }
  }
}
