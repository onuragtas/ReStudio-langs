package dev.restudio.spring

import com.intellij.lang.annotation.HighlightSeverity
import dev.restudio.spring.inspection.SpringAutowiringInspection

class SpringAutowiringInspectionTest : SpringTestCase() {
  override fun setUp() {
    super.setUp()
    myFixture.enableInspections(SpringAutowiringInspection())
    addSpringBootApplication()
  }

  private fun errors(fileName: String, text: String): List<String> {
    myFixture.configureByText(fileName, text)
    return myFixture.doHighlighting(HighlightSeverity.ERROR).map { "${it.text}: ${it.description}" }
  }

  private fun service(name: String, body: String = "") =
    myFixture.addClass("package com.example;\n@org.springframework.stereotype.Service\npublic class $name { $body }")

  fun `test no bean of a project interface`() {
    myFixture.addClass("package com.example; public interface OwnerService {}")
    val errors = errors("OwnerController.java", """
      package com.example;
      import org.springframework.beans.factory.annotation.Autowired;
      @org.springframework.stereotype.Controller
      public class OwnerController {
        @Autowired private OwnerService service;
      }
    """.trimIndent())
    assertEquals(listOf("service: Could not autowire. No beans of 'OwnerService' type found."), errors)
  }

  fun `test a scanned implementation satisfies the field`() {
    myFixture.addClass("package com.example; public interface OwnerService {}")
    myFixture.addClass("package com.example.impl; @org.springframework.stereotype.Service public class OwnerServiceImpl implements com.example.OwnerService {}")
    val errors = errors("OwnerController.java", """
      package com.example;
      @org.springframework.stereotype.Controller
      public class OwnerController {
        @org.springframework.beans.factory.annotation.Autowired OwnerService service;
      }
    """.trimIndent())
    assertEmpty(errors)
  }

  fun `test implicit constructor injection`() {
    val errors = errors("OwnerController.java", """
      package com.example;
      @org.springframework.web.bind.annotation.RestController
      public class OwnerController {
        private final Missing missing;
        public OwnerController(Missing missing) { this.missing = missing; }
      }
      interface Missing {}
    """.trimIndent())
    assertEquals(listOf("missing: Could not autowire. No beans of 'Missing' type found."), errors)
  }

  fun `test more than one bean`() {
    myFixture.addClass("package com.example; public interface Store {}")
    myFixture.addClass("package com.example; @org.springframework.stereotype.Repository public class JdbcStore implements Store {}")
    myFixture.addClass("package com.example; @org.springframework.stereotype.Repository public class JpaStore implements Store {}")
    val errors = errors("Shop.java", """
      package com.example;
      @org.springframework.stereotype.Service
      public class Shop {
        public Shop(Store store) {}
      }
    """.trimIndent())
    assertEquals(1, errors.size)
    assertTrue(errors[0], errors[0].startsWith("store: Could not autowire. There is more than one bean of 'Store' type. Beans: "))
    assertTrue(errors[0], errors[0].contains("jdbcStore (JdbcStore.java)") && errors[0].contains("jpaStore (JpaStore.java)"))
  }

  fun `test primary, qualifier and parameter name pick one bean`() {
    myFixture.addClass("package com.example; public interface Store {}")
    myFixture.addClass("package com.example; @org.springframework.stereotype.Repository public class JdbcStore implements Store {}")
    myFixture.addClass("package com.example; @org.springframework.stereotype.Repository(\"jpa\") public class JpaStore implements Store {}")
    val errors = errors("Shop.java", """
      package com.example;
      import org.springframework.beans.factory.annotation.*;
      @org.springframework.stereotype.Service
      public class Shop {
        @Autowired @Qualifier("jpa") Store byQualifier;
        @Autowired Store jdbcStore;
        @Autowired @Qualifier("nothing") Store unknown;
      }
    """.trimIndent())
    assertEquals(listOf("\"nothing\": Cannot find bean with qualifier 'nothing'"), errors)
  }

