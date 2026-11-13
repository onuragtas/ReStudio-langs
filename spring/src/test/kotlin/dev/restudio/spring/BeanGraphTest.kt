package dev.restudio.spring

import dev.restudio.spring.graph.BeanGraph
import dev.restudio.spring.graph.BeanGraphBuilder
import dev.restudio.spring.graph.BeanGraphFilter
import dev.restudio.spring.graph.BeanGraphJson
import dev.restudio.spring.graph.BeanNodeKind
import dev.restudio.spring.graph.EdgeKind
import dev.restudio.spring.graph.EdgeStatus
import dev.restudio.spring.graph.ProfileExpression
import dev.restudio.spring.model.SpringModels

class BeanGraphTest : SpringTestCase() {
  override fun setUp() {
    super.setUp()
    addSpringBootApplication()
  }

  private fun graph(): BeanGraph {
    val root = SpringModels.roots(project).single()
    return BeanGraphBuilder(SpringModels.context(root)).build().graph
  }

  /** Returns the edges of the project beans as "from -> to (STATUS)", or "from => to" for a factory edge. */
  private fun BeanGraph.edgeTexts(): List<String> = edges.filter { node(it.from)?.library == false }.map { edge ->
    if (edge.kind == EdgeKind.FACTORY) "${edge.from} => ${edge.to}" else "${edge.from} -> ${edge.to} (${edge.status})"
  }.sorted()

  private fun BeanGraph.projectNodes(): List<String> = nodes.filter { !it.library }.map { it.id }.sorted()

  private fun addShop() {
    myFixture.addClass("package com.example.store; public interface Store {}")
    myFixture.addClass("package com.example.store; @org.springframework.stereotype.Repository public class JdbcStore implements Store {}")
    myFixture.addClass("package com.example.store; @org.springframework.stereotype.Repository public class JpaStore implements Store {}")
    myFixture.addClass("package com.example.engine; public class Engine {}")
    myFixture.addClass("package com.example.engine; public class Wheel {}")
    myFixture.addClass("""
      package com.example.engine;
      import org.springframework.context.annotation.*;
      @Configuration
      public class EngineConfig {
        @Bean Engine engine() { return new Engine(); }
        @Bean static String label() { return ""; }
      }
    """.trimIndent())
    myFixture.addClass("""
      package com.example.shop;
      import org.springframework.beans.factory.annotation.*;
      import com.example.store.Store;
      import com.example.engine.*;
      @org.springframework.stereotype.Service
      public class Shop {
        @Autowired Engine engine;
        @Autowired Store store;
        @Autowired Store jdbcStore;
        @Autowired Wheel wheel;
        @Autowired @Qualifier("pager") Store pager;
        @Autowired java.util.List<Store> stores;
        public Shop(org.example.lib.Clock clock) {}
      }
    """.trimIndent())
  }

  fun `test nodes and edges`() {
    addShop()
    val graph = graph()
    assertEquals(listOf("com.example.DemoApplication", "engine", "engineConfig", "jdbcStore", "jpaStore", "label",
                        "missing:@org.springframework.beans.factory.annotation.Qualifier:pager", "missing:com.example.engine.Wheel", "shop"),
                 graph.projectNodes())
    val shop = graph.node("shop")!!
    assertEquals(BeanNodeKind.COMPONENT, shop.kind)
    assertEquals("com.example.shop", shop.packageName)
    assertEquals("Shop.java", shop.file)
    assertEquals(BeanNodeKind.CONFIGURATION, graph.node("engineConfig")!!.kind)
    assertEquals(BeanNodeKind.BEAN_METHOD, graph.node("engine")!!.kind)
    assertEquals(BeanNodeKind.REPOSITORY, graph.node("jdbcStore")!!.kind)
    assertEquals("@Qualifier(\"pager\")", graph.node("missing:@org.springframework.beans.factory.annotation.Qualifier:pager")!!.name)
    val clock = graph.node("clock")!!
    assertTrue(clock.library)

    assertEquals(listOf(
      "engine => engineConfig",
      "shop -> clock (RESOLVED)",
      "shop -> engine (RESOLVED)",
      "shop -> jdbcStore (AMBIGUOUS)",
      "shop -> jpaStore (AMBIGUOUS)",
      "shop -> missing:@org.springframework.beans.factory.annotation.Qualifier:pager (UNRESOLVED)",
      "shop -> missing:com.example.engine.Wheel (UNRESOLVED)",
    ), graph.edgeTexts())
    // The edge to a bean has all the injection points; the worst status wins.
    val jdbc = graph.edges.single { it.to == "jdbcStore" }
    assertEquals(listOf("store", "jdbcStore", "stores"), jdbc.points)
    assertEquals(EdgeStatus.AMBIGUOUS, jdbc.status)
  }

