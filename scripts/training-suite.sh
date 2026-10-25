#!/usr/bin/env bash
# Ostinato: headless training circuits. No pathbench, no client.
set -euo pipefail
cd "$(dirname "$0")/.."
nice -n 10 ./gradlew --no-daemon --max-workers=2 test --tests baritone.training.TrainingSimTest
