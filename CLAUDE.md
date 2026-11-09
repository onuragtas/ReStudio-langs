# ReStudio language plugins

This repo holds the language plugins of ReStudio. The plan is in the fork repo: `../ReStudio/docs/plan.md`. Do not keep a copy of the plan here.

## Layout

- `core/`: shared code, as its own plugin `dev.restudio.core`. Each language plugin depends on it. Two plugins cannot embed the same content module: the IDE disables one of them. It holds the tool settings and installer (`sdk/`), the DAP launch, the test translators, the gutter icons and the LSP status widget.
- `go/`: gopls, Delve, the Go Application run type, the gutter icons, the `go test -json` test tree and the New Project generator.
- `dotnet/`: Roslyn LS or csharp-ls, netcoredbg, MSBuild project data, NuGet, the `dotnet test` (TRX) test tree and the New Project generator.
- `web/`: vtsls or `tsc --lsp`, Vue, Angular, Tailwind, HTML, CSS, JSON, ESLint, Prettier, js-debug, Vitest, Jest and the New Project generator.
- `php/`: Phpactor or Intelephense, Xdebug, composer roots, PHPUnit, Pest, php-cs-fixer or Pint and the New Project generator.
- `testdata/`: one sample project for each language.
- `tools/lsp-probe/`, `tools/dap-probe/`, `tools/test-probe/`: headless clients that record the real LSP, DAP and test runner traffic.

## Commands

Use JDK 25. Platform 263 needs Java 25.

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@25
./gradlew :go:test          # unit and platform tests
./gradlew buildPlugin       # core, go and dotnet zips in */build/distributions
./gradlew :go:runFork       # starts the local fork build with the plugin
tools/open-ide.sh go         # opens testdata/go in the fork build (data: ~/.restudio-dev/ide-data)
```

## Rules

- Do not guess the behavior of a language server or a debug adapter. Record the traffic with the probes and read it.
- Treat a deprecated API warning as an error.
- Do no heavy work on the EDT. Read the PSI in a read action.
- Put each user-visible string in a `messages/*Bundle.properties` file.
- A language plugin fills in the LSP4IJ extension points. It does not copy LSP4IJ code.
- Write comments, KDoc and commit messages in ASD-STE100 Simplified Technical English.