  fun `test package filter keeps the direct dependencies as neighbors`() {
    addShop()
    val graph = graph().filter(BeanGraphFilter(packageName = "com.example.shop"))
    assertEquals(listOf("clock", "engine", "jdbcStore", "jpaStore",
                        "missing:@org.springframework.beans.factory.annotation.Qualifier:pager", "missing:com.example.engine.Wheel", "shop"),
                 graph.nodes.map { it.id }.sorted())
    assertEquals(setOf("clock", "engine", "jdbcStore", "jpaStore",
                       "missing:@org.springframework.beans.factory.annotation.Qualifier:pager", "missing:com.example.engine.Wheel"),
                 graph.neighbors)
    // The factory edge of "engine" goes to a node that the filter removes.
    assertFalse(graph.edges.any { it.kind == EdgeKind.FACTORY })

    val engine = graph().filter(BeanGraphFilter(packageName = "com.example.engine"))
    assertEquals(listOf("engine", "engineConfig", "label"), engine.nodes.map { it.id }.sorted())
    assertEquals(listOf("engine => engineConfig"), engine.edgeTexts())
    // A prefix of a package name is not a parent package.
    assertEmpty(graph().filter(BeanGraphFilter(packageName = "com.example.sh")).nodes)
  }

  fun `test library beans`() {
    addShop()
    val full = graph()
    val withoutLibrary = full.filter(BeanGraphFilter())
    // "clock" stays because "shop" depends on it. No project bean depends on the other library beans.
    assertEquals(listOf("clock"), withoutLibrary.nodes.filter { it.library }.map { it.id })
    assertTrue(withoutLibrary.neighbors.contains("clock"))
    val withLibrary = full.filter(BeanGraphFilter(showLibrary = true))
    assertEquals(full.nodes.size, withLibrary.nodes.size)
    assertTrue(withLibrary.nodes.any { it.library && it.kind == BeanNodeKind.CONFIGURATION })
  }

  fun `test profile filter`() {
    myFixture.addClass("package com.example; public interface Store {}")
    myFixture.addClass("package com.example; @org.springframework.stereotype.Repository @org.springframework.context.annotation.Profile(\"dev\") public class MemoryStore implements Store {}")
    myFixture.addClass("package com.example; @org.springframework.stereotype.Repository @org.springframework.context.annotation.Profile(\"!dev\") public class JdbcStore implements Store {}")
    myFixture.addClass("""
      package com.example;
      import org.springframework.context.annotation.*;
      @Configuration @Profile({"cloud", "prod & eu"})
      public class CloudConfig {
        @Bean StringBuilder cloudBuffer() { return new StringBuilder(); }
      }
    """.trimIndent())
    myFixture.addClass("package com.example; @org.springframework.stereotype.Service public class Shop { public Shop(Store store) {} }")
    val graph = graph()
    assertEquals(listOf("cloud", "prod & eu"), graph.node("cloudBuffer")!!.profiles)
    assertEquals(listOf("cloud", "dev", "eu", "prod"), graph.profiles())
    // Both stores have a profile, so the store is not ambiguous.
    assertEquals(listOf("cloudBuffer => cloudConfig", "shop -> jdbcStore (RESOLVED)", "shop -> memoryStore (RESOLVED)"), graph.edgeTexts())

    fun nodes(vararg profiles: String) =
      graph.filter(BeanGraphFilter(activeProfiles = profiles.toSet())).projectNodes()
    assertEquals(listOf("com.example.DemoApplication", "jdbcStore", "shop"), nodes())
    assertEquals(listOf("com.example.DemoApplication", "memoryStore", "shop"), nodes("dev"))
    assertEquals(listOf("cloudBuffer", "cloudConfig", "com.example.DemoApplication", "jdbcStore", "shop"), nodes("cloud"))
    assertEquals(listOf("com.example.DemoApplication", "jdbcStore", "shop"), nodes("prod"))
    assertEquals(listOf("cloudBuffer", "cloudConfig", "com.example.DemoApplication", "jdbcStore", "shop"), nodes("prod", "eu"))
    assertEquals(listOf("shop -> memoryStore (RESOLVED)"), graph.filter(BeanGraphFilter(activeProfiles = setOf("dev"))).edgeTexts())
    // No profile filter shows all beans.
    assertEquals(graph.nodes.size, graph.filter(BeanGraphFilter(activeProfiles = null, showLibrary = true)).nodes.size)
  }

