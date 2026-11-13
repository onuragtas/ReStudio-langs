package dev.restudio.spring

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.psi.SyntaxTraverser
import dev.restudio.spring.navigation.SpringLineMarkerProvider
import com.intellij.psi.PsiField
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiParameter
import com.intellij.psi.util.PsiTreeUtil

class SpringNavigationTest : SpringTestCase() {
  override fun setUp() {
    super.setUp()
    addSpringBootApplication()
  }

  /** Returns the gutter markers of the current file: the element text and the names of the targets. */
  private fun markers(): Map<String, List<String>> {
    val leaves = SyntaxTraverser.psiTraverser(myFixture.file).filter { it.firstChild == null }.toList()
    val result = mutableListOf<LineMarkerInfo<*>>()
    SpringLineMarkerProvider().collectSlowLineMarkers(leaves, result)
    return result.filterIsInstance<RelatedItemLineMarkerInfo<*>>()
      .associate { marker ->
        val kind = if (marker.icon == SpringIcons.Bean) "bean" else "inject"
        "$kind ${marker.element!!.text}" to marker.createGotoRelatedItems().map { item -> describe(item.element!!) }.sorted()
      }
  }

  private fun describe(element: com.intellij.psi.PsiElement): String {
    val owner = PsiTreeUtil.getParentOfType(element, com.intellij.psi.PsiClass::class.java, false)
    val name = (element as? PsiNamedElement)?.name ?: element.text
    return when (element) {
      is PsiField, is PsiParameter -> "${PsiTreeUtil.getParentOfType(element, com.intellij.psi.PsiClass::class.java)?.name}.$name"
      is com.intellij.psi.PsiClass -> name
      else -> "${owner?.name}.$name"
    }
  }

  fun `test from injection points to beans`() {
    myFixture.addClass("package com.example; public interface Store {}")
    myFixture.addClass("package com.example; @org.springframework.stereotype.Repository public class JdbcStore implements Store {}")
    myFixture.addClass("package com.example; public class Engine {}")
    myFixture.addClass("package com.example; @org.springframework.context.annotation.Configuration public class Config { @org.springframework.context.annotation.Bean Engine engine() { return new Engine(); } }")
    myFixture.configureByText("Shop.java", """
      package com.example;
      import org.springframework.beans.factory.annotation.Autowired;
      @org.springframework.stereotype.Service
      public class Shop {
        @Autowired Engine engine;
        private Store store;
        public Shop(Store store) { this.store = store; }
        @Autowired void setStores(java.util.List<Store> stores) {}
      }
    """.trimIndent())
    val markers = markers()
    assertEquals(listOf("Config.engine"), markers["inject engine"])
    assertEquals(listOf("JdbcStore"), markers["inject store"])
    assertEquals(listOf("JdbcStore"), markers["inject stores"])
    assertEquals(emptyList<String>(), markers["bean Shop"])
  }

  fun `test from beans to injection points`() {
    myFixture.addClass("""
      package com.example;
      @org.springframework.stereotype.Service
      public class Shop {
        @org.springframework.beans.factory.annotation.Autowired Store store;
      }
    """.trimIndent())
    myFixture.addClass("package com.example; public interface Store {}")
    myFixture.addClass("package com.example; @org.springframework.stereotype.Component public class Cart { public Cart(Store store) {} }")
    myFixture.configureByText("JdbcStore.java", """
      package com.example;
      @org.springframework.stereotype.Repository
      public class JdbcStore implements Store {
        @org.springframework.context.annotation.Bean Runnable task() { return null; }
      }
    """.trimIndent())
    val markers = markers()
    assertEquals(listOf("Cart.store", "Shop.store"), markers["bean JdbcStore"])
    assertEquals(emptyList<String>(), markers["bean task"])
  }

  fun `test lombok style constructor without source uses the field`() {
    // A record has an implicit canonical constructor, like the constructor that Lombok generates.
    myFixture.addClass("package com.example; @org.springframework.stereotype.Repository public class JdbcStore {}")
    myFixture.configureByText("Shop.java", """
      package com.example;
      @org.springframework.stereotype.Service
      public record Shop(JdbcStore store) {}
    """.trimIndent())
    assertEquals(listOf("JdbcStore"), markers()["inject store"])
  }

  fun `test lombok constructor injection uses the fields`() {
    myFixture.addClass("package com.example; @org.springframework.stereotype.Repository public class JdbcStore {}")
    myFixture.addClass("package com.example; public class Missing {}")
    myFixture.enableInspections(dev.restudio.spring.inspection.SpringAutowiringInspection())
    myFixture.configureByText("Shop.java", """
      package com.example;
      @org.springframework.stereotype.Service
      @lombok.RequiredArgsConstructor
      public class Shop {
        private final JdbcStore store;
        private final Missing missing;
        private String notInjected;
      }
    """.trimIndent())
    assertEquals(listOf("JdbcStore"), markers()["inject store"])
    val errors = myFixture.doHighlighting(com.intellij.lang.annotation.HighlightSeverity.ERROR).map { "${it.text}: ${it.description}" }
    assertEquals(listOf("missing: Could not autowire. No beans of 'Missing' type found."), errors)
  }
}
