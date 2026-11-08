#!/usr/bin/env bash
# Runs every case in cases.json (or the cases given as arguments) one after another.
# Results: .dap-traces/results.jsonl. Traffic: .dap-traces/<case>.jsonl.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
traces="$here/../../.dap-traces"
mkdir -p "$traces"
export PATH=/opt/homebrew/bin:$HOME/go/bin:$HOME/.dotnet/tools:$HOME/flutter/bin:$PATH
export DOTNET_ROOT=/opt/homebrew/opt/dotnet/libexec DOTNET_CLI_TELEMETRY_OPTOUT=1
if [ $# -gt 0 ]; then names=("$@"); else
  names=($(node -e 'console.log(Object.keys(require(process.argv[1])).join(" "))' "$here/cases.json"))
fi
: > "$traces/results.jsonl"
for n in "${names[@]}"; do
  echo "START $n"
  r=$(node "$here/probe.mjs" "$here/cases.json" "$n" "$traces" 2>&1 | tail -1)
  echo "$r" >> "$traces/results.jsonl"
  echo "DONE $n $(echo "$r" | node "$here/summarize.mjs")"
done
echo "ALL DONE"