  fun `test packages of the project beans`() {
    addShop()
    assertEquals(listOf("com", "com.example", "com.example.engine", "com.example.shop", "com.example.store"), graph().packages())
  }

  fun `test the graph data changes with the code`() {
    addShop()
    assertTrue(graph().edges.any { it.to == "missing:com.example.engine.Wheel" })
    myFixture.addClass("package com.example.engine; @org.springframework.stereotype.Component public class WheelFactory extends Wheel {}")
    val graph = graph()
    assertNull(graph.node("missing:com.example.engine.Wheel"))
    assertTrue(graph.edgeTexts().contains("shop -> wheelFactory (RESOLVED)"))
  }

  fun `test a graph of 300 beans`() {
    myFixture.addClass("package com.example.big; public interface Port {}")
    for (i in 0 until 300) {
      val dependencies = listOf(i / 2, i / 3, i - 1).filter { it in 0 until i }.distinct()
      val parameters = dependencies.joinToString(", ") { "com.example.big.p${it % 7}.Bean$it b$it" }
      val implements = if (i % 10 == 0) " implements com.example.big.Port" else ""
      myFixture.addClass("package com.example.big.p${i % 7}; @org.springframework.stereotype.Component " +
                         "public class Bean$i$implements { public Bean$i($parameters) {} }")
    }
    myFixture.addClass("package com.example.big; @org.springframework.stereotype.Service public class PortUser { public PortUser(Port port) {} }")
    var start = System.nanoTime()
    val context = SpringModels.context(SpringModels.roots(project).single())
    val model = (System.nanoTime() - start) / 1_000_000
    start = System.nanoTime()
    val graph = BeanGraphBuilder(context).build().graph
    println("Bean graph: ${graph.nodes.size} nodes, ${graph.edges.size} edges in ${(System.nanoTime() - start) / 1_000_000} ms; " +
            "bean model $model ms")
    start = System.nanoTime()
    BeanGraphBuilder(context).build()
    println("Bean graph again: ${(System.nanoTime() - start) / 1_000_000} ms")
    assertTrue(graph.nodes.size > 300)
    assertEquals(30, graph.edges.count { it.from == "portUser" && it.status == EdgeStatus.AMBIGUOUS })
    val filtered = graph.filter(BeanGraphFilter(packageName = "com.example.big.p0"))
    assertTrue(filtered.nodes.size in 43..300)
  }

  fun `test profile expressions`() {
    assertTrue(ProfileExpression.matches("default", emptySet()))
    assertFalse(ProfileExpression.matches("dev", emptySet()))
    assertTrue(ProfileExpression.matches("!dev", emptySet()))
    assertTrue(ProfileExpression.matches("dev", setOf("dev")))
    assertFalse(ProfileExpression.matches("!dev", setOf("dev")))
    assertTrue(ProfileExpression.matches("prod & eu", setOf("prod", "eu")))
    assertFalse(ProfileExpression.matches("prod & eu", setOf("prod")))
    assertTrue(ProfileExpression.matches("dev | test", setOf("test")))
    assertTrue(ProfileExpression.matches("prod & (eu | us)", setOf("prod", "us")))
    assertFalse(ProfileExpression.matches("prod & !(eu | us)", setOf("prod", "us")))
    assertEquals(listOf("prod", "eu", "us"), ProfileExpression.names("prod & !(eu | us)"))
  }

  fun `test json`() {
    addShop()
    val json = BeanGraphJson.write(graph().filter(BeanGraphFilter(packageName = "com.example.shop")))
    assertTrue(json, json.contains("{\"id\":\"shop\",\"name\":\"shop\",\"type\":\"Shop\",\"qualifiedType\":\"com.example.shop.Shop\"," +
                                   "\"package\":\"com.example.shop\",\"kind\":\"COMPONENT\",\"profiles\":[],\"primary\":false," +
                                   "\"conditional\":false,\"library\":false,\"neighbor\":false,\"file\":\"Shop.java\"}"))
    // The qualifier name has quotes. The JSON escapes them.
    assertTrue(json, json.contains("\"name\":\"@Qualifier(\\\"pager\\\")\""))
    assertTrue(json, json.contains("\"status\":\"UNRESOLVED\""))
    val text = with(BeanGraphJson) { StringBuilder().string("a</script> ").toString() }
    assertEquals("\"a\\u003c/script\\u003e\\u2028\"", text)
  }
}
