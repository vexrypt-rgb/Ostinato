#!/usr/bin/env bash
# Ostinato: Phase 1 pathfinding perf benchmark. Usage: scripts/pathbench-phase1.sh <label> [normal|pi]
# Ostinato: 5 reps by default (2 was within noise for ~3% changes). normal: all cores, default heap. pi: 1 core (taskset -c 0) and a 512 MB heap.
set -e
label=${1:-run}; prof=${2:-normal}
args=(--no-daemon --max-workers=2 -q cleanTest test -x nativeHost --tests baritone.pathing.calc.PathBenchTest)
pre=(nice -n 10)
if [ "$prof" = pi ]; then pre=(taskset -c 0 nice -n 10); args+=(-PbenchXmx=512m -Dorg.gradle.workers.max=1); fi
OSTINATO_BENCH="$label-$prof" OSTINATO_BENCH_REPS=${REPS:-5} "${pre[@]}" ./gradlew "${args[@]}"
cat "build/pathbench/$label-$prof.md"
