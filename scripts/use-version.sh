#!/usr/bin/env bash
# Turn the working tree into the source tree for one Minecraft version.
#   scripts/use-version.sh 1.21.11
# The shared tree is the 1.21.4 line. versions/<ver>/ holds what differs:
#   version.properties   gradle.properties overrides; `parent=<ver>` applies that version's overlay first
#   files/               files that replace (or add to) the shared ones, same relative paths
#   delete.txt           shared files that do not exist on that version
# Applying is idempotent and only writes inside the working tree; `git checkout .` undoes it.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
ver="${1:?usage: use-version.sh <minecraft version>}"

chain=()
v="$ver"
while :; do
  [ -d "versions/$v" ] || { echo "unknown version: $v (have: $(ls versions | grep -v README | tr '\n' ' '))" >&2; exit 1; }
  chain=("$v" "${chain[@]}")
  parent="$(grep -E '^parent=' "versions/$v/version.properties" | cut -d= -f2 || true)"
  [ -n "$parent" ] || break
  v="$parent"
done

set_prop() { # key value
  if grep -q "^$1=" gradle.properties; then
    sed -i "s|^$1=.*|$1=$2|" gradle.properties
  else
    printf '%s=%s\n' "$1" "$2" >> gradle.properties
  fi
}

for v in "${chain[@]}"; do
  echo "overlay $v"
  if [ -f "versions/$v/delete.txt" ]; then
    while IFS= read -r f; do [ -n "$f" ] && rm -f "$f"; done < "versions/$v/delete.txt"
  fi
  [ -d "versions/$v/files" ] && cp -a "versions/$v/files/." .
  grep -v -E '^(#|parent=|$)' "versions/$v/version.properties" | while IFS='=' read -r k val; do set_prop "$k" "$val"; done
done
echo "ready: minecraft_version=$(grep '^minecraft_version=' gradle.properties | cut -d= -f2)"
