#!/usr/bin/env bash
# Runs every case in cases.json (or the cases given as arguments) one after another.
# Results: .lsp-traces/results.jsonl. Traffic: .lsp-traces/<case>.jsonl.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
traces="$here/../../.lsp-traces"
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
  echo "DONE $n $(echo "$r" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{const j=JSON.parse(s);const f=k=>j[k]?.error?"ERR":j[k]?.targets?String(j[k].targets.length):j[k]?.count??j[k]?.edits??(j[k]?.text?"yes":"-");console.log(`def=${f("definition")} hover=${f("hover")} compl=${f("completion")} refs=${f("references")} rename=${f("rename")} fmt=${f("formatting")} diag=${j.diagnostics?.count??"-"} total=${j.totalMs}ms${j.fatal?" FATAL="+j.fatal:""}`)}catch{console.log("BAD "+s.slice(0,200))}})')"
done
echo "ALL DONE"
