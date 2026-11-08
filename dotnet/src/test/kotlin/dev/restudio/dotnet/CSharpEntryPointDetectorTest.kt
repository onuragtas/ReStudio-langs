package dev.restudio.dotnet

import dev.restudio.core.entrypoints.EntryPoint.Kind.MAIN
import dev.restudio.core.entrypoints.EntryPoint.Kind.TEST
import dev.restudio.dotnet.run.CSharpEntryPointDetector
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class CSharpEntryPointDetectorTest {
  /** Returns `kind name @ line text` for each entry point. */
  private fun detect(fileName: String, text: String): List<String> = CSharpEntryPointDetector.detect(fileName, text).map {
    val lineStart = text.lastIndexOf('\n', it.offset - 1) + 1
    "${it.kind} ${it.name} @ ${text.substring(lineStart, text.indexOf('\n', it.offset).let { e -> if (e < 0) text.length else e }).trim()}"
  }

  private fun sample(path: String) = Files.readString(Path.of("../testdata/dotnet", path))

  @Test
  fun `top-level statements of the sample apps`() {
    assertEquals(listOf("$MAIN Main @ var service = new ProductService();"), detect("Program.cs", sample("src/Shop.Cli/Program.cs")))
    assertEquals(listOf("$MAIN Main @ var builder = WebApplication.CreateBuilder(args);"), detect("Program.cs", sample("src/Shop.Api/Program.cs")))
  }

  @Test
  fun `xUnit fact of the sample`() {
    assertEquals(
      listOf("$TEST Shop.Domain.Tests.ProductTests.PriceWithTaxAddsTheRate @ public void PriceWithTaxAddsTheRate()"),
      detect("ProductTests.cs", sample("tests/Shop.Domain.Tests/ProductTests.cs")),
    )
  }

  @Test
  fun `classic Main in a block namespace`() {
    val text = """
      using System;

      namespace Tool
      {
          internal class Program
          {
              static async Task<int> Main(string[] args)
              {
                  return 0;
              }
          }
      }
    """.trimIndent()
    assertEquals(listOf("$MAIN Main @ static async Task<int> Main(string[] args)"), detect("Program.cs", text))
  }

  @Test
  fun `theory with data rows, NUnit and MSTest`() {
    val text = """
      namespace Calc.Tests;

      public class MathTests
      {
          [Theory]
          [InlineData(1)]
          [InlineData(2)]
          public void Positive(int value) { }

          [Test]
          public async Task Works() { }

          [TestMethod]
          public void Old() { }

          public void Helper() { }
      }
    """.trimIndent()
    assertEquals(
      listOf(
        "$TEST Calc.Tests.MathTests.Positive @ public void Positive(int value) { }",
        "$TEST Calc.Tests.MathTests.Works @ public async Task Works() { }",
        "$TEST Calc.Tests.MathTests.Old @ public void Old() { }",
      ),
      detect("MathTests.cs", text),
    )
  }

  @Test
  fun `a library class has no entry point`() {
    assertEquals(emptyList<String>(), detect("ProductService.cs", sample("src/Shop.Application/ProductService.cs")))
  }
}