  fun `test primary bean`() {
    myFixture.addClass("package com.example; public interface Store {}")
    myFixture.addClass("package com.example; @org.springframework.stereotype.Repository @org.springframework.context.annotation.Primary public class JdbcStore implements Store {}")
    myFixture.addClass("package com.example; @org.springframework.stereotype.Repository public class JpaStore implements Store {}")
    assertEmpty(errors("Shop.java", "package com.example; @org.springframework.stereotype.Service public class Shop { public Shop(Store store) {} }"))
  }

  fun `test beans in different profiles are not ambiguous`() {
    myFixture.addClass("package com.example; public interface Store {}")
    myFixture.addClass("package com.example; @org.springframework.stereotype.Repository @org.springframework.context.annotation.Profile(\"dev\") public class JdbcStore implements Store {}")
    myFixture.addClass("package com.example; @org.springframework.stereotype.Repository @org.springframework.context.annotation.Profile(\"prod\") public class JpaStore implements Store {}")
    assertEmpty(errors("Shop.java", "package com.example; @org.springframework.stereotype.Service public class Shop { public Shop(Store store) {} }"))
  }

  fun `test bean methods and their parameters`() {
    myFixture.addClass("package com.example; public class Engine {}")
    myFixture.addClass("package com.example; public class Wheel {}")
    val errors = errors("CarConfig.java", """
      package com.example;
      import org.springframework.context.annotation.*;
      @Configuration
      public class CarConfig {
        @Bean Engine engine() { return new Engine(); }
        @Bean String car(Engine engine, Wheel wheel) { return ""; }
      }
    """.trimIndent())
    assertEquals(listOf("wheel: Could not autowire. No beans of 'Wheel' type found."), errors)
  }

  fun `test auto-configuration beans and class conditions`() {
    val errors = errors("Watch.java", """
      package com.example;
      @org.springframework.stereotype.Component
      public class Watch {
        public Watch(org.example.lib.Clock clock, org.example.lib.Tracer tracer) {}
      }
    """.trimIndent())
    assertEquals(listOf("tracer: Could not autowire. No beans of 'Tracer' type found."), errors)
  }

  fun `test class outside the component scan is not checked`() {
    assertEmpty(errors("Other.java", """
      package org.other;
      @org.springframework.stereotype.Component
      public class Other { public Other(Missing missing) {} }
      class Missing {}
    """.trimIndent()))
  }

  fun `test optional, lazy and collection injection`() {
    myFixture.addClass("package com.example; public interface Plugin {}")
    assertEmpty(errors("Host.java", """
      package com.example;
      import org.springframework.beans.factory.annotation.Autowired;
      @org.springframework.stereotype.Component
      public class Host {
        @Autowired java.util.List<Plugin> plugins;
        @Autowired java.util.Optional<Plugin> optional;
        @Autowired org.springframework.beans.factory.ObjectProvider<Plugin> provider;
        @Autowired(required = false) Plugin notRequired;
        @Autowired java.util.Map<String, Plugin> byName;
      }
    """.trimIndent()))
  }

  fun `test spring data repository is a bean`() {
    myFixture.addClass("package com.example; public class Owner {}")
    myFixture.addClass("package com.example.owner; public interface OwnerRepository extends org.springframework.data.repository.CrudRepository<com.example.Owner, Integer> {}")
    assertEmpty(errors("OwnerService.java", """
      package com.example;
      @org.springframework.stereotype.Service
      public class OwnerService { public OwnerService(com.example.owner.OwnerRepository owners) {} }
    """.trimIndent()))
  }

  fun `test generic types select the bean`() {
    myFixture.addClass("package com.example; public interface Repo<T> {}")
    myFixture.addClass("package com.example; @org.springframework.stereotype.Component public class StringRepo implements Repo<String> {}")
    myFixture.addClass("package com.example; @org.springframework.stereotype.Component public class IntRepo implements Repo<Integer> {}")
    val errors = errors("User.java", """
      package com.example;
      @org.springframework.stereotype.Component
      public class User { public User(Repo<String> strings, Repo<Long> longs) {} }
    """.trimIndent())
    assertEquals(listOf("longs: Could not autowire. No beans of 'Repo<Long>' type found."), errors)
  }

