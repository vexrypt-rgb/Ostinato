# Ostinato 2.0 — status

**Phase 04 gate: PASS WITH BLOCKERS (provisional).** It is complete at Tier U/S. Tier L is partial: three of six modes are validated live, from historical traces. Phase 05 was **not** started.

## Where

- Repo: `vexrypt-rgb/Ostinato`, local clone `Ostinato-o2review`.
- Branch: `ostinato2/claude-review`, created from the Phase 03 endpoint `09631d874`. That commit is a snapshot of Grok's uncommitted Phase 03 tree on `combine/main-maze-ppe` @ `a1e9817cd`.
- Final commit: the commit that last touched this file. The final report gives the hash.
- **Nothing was pushed, merged, or opened as a PR.** The clone's push URL is `DISABLED-no-push`. Grok's trees (`Ostinato-combined`, `Ostinato-secondo`) were not modified.
- Benchmark version: Minecraft **26.3** (DECISIONS D10).

## What was done

1. **Review of Phases 00–03**, committed before any code change: `REVIEW_00-03.md` (`b346e66de`). Verdict before fixes: all four phases **PASS WITH FIXES**.
2. **Review fixes:**
   - `review-fix(phase-01)`: the input arbiter releases leases on executor cancel, segment discard, and world change. Four new tests:
     - timeout without ack
     - one owner per resource per tick
     - `releaseAll`
     - the call sites
   - `review-fix(phase-03)` (two commits):
     - an expansion counter on `PhysicsPathfinder` (counter only)
     - p50/p95/p99, Wilson, Fisher, and bootstrap statistics
     - no more overwriting of committed docs
     - stock A* rows on the lattice worlds
   - Evidence reruns: Phase 00 at 5 reps (fingerprint identical) and Phase 03 at 20 trials.
   - DECISIONS: D9 marked **PROVISIONAL**. Its rule now reads CONTINUE, but the evidence is weak, and stock A* shows no planner-tier disadvantage.
3. **Phase 04** (`phase-04` commits), as recorded in DECISIONS D11:
   - New canonical `baritone.contract.PredictionTolerance`: per-mode one-step bounds, each with a tier and a basis.
   - Two existing consumers now use it behind `predictionTolerance` (default **false**): `SimTrace` and `PhysicsTravel`.
   - `PlayerState` and `PlayerSim.World` are declared canonical as they are.
   - `PhysicsTransition` was **not** promoted: the interface has zero consumers.
   - PlayerSim-vs-PPE cross-model test over six modes.
   - Flag-off identity tests.
   - Overhead microbench.
   - The new setting is registered in `SettingCategorizer` (MOVEMENT) and `setting-descriptions.json`, so `SettingCatalogTest` passes.

## Phase 04 gate

| Requirement | Result | Tier |
|---|---|---|
| ≥2 real consumers per canonical interface | `PredictionTolerance`: `SimTrace` (three movers) + `PhysicsTravel`. `PlayerState`: 5 consumers. `PlayerSim.World`: ≥7. | VERIFIED, U |
| Validated per-mode tolerances | SPRINT, SPRINT_JUMP, CLIMB (climb-jump): 26.3 live traces. WALK, FALL, FLUID: cross-model only. Ladder climbing proper and vines: not traced. | **L for 3 of 6**, S for 3 |
| Physics constants follow the benchmark version | No constant changed. PlayerSim stays the 1.16 model, judged against 26.3 traces. Version adapters wait for Phase 09. | U |
| p95/p99 overhead, no material regression | Consumer microbench: ≤ +0.36 µs/tick at p99 with the flag on. Flag off runs the old path. Planner bench at HEAD: see BENCHMARKS. | S. **Client tick time not measured.** |
| New paths behind flags; defaults unchanged | `predictionTolerance=false`. `inputArbitration` and `latticeExperiment` still false. | VERIFIED, U |
| Identical behavior with flags off | `PredictionToleranceTest.flagOff*`: every SimTrace row field, the verdict and summary; 100,000 random drift pairs | VERIFIED, U |

## Blockers (owner decisions or live tests)

- **B1. Tier-L bounds for WALK, FALL, FLUID, and ladder climbing proper.**
  - Needs a 26.3 client run with `kinematicTrace` on, plus movers that trace those modes. Today only the jump, chain, and climb-jump movers trace.
  - Until then, those bounds are borrowed or legacy values (Tier S).
- **B2. Client tick time p95/p99**, with `predictionTolerance` on and off. This is a Tier-L measurement; the microbench is only a proxy.
- **B3. Does flag-on `PhysicsTravel` help?**
  - With the flag on, it replans on sprint ground ticks past 0.2235 Euclidean, instead of past 0.1 summed over the axes.
  - That needs an A/B lap course in a real client before anyone turns it on.
- **B4. Phase 03 execution tier.**
  - The lattice was compared with stock A* at planner tier only.
  - An execution-tier comparison needs either a headless `PathExecutor` (D2 says no) or live runs.
  - D9 stays PROVISIONAL until then.
- **B5. A pre-2.0 test is red:** `SecondoTest.plannedApisAreAbsentOnThisBranchAndInvokeIsANoop` (F-M6). The PPE merge invalidated it. Fixing it means changing Secondo bookkeeping, which is the owner's call.
- **B6. 31 direct key writers bypass the arbiter** (F-M3). `inputArbitration` must not be turned on in production until they migrate. That migration is later-phase work.

## Next task

Phase 05, **not started**. Before it, the owner should decide:
- whether to run B1–B3 on a 26.3 client;
- whether B4's execution-tier comparison is required before the lattice continues.

## Previous status (Grok, REPORTED; kept as written)

