package dev.restudio.php

import dev.restudio.core.entrypoints.EntryPoint.Kind.MAIN
import dev.restudio.core.entrypoints.EntryPoint.Kind.TEST
import dev.restudio.php.run.PhpEntryPointDetector
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class PhpEntryPointDetectorTest {
  private fun detect(fileName: String, text: String): List<String> = PhpEntryPointDetector.detect(fileName, text).map {
    val end = text.indexOf('\n', it.offset).let { e -> if (e < 0) text.length else e }
    "${it.kind} ${it.name} @ ${text.substring(it.offset, end).trim()}"
  }

  private fun sample(path: String) = Files.readString(Path.of("../testdata/php", path))

  @Test
  fun `tests of the Laravel sample`() {
    assertEquals(
      listOf("$TEST Tests\\Unit\\ExampleTest::test_that_true_is_true @ public function test_that_true_is_true(): void"),
      detect("ExampleTest.php", sample("tests/Unit/ExampleTest.php")),
    )
  }

  @Test
  fun `the probe script is a main entry point`() {
    assertEquals(listOf("$MAIN probe @ <?php"), detect("probe.php", sample("scripts/probe.php")))
  }

  @Test
  fun `attributes and doc comments mark tests`() {
    val text = """
      <?php

      namespace App\Tests;

      use PHPUnit\Framework\Attributes\Test;
      use PHPUnit\Framework\TestCase;

      final class PriceTest extends TestCase
      {
          #[Test]
          public function adds_tax(): void {}

          /** @test */
          public function rounds(): void {}

          private function helper(): void {}

          public function testOld(): void {}
      }
    """.trimIndent()
    assertEquals(
      listOf(
        "$TEST App\\Tests\\PriceTest::adds_tax @ public function adds_tax(): void {}",
        "$TEST App\\Tests\\PriceTest::rounds @ public function rounds(): void {}",
        "$TEST App\\Tests\\PriceTest::testOld @ public function testOld(): void {}",
      ),
      detect("PriceTest.php", text),
    )
  }

  @Test
  fun `a class and a namespaced function file are not scripts`() {
    assertEquals(emptyList<String>(), detect("User.php", sample("app/Models/User.php")))
    assertEquals(emptyList<String>(), detect("helpers.php", "<?php\n\nnamespace App;\n\nfunction help() {}\n"))
  }
}