  fun `test custom stereotype, inject and resource`() {
    myFixture.addClass("""
      package com.example;
      @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
      @org.springframework.stereotype.Service
      public @interface UseCase {}
    """.trimIndent())
    myFixture.addClass("package com.example; @UseCase public class Billing {}")
    assertEmpty(errors("Checkout.java", """
      package com.example;
      @org.springframework.stereotype.Component
      public class Checkout {
        @jakarta.inject.Inject Billing billing;
        @jakarta.annotation.Resource Billing other;
      }
    """.trimIndent()))
  }

  fun `test import and scan base packages`() {
    myFixture.addClass("package org.shared; public class Mailer {}")
    myFixture.addClass("package org.shared; public class MailConfig { @org.springframework.context.annotation.Bean Mailer mailer() { return new Mailer(); } }")
    myFixture.addClass("package com.example; @org.springframework.context.annotation.Configuration @org.springframework.context.annotation.Import(org.shared.MailConfig.class) public class AppConfig {}")
    assertEmpty(errors("Notifier.java", """
      package com.example;
      @org.springframework.stereotype.Component
      public class Notifier { public Notifier(org.shared.Mailer mailer) {} }
    """.trimIndent()))
  }

  fun `test interface with an unknown annotation is not reported`() {
    myFixture.addClass("package com.example; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface HttpClient {}")
    myFixture.addClass("package com.example; @HttpClient public interface PetClient {}")
    assertEmpty(errors("Pets.java", "package com.example; @org.springframework.stereotype.Component public class Pets { public Pets(PetClient client) {} }"))
  }

  fun `test xml configuration stops missing bean reports`() {
    myFixture.addClass("package com.example; @org.springframework.context.annotation.Configuration @org.springframework.context.annotation.ImportResource(\"beans.xml\") public class XmlConfig {}")
    assertEmpty(errors("Pets.java", "package com.example; @org.springframework.stereotype.Component public class Pets { public Pets(Missing missing) {} } class Missing {}"))
  }

  fun `test add qualifier quick fix`() {
    myFixture.addClass("package com.example; public interface Store {}")
    myFixture.addClass("package com.example; @org.springframework.stereotype.Repository public class JdbcStore implements Store {}")
    myFixture.addClass("package com.example; @org.springframework.stereotype.Repository public class JpaStore implements Store {}")
    myFixture.configureByText("Shop.java", """
      package com.example;
      @org.springframework.stereotype.Service
      public class Shop {
        @org.springframework.beans.factory.annotation.Autowired Store st<caret>ore;
      }
    """.trimIndent())
    myFixture.launchAction(myFixture.findSingleIntention("Add @Qualifier(\"jpaStore\")"))
    assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("@Qualifier(\"jpaStore\")"))
    assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
  }

  fun `test mark primary quick fix`() {
    myFixture.addClass("package com.example; public interface Store {}")
    val jdbc = myFixture.addClass("package com.example; @org.springframework.stereotype.Repository public class JdbcStore implements Store {}")
    myFixture.addClass("package com.example; @org.springframework.stereotype.Repository public class JpaStore implements Store {}")
    myFixture.configureByText("Shop.java", """
      package com.example;
      @org.springframework.stereotype.Service
      public class Shop {
        @org.springframework.beans.factory.annotation.Autowired Store st<caret>ore;
      }
    """.trimIndent())
    myFixture.launchAction(myFixture.findSingleIntention("Mark bean 'jdbcStore' as @Primary"))
    assertTrue(jdbc.containingFile.text, jdbc.containingFile.text.contains("@Primary"))
    assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
  }
}
