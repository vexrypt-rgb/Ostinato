#!/usr/bin/env bash
# Ostinato: headless tests for the travel stack. No pathbench, no client.
# Usage: scripts/travelstack-smoke.sh
set -e
nice -n 10 ./gradlew --no-daemon --max-workers=2 test \
  --tests baritone.pathing.travel.TravelStackTest \
  --tests baritone.pathing.kinematic.SimProbeTest
