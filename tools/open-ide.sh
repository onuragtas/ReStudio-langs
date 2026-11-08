#!/bin/sh
# Opens a sample project in the local fork build.
# Usage: tools/open-ide.sh go             (data: ~/.restudio-dev/ide-data)
#        tools/open-ide.sh flutter flutter (data: ~/.restudio-dev/ide-data-flutter)
set -eu
project="${1:?give a testdata project, for example: go}"
data="$HOME/.restudio-dev/ide-data${2:+-$2}"
app="$HOME/.restudio-dev/ide/IntelliJ IDEA OSS.app/Contents/MacOS/idea"
dir="$(cd "$(dirname "$0")/.." && pwd)/testdata/$project"
[ -d "$dir" ] || { echo "no project: $dir" >&2; exit 1; }
cd "$dir"
IDEA_PROPERTIES="$data/idea.properties" nohup "$app" "$dir" > "$data/log/open-ide.out" 2>&1 &
echo "started: $dir (data: $data)"
