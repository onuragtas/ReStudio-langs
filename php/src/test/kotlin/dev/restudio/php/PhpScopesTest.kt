package dev.restudio.php

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.restudio.php.ide.PhpBreadcrumbsCollector
import dev.restudio.php.ide.PhpScopes

class PhpScopesTest : BasePlatformTestCase() {
  /** Returns the header lines of the scopes around the `|` mark. */
  private fun headers(code: String): List<String> {
    val offset = code.indexOf('|')
    val text = code.removeRange(offset, offset + 1)
    return PhpScopes.around(PhpScopes.find(text), offset).map { scope ->
      val end = text.indexOf('\n', scope.headerOffset).let { if (it < 0) text.length else it }
      text.substring(scope.headerOffset, end).trim()
    }
  }

  fun `test a Slim route shows the group and the route`() {
    val code = """
      <?php
      ${'$'}app->group('/eptnew/order', ${'$'}a, function () use (${'$'}app) {
          ${'$'}app->post('/credit_card', function () use (${'$'}app) {
              echo 1;
          });

          ${'$'}app->post('/draws_ticket', ${'$'}b, function () use (${'$'}app) {
              try {
                  ${'$'}x = 1;|
              } catch (Throwable ${'$'}e) {
              }
          });
      });
    """.trimIndent()
    assertEquals(
      listOf(
        "\$app->group('/eptnew/order', \$a, function () use (\$app) {",
        "\$app->post('/draws_ticket', \$b, function () use (\$app) {",
        "try {",
      ),
      headers(code),
    )
  }

  fun `test a brace on its own line uses the line before it`() {
    val code = """
      <?php
      class Cart
      {

          public function add(string ${'$'}name): void
          {
              ${'$'}this->items[] = ${'$'}name;|
          }
      }
    """.trimIndent()
    assertEquals(listOf("class Cart", "public function add(string \$name): void"), headers(code))
  }

  fun `test braces in strings comments and heredocs do not count`() {
    val code = """
      <?php
      function a() {
          ${'$'}s = "{${'$'}x} }";
          ${'$'}t = '}';
          // }
          # }
          /* } */
          ${'$'}h = <<<EOT
          }
          EOT;
          ${'$'}n = <<<'EOT'
          }
          EOT;
          #[Attribute]
          echo 1;|
      }
    """.trimIndent()
    assertEquals(listOf("function a() {"), headers(code))
  }

  fun `test text outside php and blocks on one line do not count`() {
    val code = """
      <div>{ }</div>
      <?php if (${'$'}a) { ?>
        <p>}</p>
      <?php } ?>
      <?php
      ${'$'}f = function () { return 1; };
      function b() {
          echo 2;|
      }
    """.trimIndent()
    assertEquals(listOf("function b() {"), headers(code))
  }

  fun `test the collector gives the sticky lines`() {
    val file = myFixture.configureByText("routes.php", "<?php\nfunction a() {\n  if (1) {\n    echo 1;\n  }\n}\n")
    val collector = PhpBreadcrumbsCollector()
    assertTrue(collector.handlesFile(file.virtualFile))
    val document = myFixture.editor.document
    val offset = document.text.indexOf("echo")
    assertEquals(2, collector.computeStickyLineInfos(file.virtualFile, document, offset).size)
    assertEquals(listOf("function a()", "if (1)"), collector.computeCrumbs(file.virtualFile, document, offset, null).map { it.text })
  }
}
