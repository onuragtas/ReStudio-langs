package dev.restudio.php.run

import dev.restudio.core.entrypoints.EntryPoint
import dev.restudio.core.entrypoints.EntryPointDetector
import dev.restudio.php.test.PestEntryPointDetector

/**
 * Finds the entry points of a PHP file without a parser.
 *
 * - In a `*Test.php` file, a method named `test*`, or a method with `#[Test]` or `@test`, is a [EntryPoint.Kind.TEST].
 *   Its name is `Namespace\Class::method`, the name that PHPUnit prints.
 * - A `*Test.php` file without a class, but with Pest calls, is a Pest file. [PestEntryPointDetector] finds its tests.
 * - A script is a file without a namespace and without a class, interface, trait or enum. Its `<?php` tag is a
 *   [EntryPoint.Kind.MAIN].
 */
object PhpEntryPointDetector : EntryPointDetector {
  private val NAMESPACE = Regex("""^[ \t]*namespace\s+([\w\\]+)\s*[;{]""", RegexOption.MULTILINE)
  private val CLASS_LIKE = Regex("""^[ \t]*(?:(?:abstract|final|readonly)\s+)*(?:class|interface|trait|enum)\s+(\w+)""", RegexOption.MULTILINE)
  private val METHOD = Regex("""^[ \t]*(?:(?:public|protected|private|static|final|abstract)\s+)*function\s+(\w+)\s*\(""", RegexOption.MULTILINE)
  private val OPEN_TAG = Regex("""^<\?php\b""", RegexOption.MULTILINE)
  private val TEST_MARK = Regex("""#\[\s*(?:\\?PHPUnit\\Framework\\Attributes\\)?Test\s*]|@test\b""")

  override fun detect(fileName: String, text: CharSequence): List<EntryPoint> {
    if (!fileName.endsWith(".php")) return emptyList()
    val classes = CLASS_LIKE.findAll(text).toList()
    if (classes.isEmpty()) {
      if (fileName.endsWith("Test.php")) PestEntryPointDetector.detect(text).takeIf { it.isNotEmpty() }?.let { return it }
      if (NAMESPACE.containsMatchIn(text)) return emptyList()
      return OPEN_TAG.find(text)?.let { listOf(EntryPoint(EntryPoint.Kind.MAIN, fileName.removeSuffix(".php"), it.range.first)) }.orEmpty()
    }
    if (!fileName.endsWith("Test.php")) return emptyList()
    val namespace = NAMESPACE.find(text)?.groupValues?.get(1)
    val result = mutableListOf<EntryPoint>()
    for (method in METHOD.findAll(text)) {
      val name = method.groupValues[1]
      val start = method.range.first
      // The attributes and the doc comment come before the method, after the previous closing brace or semicolon.
      val header = text.subSequence(text.lastIndexOfAny(charArrayOf('}', ';', '{'), start - 1).coerceAtLeast(0), start)
      if (!name.startsWith("test") && !TEST_MARK.containsMatchIn(header)) continue
      val className = classes.lastOrNull { it.range.first < start }?.groupValues?.get(1) ?: continue
      val qualified = listOfNotNull(namespace, className).joinToString("\\") + "::" + name
      val offset = start + method.value.indexOfFirst { !it.isWhitespace() }
      result += EntryPoint(EntryPoint.Kind.TEST, qualified, offset)
    }
    return result
  }
}
