# Ostinato 2.0 — status

**Phase 04 gate: PROVISIONAL after live validation** (DECISIONS D14, 2026-11-08).
- All six modes have validated Tier-L bounds for the 26.3 (D12) model.
- The flag-on A/B shows no improvement and no regression.
- Client tick-time overhead with the flag on is inconclusive: p95 +0.59 ms, CI −0.01 .. +1.22 ms, against a 0.5 ms criterion.
- `predictionTolerance` stays default **false**.

**Phase 05 gate (unchanged): NO IMPROVEMENT. Legacy A* retained** (DECISIONS D13). Simulation-derived A* is built, tested, and kept behind `simEdgeSearch` (default **false**). Paired live A/B runs on a 26.3 client showed no reliability regression but slower laps and no gain in prediction accuracy. The Phase 04 validation did not touch it. Phase 06 was **not** started.

**Phase 05B diagnosis: class C, NO IMPROVEMENT** (DECISIONS D15, 2026-11-08).
- SimEdge's loss is first-plan latency: 12–16 motion states per cell inside the 32-block horizon. It is not the
  physics, and not the controller.
- The one pre-registered experiment ran SimEdge with `simEdgeHorizon` 8 (2 seeds × 96 laps). It cut the wait from
  12–13 ticks to 4–5 and lost no laps.
- It still did not beat legacy A* (D/A 0.978–1.015), and beat the existing SimEdge on ≥ 2 legs in one seed only.
- SimEdge stays disabled, kept as research code. No production default changed. Phase 06 was **not** started.

## Where

- Repo: `vexrypt-rgb/Ostinato`, local clone `Ostinato-o2review`.
- Branch: `ostinato2/claude-review`, created from the Phase 03 endpoint `09631d874`. That commit is a snapshot of Grok's uncommitted Phase 03 tree on `combine/main-maze-ppe` @ `a1e9817cd`.
- Final commit: the commit that last touched this file. The final report gives the hash.
- **Nothing was pushed, merged, or opened as a PR.** The clone's push URL is `DISABLED-no-push`. Grok's trees (`Ostinato-combined`, `Ostinato-secondo`) were not modified.
- Benchmark version: Minecraft **26.3** (DECISIONS D10). Since physics-263 (D12), `PlayerSim`, its native mirror and the other predictors model 26.3 too.

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
4. **physics-263** (owner request after Phase 04; DECISIONS D12):
   - `PlayerSim` and the Rust mirror `native/src/sim.rs` now follow the 26.3 rules. Native parity is bitwise.
   - PPE's sprint speed was one float ulp low; fixed.
   - The spectate `InputDecoder` now uses 26.3's client input pipeline.
   - `ItemThrow` and eight inline fall predictors now use 26.3's float math.
   - New test class `PlayerSim263Test` pins the version-specific rules against values read from the decompiled 26.3 client.
   - Flag-off identity tests.
   - Overhead microbench.
   - The new setting is registered in `SettingCategorizer` (MOVEMENT) and `setting-descriptions.json`, so `SettingCatalogTest` passes.
5. **Phase 04 live validation** (2026-11-08; DECISIONS D14; criteria pre-registered in BENCHMARKS before any data):
   - Live 26.3 traces of all six modes: dev seed 81001, held-out seed 81002, ≥ 2000 usable ticks per mode per session.
     They come from a new scripted harness, `ToleranceTraceTest`.
   - `PredictionTolerance.MEASURED` is replaced by the validated dev p99s (Tier L). This changes flag-on only.
   - Client tick time, from a new `TickClock` (harness-only).
   - A paired flag on/off A/B on `SimEdgeCourse`, physics stack, 192 laps (seeds 81011, 81012).
   - Raw rows, per-run outputs and analysis: `docs/ostinato2/raw/phase04/`.

## Phase 04 gate

