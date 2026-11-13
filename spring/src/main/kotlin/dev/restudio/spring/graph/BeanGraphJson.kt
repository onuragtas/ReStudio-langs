package dev.restudio.spring.graph

/** Writes a [BeanGraph] as the JSON that `graph.js` reads. */
object BeanGraphJson {
  fun write(graph: BeanGraph): String = buildString {
    append("{\"context\":").string(graph.contextName)
    append(",\"nodes\":[")
    graph.nodes.forEachIndexed { index, node ->
      if (index > 0) append(',')
      append("{\"id\":").string(node.id)
      append(",\"name\":").string(node.name)
      append(",\"type\":").string(node.type)
      append(",\"qualifiedType\":").string(node.qualifiedType)
      append(",\"package\":").string(node.packageName)
      append(",\"kind\":").string(node.kind.name)
      append(",\"profiles\":[")
      node.profiles.forEachIndexed { i, profile -> if (i > 0) append(','); string(profile) }
      append("],\"primary\":").append(node.primary)
      append(",\"conditional\":").append(node.conditional)
      append(",\"library\":").append(node.library)
      append(",\"neighbor\":").append(node.id in graph.neighbors)
      append(",\"file\":")
      if (node.file == null) append("null") else string(node.file)
      append('}')
    }
    append("],\"edges\":[")
    graph.edges.forEachIndexed { index, edge ->
      if (index > 0) append(',')
      append("{\"id\":").string(edge.id)
      append(",\"from\":").string(edge.from)
      append(",\"to\":").string(edge.to)
      append(",\"kind\":").string(edge.kind.name)
      append(",\"status\":").string(edge.status.name)
      append(",\"points\":[")
      edge.points.forEachIndexed { i, point -> if (i > 0) append(','); string(point) }
      append("]}")
    }
    append("]}")
  }

  /** Appends [value] as a JSON string that is also safe in a JavaScript source and in an HTML script element. */
  fun StringBuilder.string(value: String): StringBuilder {
    append('"')
    for (char in value) {
      when {
        char == '"' -> append("\\\"")
        char == '\\' -> append("\\\\")
        char == '\n' -> append("\\n")
        char == '\r' -> append("\\r")
        char == '\t' -> append("\\t")
        char < ' ' || char == ' ' || char == ' ' || char == '<' || char == '>' || char == '&' ->
          append("\\u").append(String.format("%04x", char.code))
        else -> append(char)
      }
    }
    return append('"')
  }
}
