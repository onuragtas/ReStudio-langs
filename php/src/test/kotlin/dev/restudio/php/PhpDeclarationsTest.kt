package dev.restudio.php

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.restudio.php.lsp.PhpDeclarations
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either

class PhpDeclarationsTest : BasePlatformTestCase() {
  private val uri = "file:///p/a.php"

  fun `test a definition that contains the request position is a declaration`() {
    val declarations = PhpDeclarations()
    // Phpactor gives the range of the whole method.
    declarations.request("1", uri, uri, Position(15, 11))
    declarations.response("1", Either.forLeft<List<Location>, Nothing>(listOf(Location(uri, Range(Position(15, 0), Position(17, 1))))))
    assertEquals(Position(15, 11), declarations.declaration("/p/a.php"))

    // A Cmd+click on a call goes to an other place. The old declaration goes away.
    declarations.request("2", uri, uri, Position(10, 6))
    declarations.response("2", Either.forLeft<List<Location>, Nothing>(listOf(Location(uri, Range(Position(15, 0), Position(17, 1))))))
    assertNull(declarations.declaration("/p/a.php"))
  }

  fun `test a definition in an other file or no definition is not a declaration`() {
    val declarations = PhpDeclarations()
    declarations.request("1", uri, uri, Position(1, 1))
    declarations.response("1", listOf(Location("file:///p/b.php", Range(Position(0, 0), Position(5, 0)))))
    assertNull(declarations.declaration("/p/a.php"))
    declarations.request("2", uri, uri, Position(1, 1))
    declarations.response("2", null)
    assertNull(declarations.declaration("/p/a.php"))
    declarations.response("3", null)
  }

  fun `test the Docker URI of the server matches the result`() {
    val declarations = PhpDeclarations()
    declarations.request("1", uri, "file:///var/www/html/a.php", Position(2, 3))
    declarations.response("1", listOf(Location("file:///var/www/html/a.php", Range(Position(2, 0), Position(4, 1)))))
    assertEquals(Position(2, 3), declarations.declaration("/p/a.php"))
  }
}
