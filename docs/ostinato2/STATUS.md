# Ostinato 2.0 — status

Phase: **01 input ownership**. Phase 02 was not started.

These notes were copied onto `combine/main-maze-ppe` (`Ostinato-combined`) with the Phase 01 sources. That branch also has `origin/main`, `maze-backrooms-expand` (including the maze survival working tree), and `feat/predictive-physics`. It was not pushed. `Ostinato-secondo` was left dirty.

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

## Next

Phase 02 was not started.
