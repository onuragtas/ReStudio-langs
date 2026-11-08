package dev.restudio.dotnet.run

import dev.restudio.core.entrypoints.EntryPoint
import dev.restudio.core.entrypoints.EntryPointDetector

/**
 * Finds the entry points of a C# file without a parser.
 *
 * - A `static Main` method, or the first top-level statement of a file, is a [EntryPoint.Kind.MAIN].
 * - A method with a test attribute of xUnit, NUnit or MSTest is a [EntryPoint.Kind.TEST].
 *   Its name is the fully qualified name that `dotnet test --filter FullyQualifiedName=` takes.
 *
 * Comments and strings can confuse it. The language server can replace it later.
 */
object CSharpEntryPointDetector : EntryPointDetector {
  private val TEST_ATTRIBUTE = Regex("""^[ \t]*\[\s*(?:Xunit\.)?(?:Fact|Theory|Test|TestCase|TestCaseSource|TestMethod|DataTestMethod)\b[^\]]*]""", RegexOption.MULTILINE)
  private val METHOD = Regex("""^[ \t]*(?:\[[^\]]*][ \t]*\r?\n[ \t]*)*(?:(?:public|private|protected|internal|static|async|virtual|override)\s+)*[\w<>\[\],.?\s]+?\s+(\w+)\s*\(""", RegexOption.MULTILINE)
  private val MAIN = Regex("""^[ \t]*(?:(?:public|private|internal|static|async)\s+)*static\s+(?:async\s+)?(?:void|int|Task|Task<int>)\s+Main\s*\(""", RegexOption.MULTILINE)
  private val FILE_NAMESPACE = Regex("""^[ \t]*namespace\s+([\w.]+)\s*;""", RegexOption.MULTILINE)
  private val BLOCK_NAMESPACE = Regex("""^[ \t]*namespace\s+([\w.]+)\s*\{?\s*$""", RegexOption.MULTILINE)
  private val CLASS = Regex("""\b(?:class|record|struct)\s+(\w+)""")
  private val DECLARATION_START = Regex("""^(?:namespace|class|record|struct|interface|enum|public|internal|private|protected|static|abstract|sealed|partial|file|\[|#)""")

  override fun detect(fileName: String, text: CharSequence): List<EntryPoint> {
    if (!fileName.endsWith(".cs")) return emptyList()
    val result = mutableListOf<EntryPoint>()
    MAIN.find(text)?.let { result += EntryPoint(EntryPoint.Kind.MAIN, "Main", it.range.first + leadingSpace(it.value)) }
    if (result.isEmpty()) topLevelStatement(text)?.let { result += EntryPoint(EntryPoint.Kind.MAIN, "Main", it) }
    val namespace = (FILE_NAMESPACE.find(text) ?: BLOCK_NAMESPACE.find(text))?.groupValues?.get(1)
    for (attribute in TEST_ATTRIBUTE.findAll(text)) {
      val method = METHOD.find(text, attribute.range.last + 1) ?: continue
      val className = CLASS.findAll(text.subSequence(0, attribute.range.first)).lastOrNull()?.groupValues?.get(1) ?: continue
      val qualifiedName = listOfNotNull(namespace, className, method.groupValues[1]).joinToString(".")
      val offset = method.groups[1]!!.range.first.let { nameOffset -> lineStart(text, nameOffset) + leadingSpace(text.subSequence(lineStart(text, nameOffset), nameOffset)) }
      if (result.none { it.offset == offset }) result += EntryPoint(EntryPoint.Kind.TEST, qualifiedName, offset)
    }
    return result
  }

  /** Returns the offset of the first statement when the file uses top-level statements. */
  private fun topLevelStatement(text: CharSequence): Int? {
    var offset = 0
    var inBlockComment = false
    for (line in text.lineSequence()) {
      val trimmed = line.trim()
      val start = offset + line.indexOf(trimmed.firstOrNull() ?: ' ').coerceAtLeast(0)
      offset += line.length + 1
      when {
        inBlockComment -> if (trimmed.contains("*/")) inBlockComment = false
        trimmed.startsWith("/*") -> if (!trimmed.contains("*/")) inBlockComment = true
        trimmed.isEmpty() || trimmed.startsWith("//") -> {}
        trimmed.startsWith("using ") && trimmed.endsWith(";") && !trimmed.contains("(") -> {}
        trimmed.startsWith("global using ") -> {}
        DECLARATION_START.containsMatchIn(trimmed) -> return null
        else -> return start
      }
    }
    return null
  }

  private fun lineStart(text: CharSequence, offset: Int): Int = text.lastIndexOf('\n', offset - 1) + 1

  private fun leadingSpace(text: CharSequence): Int = text.indexOfFirst { it != ' ' && it != '\t' && it != '\r' && it != '\n' }.coerceAtLeast(0)
}
