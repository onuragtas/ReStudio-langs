package dev.restudio.spring

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.properties.psi.Property
import com.intellij.psi.PsiField
import dev.restudio.spring.boot.ConfigDocumentationProvider
import dev.restudio.spring.boot.ConfigKeyInspection
import org.jetbrains.yaml.psi.YAMLKeyValue

class SpringBootConfigTest : SpringTestCase() {
  fun `test properties key completion`() {
    myFixture.configureByText("application.properties", "server.po<caret>")
    myFixture.completeBasic()
    myFixture.checkResult("server.port=<caret>")
  }

  fun `test properties value completion from hints`() {
    myFixture.configureByText("application.properties", "spring.jpa.hibernate.ddl-auto=<caret>")
    val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
    assertSameElements(items, "none", "validate", "update")
  }

  fun `test no completion in other properties files`() {
    myFixture.configureByText("messages.properties", "server.p<caret>")
    assertEmpty(myFixture.completeBasic()?.toList().orEmpty())
  }

  fun `test yaml key completion below a parent key`() {
    myFixture.configureByText("application.yml", "spring:\n  jpa:\n    sh<caret>\n")
    myFixture.completeBasic()
    myFixture.checkResult("spring:\n  jpa:\n    show-sql: <caret>\n")
  }

  fun `test yaml top level completion`() {
    myFixture.configureByText("application-dev.yaml", "serv<caret>")
    val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
    assertContainsElements(items, "server.port", "server.servlet.context-path")
  }

  fun `test unknown keys`() {
    myFixture.enableInspections(ConfigKeyInspection())
    myFixture.addClass("package com.example; public class Uses { @org.springframework.beans.factory.annotation.Value(\"\${server.custom-flag}\") String flag; }")
    myFixture.configureByText("application.properties", """
      server.port=8081
      server.prot=1
      server.servlet.context-path=/app
      spring.jpa.properties.hibernate.format_sql=true
      logging.level.org.example=DEBUG
      app.custom.key=1
      server.custom-flag=on
      spring.jpa.showSql=true
    """.trimIndent())
    val warnings = myFixture.doHighlighting(HighlightSeverity.WARNING).map { it.description }
    assertEquals(listOf("Cannot resolve configuration property 'server.prot'"), warnings)
  }

  fun `test unknown yaml keys`() {
    myFixture.enableInspections(dev.restudio.spring.boot.YamlConfigKeyInspection())
    myFixture.configureByText("application.yml", "server:\n  port: 8080\n  prot: 1\nspring:\n  jpa:\n    properties:\n      hibernate.x: 1\n")
    val warnings = myFixture.doHighlighting(HighlightSeverity.WARNING).map { it.description }
    assertEquals(listOf("Cannot resolve configuration property 'server.prot'"), warnings)
  }

  fun `test value placeholder navigates to the key`() {
    myFixture.addFileToProject("application.properties", "server.port=8081\n")
    myFixture.addFileToProject("application-dev.yml", "server:\n  port: 9090\n")
    myFixture.configureByText("Uses.java", """
      package com.example;
      public class Uses { @org.springframework.beans.factory.annotation.Value("${'$'}{server.po<caret>rt:8080}") int port; }
    """.trimIndent())
    val targets = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, myFixture.caretOffset).toList()
    assertEquals(3, targets.size)
    assertTrue(targets.any { it is Property && it.key == "server.port" })
    assertTrue(targets.any { it is YAMLKeyValue && it.keyText == "port" })
    assertTrue(targets.any { it is PsiField && it.name == "port" && it.containingClass?.name == "ServerProperties" })
  }

  fun `test key navigates to the configuration properties field`() {
    myFixture.addClass("""
      package com.example;
      @org.springframework.boot.context.properties.ConfigurationProperties(prefix = "petclinic")
      public class PetclinicProperties {
        private String ownerName;
        private Cache cache = new Cache();
        public static class Cache { private int maxSize; }
      }
    """.trimIndent())
    myFixture.configureByText("application.properties", "petclinic.cache.max-si<caret>ze=10\n")
    val targets = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, myFixture.caretOffset).toList()
    assertEquals(listOf("maxSize"), targets.map { (it as PsiField).name })
  }

  fun `test properties of project classes are completed`() {
    myFixture.addClass("""
      package com.example;
      @org.springframework.boot.context.properties.ConfigurationProperties("petclinic")
      public class PetclinicProperties { private String ownerName; }
    """.trimIndent())
    myFixture.configureByText("application.properties", "petclinic.o<caret>")
    myFixture.completeBasic()
    myFixture.checkResult("petclinic.owner-name=<caret>")
  }

  fun `test documentation of a key`() {
    myFixture.configureByText("application.properties", "server.port=8081\n")
    val property = (myFixture.file as com.intellij.lang.properties.psi.PropertiesFile).properties.single().psiElement
    val doc = ConfigDocumentationProvider().generateDoc(property, null).orEmpty()
    assertTrue(doc, doc.contains("Server HTTP port.") && doc.contains("java.lang.Integer") && doc.contains("8080"))
  }
}
