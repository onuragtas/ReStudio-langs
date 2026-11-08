package dev.restudio.core.entrypoints

/**
 * A place in a source file that the IDE can run or debug: a program entry point or a test.
 *
 * [offset] is the offset of the declaration keyword. The run gutter icon goes on its line.
 */
data class EntryPoint(val kind: Kind, val name: String, val offset: Int) {
  enum class Kind { MAIN, TEST }
}

/** Finds the entry points of one language in the text of a file. */
fun interface EntryPointDetector {
  fun detect(fileName: String, text: CharSequence): List<EntryPoint>
}