Phase: **03 kinodynamic lattice experiment**, on `combine/main-maze-ppe` (dirty, not committed).

Gate: **REVISE**.

These notes now live on that branch in `Ostinato-combined`. It has `origin/main`, `maze-backrooms-expand` (including the maze survival working tree), `feat/predictive-physics`, and Phase 01. `Ostinato-secondo` was left dirty.

Phase 01's gate on the earlier tree was **PASS**. Phase 02's gate on this branch was **PASS** for the supported subset. Those results are unchanged.

Tree: `Ostinato-secondo`, branch `feature/secondo-v1`, commit `2f4176049617c33d13f5a3fc4ac47b2231b7cf6f`. That commit's tree matches `origin/main` `67e12b96e` before these edits. Gradle reported `1.26.0-1-g2f4176049-dirty`. Nothing was committed or pushed.

### What passed

- One complete key frame per seal. A key missing from the frame is up. A second seal with no new frame clears the movement keys. **VERIFIED** (U), `InputArbiterTest.aMissingKeyIsUpOnTheNextFrame`.
- Resource leases: move (forward, back, left, right, sprint), jump, sneak, click. Emergency beats a normal claim, then higher priority, then the smaller owner id. The winner is the same in either claim order. **VERIFIED** (U).
- A stale epoch is rejected and does not get sealed. **VERIFIED** (U).
- `requestCancel` keeps the lease and the keys. `confirmRelease` clears them and rejects the old epoch. `fail` clears the same keys, leaves the cancel flag unset, and ends `FAILED`. **VERIFIED** (U), including `FallInput.end(..., true)`.
- An emergency claim of jump alone clears jump at once and leaves forward down. Jump in the loser's later frame is ignored. **VERIFIED** (U).
- `inputArbitration` defaults to false. With it off, `FallController` still clears the key map and writes forward, back, left, right, sneak, jump, and use. That branch returns before `getInputArbiter`. **VERIFIED** (U) by the source scan in `fallChordListsEveryKeyAndTheFlagDefaultsOff`.
- Navigation tests from this session passed. 87 tests, 0 failures, 0 errors, 3 skipped. `BUILD SUCCESSFUL` in 46s. Counts are in `BENCHMARKS.md`.

### Limits

- No Minecraft client was launched. Flag-on fall steering in a world is **UNKNOWN** (L).
- `FallController.tick` calls `endLease(..., true)` from its catch. That line is in the source. A test that throws from a live player was not run. The key release is the `fail` test above.
- `FallController` is the only controller on the arbiter. Swim, physics, sim, and `Movement.update` still write `InputOverrideHandler` directly.
- With the flag on, a fall tick that returns -1 after a frame was written clears those keys before the next family driver. With the flag off, that clear stays with the next writer, as it did before this phase.

### Blockers

None. Wiring fall did not require a `PathExecutor` change.

### Phase 00

Gate was **PARTIAL**. The SHORT planner baseline is in `BENCHMARKS.md`. Headless `PathExecutor` is no. The headless `PlayerSim` tick-driver assignment was superseded before any of it was written. See `DECISIONS.md` D7.

### Recheck on the combined tree (2026-11-04)

Tree: `Ostinato-combined`, branch `combine/main-maze-ppe`. No client. `OSTINATO_BENCH` unset. Command and counts are in `BENCHMARKS.md`.

- Phase 00: `stockAStarMatchesGoldenCosts` was re-run on this tree. Gate stays **PARTIAL**. Execution ticks, CPU, live prediction error, and MEDIUM/LONG timing were not re-measured. Headless `PathExecutor` is still no.
- Phase 01: `InputArbiterTest` and `SettingCatalogTest` were re-run here. Gate stays **PASS**. Flag-on fall in a world is still **UNKNOWN** (L). `inputArbitration` still defaults to false.
- Phase 02: gate **PASS** for the supported subset. `physics-search` and `trivial-sprint` share one PlayerSim world, one settled start, and the same budgets, and both reach the flat goal in 18 ticks (end `0.5000 0.0000 5.2136`). PPE ran and is labeled `NOT_EQUIVALENT` (`PPE-26.3-living-tick`, held input, feet y=64). Stock A* is named and not executed. Report: `docs/ostinato2/raw/phase02-flat.txt`.

### Phase 03

Gate **REVISE**. The lattice is an isolated prototype under `baritone.lattice`. `Settings.latticeExperiment` defaults to false. Path execution, A*, `PathingBehavior`, and `KinematicController` do not reference it. `PlayerSim` and `PhysicsPathfinder` were not edited. `PlayerSimPhysics.face` only records yaw; it does not tick.

Both beam search and weighted A* solved 12/12 development cases. `PhysicsPathfinder` also solved 12/12 on the same worlds, starts, goals, radius 0.5, and node budget 1500. Chosen method is beam (equal success, lower median travel ticks). Shared-solve median ticks are beam 18.0 versus physics 17.5 (ratio 1.029, bound 1.15). Max prediction divergence is 0. Three trials matched, and a second in-process run matched the discrete columns. Held-out ran (dev success was above 0.7): beam 16/16, weighted 16/16, physics 14/16. Physics missed `gap-2-far` and `corner-long` inside the node budget. Baseline expansion counts are UNKNOWN, so the 3x expansion clause cannot pass. That is why the gate is REVISE rather than CONTINUE, PREFER SIMULATED A*, or REJECT.

Raw rows: `docs/ostinato2/raw/phase03-trials.csv` (252 data rows). Summary: `docs/ostinato2/raw/phase03-summary.txt`. Semantics `PlayerSim-1.16-land-tick`. No live client. The 400-tick lattice cap did not bind.

### Next

Phase 04 was not started.