| Requirement | Result | Tier |
|---|---|---|
| ≥2 real consumers per canonical interface | `PredictionTolerance`: `SimTrace` (three movers) + `PhysicsTravel`. `PlayerState`: 5 consumers. `PlayerSim.World`: ≥7. | VERIFIED, U |
| Validated per-mode tolerances | **All six modes**, live 26.3 traces of the D12 model (D14). Dev 81001 / held-out 81002, ≥ 2000 usable ticks per mode per session. Held-out share over the dev p99 is 0–1.54% (criterion ≤ 2.0%). Ladders and vines both traced; FLUID with `tickWater`. | **L for 6 of 6** |
| Physics constants follow the benchmark version | **Changed by owner request (D12):** PlayerSim, native sim, PPE, decoder and the inline predictors follow 26.3. Cost estimators (`TickSim`, `SurfaceSpeed`) already match 26.3's structure to float rounding and were left alone. **Live (D14):** with the yaw the client applied, the model's one-step p99 is 0 in every mode. | VERIFIED, U/S/**L** |
| p95/p99 overhead, no material regression | Client tick time, 96 lap pairs (flag on − off). p95: +0.59 ms, CI −0.01 .. +1.22 ms, **inconclusive** against 0.5 ms. p99: −2.98 ms, CI −4.51 .. −1.30 ms, acceptable. The cost is extra drift replans (1650 on vs 60 off). Flag off runs the old path. | L. **Inconclusive** |
| Flag-on navigation effect | Live A/B, 192 laps (seeds 81011, 81012). Reliability holds (192/192 reached; no faults, stuck keys, deaths or cancels). Lap-tick ratios 0.962–1.015. **No improvement, no regression.** | L |
| New paths behind flags; defaults unchanged | `predictionTolerance=false`. `inputArbitration` and `latticeExperiment` still false. | VERIFIED, U |
| Identical behavior with flags off | `PredictionToleranceTest.flagOff*`: every SimTrace row field, the verdict and summary; 100,000 random drift pairs | VERIFIED, U |

## Phase 05 (simulation-derived A*)

- **Audit first:** `PHASE05_AUDIT.md` covers hashing, equivalence, dominance, pruning, heuristic validity, reopening,
  reconstruction, edge start state and the executor policy the sim copies. §11 records three changes made during
  implementation: the corridor, the half-block correction and the horizon.
- **Code** (`phase-05` commit, all behind `simEdgeSearch=false`):
  - `SimEdge` (the simulated edge evaluator) and `SimEdgePathFinder` (the search over cell × motion class);
  - `PathNode.body`, and `PathingBehavior.playerBody()` plus a branch in `createPathfinder`;
  - three settings: `simEdgeSearch`, `simEdgeMaxStates`, `simEdgeHorizon`;
  - the live course `SimEdgeCourse` and harness arms `-Dostinato.legatotest.arms=simedge` in `LegatoLiveTest`;
  - the analysis script `scripts/simedge-ab.py`.
- **Tests:** `SimEdgeTest` (15 tests plus an env-gated benchmark) and `SimEdgeCourseTest` (2) pass. `settingsDefaultOff`
  pins the default. Full suite: 1535 tests, 1 failed (the pre-existing B5 `SecondoTest`), 19 skipped.
- **Evidence** (BENCHMARKS, Phase 05):

| tier | result |
|---|---|
| S, headless, 60 queries × 2 | Horizon 32: 5 failures vs A*'s 4 (all timeouts), 1.5–3× plan time on hard terrain. Route price under the sim model 0.995–1.000 of A*'s. |
| L, seed 71006, 96 laps | Reliability holds (96/96). Laps 3–9% slower on 3 of 4 legs. Sim-model prediction error 0.032 vs legacy 0.020. |
| L, seed 71007, 96 laps | Reproduces 71006: reliability holds (96/96), ridges 7–10% and slalom 3% slower, bumps 0.8% faster, sim-model predictions slightly worse. |

- **Classification:** no improvement on any pre-registered measure. Legacy behaviour is retained, and no default
  changed. No baseline fixes were needed: legacy and executor code are untouched.

## Blockers

**Phase 04 gate items (D14; evidence in BENCHMARKS "Phase 04 live validation"):**

- **B1. RESOLVED.** All six modes validated, Tier L, for the D12 model.
  - The dev p99s are in `MEASURED`. The 1.16-model D11 numbers are no longer in the table.
  - Coverage limits:
    - turns ≤ 30°/tick;
    - one course;
    - no slime, honey, ice, soul sand, flowing water, lava or sneaking;
    - one-step only.
- **B2. MEASURED, INCONCLUSIVE.** This is the only reason the gate is PROVISIONAL rather than PASS.
  - With the flag on, the p95 CI crosses 0.5 ms.
  - The cost is real and its source is known: about 17 extra `PhysicsPathfinder` replans a lap, +26 ms of
    `PhysicsTravel` time per lap.
  - With the flag off (the default) there is no cost.
  - Closing it needs a new pre-registered measurement, or a cheaper drift check (new design, own A/B). It was not
    re-run here, because the pre-registration fixed two runs.
- **B3. MEASURED: NO IMPROVEMENT.** The tight bounds replan more without any gain in lap time, recovery or
  reliability. The flag stays off. No navigation benefit is claimed.

**Not Phase 04 gate items. Assessed only for their effect on the gate and on Phase 05 (2026-11-08):**

- **B4. Phase 03 execution tier: OPEN, unchanged.**
  - The lattice was compared with stock A* at planner tier only. D9 stays PROVISIONAL.
  - Effect on the Phase 04 gate: none.
  - **Phase 05 dependency: none.** No class outside `baritone.lattice` references it (grep of `src/main/java`), and
    `SimEdge`/`SimEdgePathFinder` do not use it.
- **B5. `SecondoTest.plannedApisAreAbsentOnThisBranchAndInvokeIsANoop` red: OPEN, unchanged** (F-M6).
  - It is a stale assertion that `baritone.api.physics.IPredictivePhysics` is absent; the PPE merge added it.
  - Effect on the Phase 04 gate: none, because it tests Secondo bookkeeping, not physics or tolerance.
  - **Phase 05 dependency: none functionally.** But the full suite can never run green until the owner updates
    Secondo bookkeeping, so "full suite green" cannot serve as a gate signal. Gates use named test classes instead.
- **B6. Direct key writers bypass the arbiter: OPEN, unchanged** (F-M3).
  - 267 `setInputForceState`/`clearAllKeys` sites in 31 production files, recounted on this tree. The Phase 04
    harness `ToleranceTraceTest` adds 5 sites in 1 harness-only file, off by default.
  - Only `FallController`/`FallInput` use the arbiter.
  - It blocks turning `inputArbitration` on in production.
  - Effect on the Phase 04 gate: none.
  - **Phase 05 dependency: none.** `SimEdge` writes no keys, and Phase 05 paths execute through the legacy `Movement`
    classes and drivers. A future Phase 05 retry that adds a new driver would add a writer to this list.

## Phase 05 readiness (decision, 2026-11-08)

Phase 05 already ran before this validation (`72e5878a7`, D13: NO IMPROVEMENT, `simEdgeSearch` default off). It was
not changed or re-run here.

**Decision: Phase 04 does not block Phase 05 work, and gives no evidence-based reason to start it.** What Phase 05
actually depends on, and where each item stands:

| Phase 05 dependency | Status after D14 |
|---|---|
| `PlayerSim` one-step accuracy for walk, sprint and jump (`SimEdge` prices edges by running it) | **Tier L.** Exact given the applied yaw; p99 0.0035–0.0047 (ground) and 0.00045 (air) with the commanded yaw |
| `PredictionTolerance.classify` and `MEASURED` (`SimEdge`'s `drift` field) | Tier-L table. `drift` is not read by the search or the cost, so no Phase 05 behaviour changed; only its values shrank. `SimEdgeTest` passes |
| `predictionTolerance` flag; B2; B3 | Not used by Phase 05 |
| B4 lattice; B5 Secondo; B6 arbiter | Not used by Phase 05 (above) |

What the Phase 04 evidence does **not** establish for Phase 05:

- multi-tick open-loop accuracy;
- the executor's controller model. D13 reason 2: the kinematic driver drives most of a lap, and `SimEdge` does not
  simulate it.

One-step bounds cover neither. They do narrow D13's diagnosis: one tick ahead, the physics model is exact, so a
Phase 05 retry should target plan time and the controller model, not `PlayerSim`.

## Phase 05B (SimEdge diagnosis, 2026-11-08)

Scope: diagnosis and one bounded experiment. No A* redesign, physics rewrite, lattice, controller refactor or
default change; nothing pushed or merged.

- **Commits** (local, `ostinato2/claude-review`):

  | commit | content |
  |---|---|
  | `e071cc7d9` | flag-gated diagnostics |
  | `5b26423b5` | diagnosis and pre-registration, written before any experiment data |
  | `b78f7d56b` | the experiment's harness arms `simedge5b` |
  | the commit that last touched this file | results |

- **Diagnosis** (BENCHMARKS "Phase 05B"):
  - The first plan exceeds the 500 ms budget on 3 of 4 legs. About 7 sims and about 100 legacy move evaluations run
    per expansion, with 12–16 states per cell, and 175 MB is allocated per search.
  - That latency is 70–100% of the lap loss.
  - Per-edge timing from the real entry state is accurate. Multi-tick drift under SimEdge's assumed steering is 2–4×
    the recorded-input drift at p90.
  - The routes are followable, but worth 0–3 ticks a lap.
- **Experiment** (`simEdgeHorizon` 8, seeds 71006 and 71007, 6 reps each):

  | check | result |
  |---|---|
  | Reliability | 24/24 per arm, no faults or stuck keys |
  | D − A first-plan wait | ≤ 3 ticks on every leg |
  | M1 against legacy A* | not met |
  | M2 against the existing SimEdge | met in 71006 only |

  **Class C.**
- **Archive rule:** steps D/A ≤ 0.99 in both seeds, so keep SimEdge as disabled research code.
- **Full suite** at the results commit: see BENCHMARKS "Phase 05B". The known SecondoTest failure is expected.

## Next task

**Owner decisions. Phase 05B is complete. Phase 06 was not started.**

1. Accept the Phase 04 gate as PROVISIONAL with `predictionTolerance` off. The alternative is to fund a new
   pre-registered drift-check design (bound × margin, or persistence) and its own A/B.
2. SimEdge (D15):
   - Keep it disabled.
   - Do not fund another latency fix on this course: even zero wait gains about 2–3% on steps only.
   - It can only pay off on momentum-decided routes (gaps, sprint jumps, long ascent chains). That needs a new
     pre-registered scenario family as its own phase. Otherwise archive it.
3. Separately:
   - `SimTrace`'s land prediction on water ticks (D14 finding 2);
   - B5 Secondo bookkeeping;
   - B6 key-writer migration;
   - B4 execution tier.

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
