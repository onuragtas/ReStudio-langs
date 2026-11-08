#!/usr/bin/env bash
# Measures each test runner: all tests with structured output, one test by name,
# and one failing test. Failing tests go into temporary files that the trap removes.
# Output: .test-traces/<lang>-<mode>.out and one summary line per run.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; td="$here/../../testdata"; out="$here/../../.test-traces"
mkdir -p "$out"
export PATH=/opt/homebrew/bin:$HOME/go/bin:$HOME/flutter/bin:$PATH DOTNET_ROOT=/opt/homebrew/opt/dotnet/libexec DOTNET_CLI_TELEMETRY_OPTOUT=1 NO_COLOR=1
temps=()
trap 'rm -f "${temps[@]}"' EXIT
run() { # name dir command...
  local name=$1 dir=$2; shift 2
  local t0=$(date +%s)
  (cd "$dir" && "$@") > "$out/$name.out" 2>&1; local rc=$?
  echo "RUN $name rc=$rc time=$(( $(date +%s) - t0 ))s bytes=$(wc -c < "$out/$name.out" | tr -d ' ')"
}
only=${1:-all}
if [[ $only == all || $only == go ]]; then
  f="$td/go/lib/greet/fail_probe_test.go"; temps+=("$f")
  run go-all "$td/go" go test -json ./lib/... ./api/...
  run go-single "$td/go/lib" go test -json -run '^TestHello$' ./greet
  printf 'package greet\n\nimport "testing"\n\nfunc TestProbeFails(t *testing.T) {\n\tt.Errorf("want 1, got %%d", 2)\n}\n' > "$f"
  run go-fail "$td/go/lib" go test -json -run '^TestProbeFails$' ./greet
  rm -f "$f"
fi
if [[ $only == all || $only == dotnet ]]; then
  f="$td/dotnet/tests/Shop.Domain.Tests/FailProbeTests.cs"; temps+=("$f")
  run dotnet-all "$td/dotnet" dotnet test Shop.sln --logger "trx;LogFilePrefix=all" --results-directory "$out/trx"
  run dotnet-single "$td/dotnet" dotnet test tests/Shop.Domain.Tests --filter "FullyQualifiedName=Shop.Domain.Tests.ProductTests.PriceWithTaxAddsTheRate" --logger "trx;LogFilePrefix=single" --results-directory "$out/trx"
  printf 'namespace Shop.Domain.Tests;\n\npublic class FailProbeTests\n{\n    [Fact]\n    public void ProbeFails()\n    {\n        Assert.Equal(1, 2);\n    }\n}\n' > "$f"
  run dotnet-fail "$td/dotnet" dotnet test tests/Shop.Domain.Tests --filter "FullyQualifiedName~FailProbeTests" --logger "trx;LogFilePrefix=fail" --results-directory "$out/trx"
  rm -f "$f"
fi
if [[ $only == all || $only == vitest ]]; then
  f="$td/web/vue/src/lib/fail-probe.test.ts"; temps+=("$f")
  run vitest-all "$td/web/vue" npx vitest run --reporter=json --outputFile="$out/vitest-all.json"
  run vitest-single "$td/web/vue" npx vitest run --reporter=json --outputFile="$out/vitest-single.json" -t 'adds the prices'
  printf "import { expect, it } from 'vitest'\n\nit('probe fails', () => {\n  expect(2).toBe(1)\n})\n" > "$f"
  run vitest-fail "$td/web/vue" npx vitest run --reporter=json --outputFile="$out/vitest-fail.json" src/lib/fail-probe.test.ts
  rm -f "$f"
fi
if [[ $only == all || $only == phpunit ]]; then
  f="$td/php/tests/Unit/FailProbeTest.php"; temps+=("$f")
  run phpunit-all "$td/php" vendor/bin/phpunit --teamcity
  run phpunit-single "$td/php" vendor/bin/phpunit --teamcity --filter 'test_that_true_is_true'
  printf '<?php\n\nnamespace Tests\\Unit;\n\nuse PHPUnit\\Framework\\TestCase;\n\nclass FailProbeTest extends TestCase\n{\n    public function test_probe_fails(): void\n    {\n        $this->assertSame(1, 2);\n    }\n}\n' > "$f"
  run phpunit-fail "$td/php" vendor/bin/phpunit --teamcity tests/Unit/FailProbeTest.php
  rm -f "$f"
fi
if [[ $only == all || $only == flutter ]]; then
  f="$td/flutter/test/fail_probe_test.dart"; temps+=("$f")
  run flutter-all "$td/flutter" flutter test --machine
  run flutter-single "$td/flutter" flutter test --machine --plain-name 'Counter increments smoke test'
  printf "import 'package:flutter_test/flutter_test.dart';\n\nvoid main() {\n  test('probe fails', () {\n    expect(2, 1);\n  });\n}\n" > "$f"
  run flutter-fail "$td/flutter" flutter test --machine test/fail_probe_test.dart
  rm -f "$f"
fi
echo "ALL DONE"
