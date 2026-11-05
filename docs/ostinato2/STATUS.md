# Ostinato 2.0 — status

Phase: **02 shared contracts**, rechecked on `combine/main-maze-ppe` after the maze and predictive-physics merge.

These notes now live on that branch in `Ostinato-combined`. It has `origin/main`, `maze-backrooms-expand` (including the maze survival working tree), `feat/predictive-physics`, and Phase 01. `Ostinato-secondo` was left dirty.

Gate: **PASS**.

Tree: `Ostinato-secondo`, branch `feature/secondo-v1`, commit `2f4176049617c33d13f5a3fc4ac47b2231b7cf6f`. That commit's tree matches `origin/main` `67e12b96e` before these edits. Gradle reported `1.26.0-1-g2f4176049-dirty`. Nothing was committed or pushed.

## What passed

- One complete key frame per seal. A key missing from the frame is up. A second seal with no new frame clears the movement keys. **VERIFIED** (U), `InputArbiterTest.aMissingKeyIsUpOnTheNextFrame`.
- Resource leases: move (forward, back, left, right, sprint), jump, sneak, click. Emergency beats a normal claim, then higher priority, then the smaller owner id. The winner is the same in either claim order. **VERIFIED** (U).
- A stale epoch is rejected and does not get sealed. **VERIFIED** (U).
- `requestCancel` keeps the lease and the keys. `confirmRelease` clears them and rejects the old epoch. `fail` clears the same keys, leaves the cancel flag unset, and ends `FAILED`. **VERIFIED** (U), including `FallInput.end(..., true)`.
- An emergency claim of jump alone clears jump at once and leaves forward down. Jump in the loser's later frame is ignored. **VERIFIED** (U).
- `inputArbitration` defaults to false. With it off, `FallController` still clears the key map and writes forward, back, left, right, sneak, jump, and use. That branch returns before `getInputArbiter`. **VERIFIED** (U) by the source scan in `fallChordListsEveryKeyAndTheFlagDefaultsOff`.
- Navigation tests from this session passed. 87 tests, 0 failures, 0 errors, 3 skipped. `BUILD SUCCESSFUL` in 46s. Counts are in `BENCHMARKS.md`.

## Limits

- No Minecraft client was launched. Flag-on fall steering in a world is **UNKNOWN** (L).
- `FallController.tick` calls `endLease(..., true)` from its catch. That line is in the source. A test that throws from a live player was not run. The key release is the `fail` test above.
- `FallController` is the only controller on the arbiter. Swim, physics, sim, and `Movement.update` still write `InputOverrideHandler` directly.
- With the flag on, a fall tick that returns -1 after a frame was written clears those keys before the next family driver. With the flag off, that clear stays with the next writer, as it did before this phase.

## Blockers

None. Wiring fall did not require a `PathExecutor` change.

## Phase 00

Gate was **PARTIAL**. The SHORT planner baseline is in `BENCHMARKS.md`. Headless `PathExecutor` is no. The headless `PlayerSim` tick-driver assignment was superseded before any of it was written. See `DECISIONS.md` D7.

## Recheck on the combined tree (2026-11-04)

Tree: `Ostinato-combined`, branch `combine/main-maze-ppe`. No client. `OSTINATO_BENCH` unset. Command and counts are in `BENCHMARKS.md`.

- Phase 00: `stockAStarMatchesGoldenCosts` was re-run on this tree. Gate stays **PARTIAL**. Execution ticks, CPU, live prediction error, and MEDIUM/LONG timing were not re-measured. Headless `PathExecutor` is still no.
- Phase 01: `InputArbiterTest` and `SettingCatalogTest` were re-run here. Gate stays **PASS**. Flag-on fall in a world is still **UNKNOWN** (L). `inputArbitration` still defaults to false.
- Phase 02: gate **PASS** for the supported subset. `physics-search` and `trivial-sprint` share one PlayerSim world, one settled start, and the same budgets, and both reach the flat goal in 18 ticks (end `0.5000 0.0000 5.2136`). PPE ran and is labeled `NOT_EQUIVALENT` (`PPE-26.3-living-tick`, held input, feet y=64). Stock A* is named and not executed. Report: `docs/ostinato2/raw/phase02-flat.txt`.

## Next

Phase 03 was not started.
