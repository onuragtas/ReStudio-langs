package dev.restudio.spring.graph

enum class BeanNodeKind { COMPONENT, CONFIGURATION, BEAN_METHOD, REPOSITORY, CONFIGURATION_PROPERTIES, MISSING }

/** How the model resolves the injection points of an edge. */
enum class EdgeStatus {
  /** Spring injects the target bean. */
  RESOLVED,
  /** The injection point has more than one candidate. The autowiring inspection reports it. */
  AMBIGUOUS,
  /** The injection point has no bean. The autowiring inspection reports it. The target is a [BeanNodeKind.MISSING] node. */
  UNRESOLVED,
}

enum class EdgeKind {
  /** The source bean gets the target bean through an injection point. */
  INJECTION,
  /** The source is a non-static `@Bean` method. The target is the configuration bean that declares it. */
  FACTORY,
}

/** A bean of the graph, or a placeholder for a bean that is not found. */
data class BeanNode(
  val id: String,
  val name: String,
  /** The short type name. */
  val type: String,
  val qualifiedType: String,
  val packageName: String,
  val kind: BeanNodeKind,
  /** The `@Profile` expressions of the bean and of the class that declares a `@Bean` method. */
  val profiles: List<String>,
  val primary: Boolean,
  val conditional: Boolean,
  /** True when the bean comes from a library (for example a Spring Boot auto-configuration). */
  val library: Boolean,
  /** The file that declares the bean, for the tooltip. */
  val file: String?,
)

/** A dependency from [from] (the bean that has the injection points) to [to]. */
data class BeanEdge(
  val id: String,
  val from: String,
  val to: String,
  val kind: EdgeKind,
  val status: EdgeStatus,
  /** The names of the injection points, for example field and parameter names. */
  val points: List<String>,
)

/**
 * The dependency graph of the beans of one context.
 * [neighbors] are the nodes that the filter keeps only because a node of the filter depends on them.
 */
data class BeanGraph(
  val contextName: String,
  val nodes: List<BeanNode>,
  val edges: List<BeanEdge>,
  val neighbors: Set<String> = emptySet(),
) {
  fun node(id: String): BeanNode? = nodes.firstOrNull { it.id == id }

  /** The package names of the project beans and all their parent packages, sorted. */
  fun packages(): List<String> {
    val result = sortedSetOf<String>()
    for (node in nodes) {
      if (node.library || node.kind == BeanNodeKind.MISSING || node.packageName.isEmpty()) continue
      var name = node.packageName
      while (true) {
        result += name
        val dot = name.lastIndexOf('.')
        if (dot < 0) break
        name = name.substring(0, dot)
      }
    }
    return result.toList()
  }

  /** The profile names in the `@Profile` expressions of the beans, sorted. */
  fun profiles(): List<String> = nodes.flatMap { node -> node.profiles.flatMap(ProfileExpression::names) }.distinct().sorted()

  /** Applies [filter] and returns a new graph. */
  fun filter(filter: BeanGraphFilter): BeanGraph {
    val active = filter.activeProfiles
    val candidates = nodes.filter { node ->
      node.kind == BeanNodeKind.MISSING || active == null || node.profiles.isEmpty() ||
      node.profiles.any { ProfileExpression.matches(it, active) }
    }.associateBy { it.id }
    val core = candidates.values.filter { node ->
      node.kind != BeanNodeKind.MISSING && (filter.showLibrary || !node.library) && filter.matchesPackage(node.packageName)
    }.map { it.id }.toSet()
    val kept = LinkedHashSet(core)
    for (edge in edges) {
      if (edge.from in core && edge.to in candidates) kept += edge.to
    }
    return BeanGraph(
      contextName = contextName,
      nodes = nodes.filter { it.id in kept },
      edges = edges.filter { it.from in kept && it.to in kept },
      neighbors = kept - core,
    )
  }
}

/**
 * The options of the graph view.
 * [packageName]: show only the beans in this package and its subpackages. Null shows all packages.
 * [activeProfiles]: show only the beans that these profiles activate. Null ignores the profiles.
 * [showLibrary]: show the library beans also when no project bean depends on them.
 */
data class BeanGraphFilter(
  val packageName: String? = null,
  val activeProfiles: Set<String>? = null,
  val showLibrary: Boolean = false,
) {
  fun matchesPackage(name: String): Boolean =
    packageName.isNullOrEmpty() || name == packageName || name.startsWith("$packageName.")
}

/**
 * Evaluates the profile expressions of `@Profile`: names, `!`, `&`, `|` and parentheses.
 * When no profile is active, Spring activates the profile `default`.
 */
object ProfileExpression {
  const val DEFAULT = "default"

  fun matches(expression: String, active: Set<String>): Boolean {
    val profiles = active.ifEmpty { setOf(DEFAULT) }
    return Parser(expression, profiles).parse()
  }

  fun names(expression: String): List<String> =
    expression.split('!', '&', '|', '(', ')', ' ', '\t').filter { it.isNotEmpty() }

  private class Parser(private val text: String, private val active: Set<String>) {
    private var position = 0

    fun parse(): Boolean {
      val result = or()
      // An expression that is not complete counts as a name, as in Spring for a simple profile.
      return if (position < text.length) text.trim() in active else result
    }

    private fun or(): Boolean {
      var result = and()
      while (skip('|')) result = and() || result
      return result
    }

    private fun and(): Boolean {
      var result = unary()
      while (skip('&')) result = unary() && result
      return result
    }

    private fun unary(): Boolean {
      if (skip('!')) return !unary()
      if (skip('(')) {
        val result = or()
        skip(')')
        return result
      }
      spaces()
      val start = position
      while (position < text.length && text[position] !in "!&|() \t") position++
      return text.substring(start, position) in active
    }

    private fun skip(char: Char): Boolean {
      spaces()
      if (position < text.length && text[position] == char) {
        position++
        return true
      }
      return false
    }

    private fun spaces() {
      while (position < text.length && text[position].isWhitespace()) position++
    }
  }
}
