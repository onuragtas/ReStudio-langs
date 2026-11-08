# ReStudio language plugins

This repo holds the language plugins of ReStudio. The plan is in the fork repo: `../ReStudio/docs/plan.md`. Do not keep a copy of the plan here.

## Layout

- `core/`: shared code. It has no `plugin.xml`. Each language plugin merges it in with `pluginModule`.
- `go/`: gopls, Delve and the Go run gutter icons. It produces one plugin `.zip`.
- `testdata/`: one sample project for each language.
- `tools/lsp-probe/`, `tools/dap-probe/`, `tools/test-probe/`: headless clients that record the real LSP, DAP and test runner traffic.

## Commands

Use JDK 25. Platform 263 needs Java 25.

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@25
./gradlew :go:test          # unit and platform tests
./gradlew :go:buildPlugin   # go/build/distributions/*.zip
./gradlew :go:runFork       # starts the local fork build with the plugin
```

## Rules

- Do not guess the behavior of a language server or a debug adapter. Record the traffic with the probes and read it.
- Treat a deprecated API warning as an error.
- Do no heavy work on the EDT. Read the PSI in a read action.
- Put each user-visible string in a `messages/*Bundle.properties` file.
- A language plugin fills in the LSP4IJ extension points. It does not copy LSP4IJ code.
- Write comments, KDoc and commit messages in ASD-STE100 Simplified Technical English.
