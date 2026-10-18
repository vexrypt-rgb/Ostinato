#!/usr/bin/env bash
# Ostinato: per-commit regression runner (docs/REGRESSION.md). New file.
#
#   scripts/regress.sh [options] [<rev> | <a>..<b>]...     (default: HEAD)
#
# Each commit is checked out in one reused worktree (build/regress-runs/wt), so Gradle and cargo stay incremental, and
# `./gradlew regress` runs there at low priority. Reports land in build/regress-runs/<sha>/ and a summary table in
# build/regress-runs/summary.md. Commits older than the suite get the suite overlaid (its test-side files plus the
# one apply line in build.gradle), so a range can be bisected; an overlay that does not compile shows as an error.
#
# Options:
#   --worlds DIR      folder of the pk_* map saves (default $OSTINATO_REGRESS_WORLDS)
#   --baseline DIR    baseline to compare with (default: regress/baseline of this checkout)
#   --out DIR         where runs go (default: build/regress-runs of this checkout)
#   --stages LIST     unit,pathbench,simdrill,combat (default all)
#   --live            also run the live-client stages in regress/live.d/*.sh (see the README there)
#   --nice N          CPU niceness (default 10)        --workers N   Gradle --max-workers (default 2)
#   --java DIR        JAVA_HOME (JDK 25)               --            pass the rest to Gradle
set -euo pipefail

SUITE="$(cd "$(dirname "$0")/.." && pwd)"
WORLDS="${OSTINATO_REGRESS_WORLDS:-}"
BASELINE="$SUITE/regress/baseline"
OUT="$SUITE/build/regress-runs"
STAGES=""
LIVE=0
NICE=10
WORKERS=2
REVS=()
EXTRA=()
while [ $# -gt 0 ]; do
  case "$1" in
    --worlds) WORLDS="$2"; shift 2 ;;
    --baseline) BASELINE="$(cd "$2" && pwd)"; shift 2 ;;
    --out) mkdir -p "$2"; OUT="$(cd "$2" && pwd)"; shift 2 ;;
    --stages) STAGES="$2"; shift 2 ;;
    --live) LIVE=1; shift ;;
    --nice) NICE="$2"; shift 2 ;;
    --workers) WORKERS="$2"; shift 2 ;;
    --java) export JAVA_HOME="$2"; export PATH="$2/bin:$PATH"; shift 2 ;;
    --) shift; EXTRA=("$@"); break ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) REVS+=("$1"); shift ;;
  esac
done
[ ${#REVS[@]} -eq 0 ] && REVS=(HEAD)

cd "$SUITE"
SHAS=()
for r in "${REVS[@]}"; do
  if [[ "$r" == *..* ]]; then
    while read -r s; do SHAS+=("$s"); done < <(git rev-list --reverse --first-parent "$r")
  else
    SHAS+=("$(git rev-parse --verify "$r^{commit}")")
  fi
done

mkdir -p "$OUT"
WT="$OUT/wt"
if [ ! -e "$WT/.git" ]; then
  git worktree add --detach "$WT" "${SHAS[0]}" >/dev/null
fi
# the suite's own files, overlaid onto commits that predate it
OVERLAY=(gradle/regress.gradle src/test/java/baritone/regress src/test/java/baritone/pathing/calc/RegressPlan.java src/test/resources/regress)

SUMMARY="$OUT/summary.md"
[ -f "$SUMMARY" ] || printf '| commit | subject | verdict | fail | warn | improved | report |\n|---|---|---|---|---|---|---|\n' > "$SUMMARY"
STATUS=0
for sha in "${SHAS[@]}"; do
  short="${sha:0:10}"
  subject="$(git log -1 --format=%s "$sha" | cut -c1-70 | tr '|' '/')"
  echo "[regress] $short $subject"
  (
    cd "$WT"
    git checkout -q -f --detach "$sha"
    git clean -fdq -e build -e .gradle -e native/target -e '*/build'
    if [ ! -f gradle/regress.gradle ]; then
      echo "[regress] $short predates the suite: overlaying it"
      for f in "${OVERLAY[@]}"; do mkdir -p "$(dirname "$f")"; cp -r "$SUITE/$f" "$(dirname "$f")/"; done
      printf '\n// Ostinato: regression suite (overlaid by scripts/regress.sh)\napply from: rootProject.file("gradle/regress.gradle")\n' >> build.gradle
    fi
    rm -rf build/regress
    LIVE_DIR="$OUT/$short/live"
    rm -rf "$LIVE_DIR"; mkdir -p "$LIVE_DIR"
    if [ "$LIVE" = 1 ]; then
      for s in "$SUITE"/regress/live.d/*.sh; do
        [ -x "$s" ] || continue
        echo "[regress] live stage $(basename "$s")"
        OSTINATO_REGRESS_METRICS_DIR="$LIVE_DIR" OSTINATO_REGRESS_COMMIT="$sha" OSTINATO_REGRESS_TREE="$WT" \
          nice -n "$NICE" "$s" || echo "[regress] live stage $(basename "$s") exited $?"
      done
    fi
    args=(--no-daemon --max-workers="$WORKERS" regress -Pregress_nofail -Pregress_commit="$sha" -Pregress_baseline="$BASELINE" -Pregress_extra="$LIVE_DIR")
    [ -n "$WORLDS" ] && args+=(-Pregress_worlds="$WORLDS")
    [ -n "$STAGES" ] && args+=(-Pregress_stages="$STAGES")
    mkdir -p "$OUT/$short"
    nice -n "$NICE" ./gradlew "${args[@]}" "${EXTRA[@]}" > "$OUT/$short/gradle.log" 2>&1 || echo "[regress] gradle exited $? (see $OUT/$short/gradle.log)"
    cp -r build/regress/report.md build/regress/report.json build/regress/metrics "$OUT/$short/" 2>/dev/null || true
  )
  if [ -f "$OUT/$short/report.json" ]; then
    read -r verdict nf nw ni < <(python3 - "$OUT/$short/report.json" <<'PY'
import json,sys
d=json.load(open(sys.argv[1])); f=d["findings"]
c=lambda l: sum(1 for x in f if x["level"]==l)
print(d["verdict"], c("FAIL"), c("WARN"), c("IMPROVED"))
PY
)
  else
    verdict=ERROR; nf=-; nw=-; ni=-
  fi
  [ "$verdict" = PASS ] || STATUS=1
  echo "| \`$short\` | $subject | $verdict | $nf | $nw | $ni | $short/report.md |" >> "$SUMMARY"
  echo "[regress] $short: $verdict (fail $nf, warn $nw, improved $ni)"
done
echo "[regress] summary: $SUMMARY"
exit $STATUS
