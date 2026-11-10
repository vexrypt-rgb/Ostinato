# Ostinato 2.0 — status

**Phase 10: PARTIAL. Release readiness CONDITIONAL. No default changed, nothing promoted** (DECISIONS D21,
FINAL_REPORT.md, 2026-11-09).
- Production defaults validated on 26.3 Fabric: lap course 96/96 reached, A/A ratios 0.996–1.004 (Run P); suite
  1645 / 0 failed / 24 skipped (was 1 failed).
- B6, Critical, fixed: `AirProcess` left SNEAK forced on an idle player after magma (keyboard ignored). STUCK 2/2
  before, CLEAR 12/12 held out. The suspected surfacing JUMP leak did not reproduce (CLEAR 6/6); not changed.
- B5 closed: `SecondoTest` was stale (written before the merges that added the six types), not a regression.
- `verifiedGetToBlock` on natural terrain (Run V, 180 laps): **DEFER** by the pre-registered rule. 0 false successes
  with it on (24 off), but T2/T3/T4 fail on seed 90012, through a navigation stall and a stop-release lag seen in
  both arms. Stays default false.
- Disposition: RETAIN interaction contract, magma fix, physics-263, `PredictionTolerance` contract, harnesses;
  DEFER `verifiedGetToBlock`, `unifiedExecutor`, `inputArbitration`, `pathingStateIdCache`, `pathingHierarchical`,
  `pathDecisionMatrix`; RETIRE (recommend, code kept) `predictionTolerance`, `simEdgeSearch`, `latticeExperiment`.
- No further phase was started.

**Phase 09 gate: PASS. No flag added; no default changed** (DECISIONS D20, 2026-11-09).
- Slice A: the interaction capability's container observation behind a version adapter. `GetToBlockProcess` and
  the task runner no longer name a menu class; `ClientContainers` (26.3) is the only class in the slice that does.
- Phase 08's verified-open rule now lives in the Minecraft-free `OpenVerifier` (unit-tested for the first time); the
  runner judges GET_TO_BLOCK through `IGetToBlockProcess` and `ProcessOutcome` only.
- Second version: the unchanged contract and rule compile at Java 8 against the 1.16.5 MCP names with a 1.16 adapter
  (compile only). `ProcessOutcome` is a record and does not: a back-port blocker.
- Held out (Tier L, seeds 89011/89012, Phase 08 build vs Phase 09 build, 288 laps): 160/160 rows the same oracle class,
  every arm-B outcome identical, both flag states; Phase 08's gates all pass on the new build; OPEN, failure and
  cancel latencies unchanged; runner +2.1 µs per tick (bound 5).
- `verifiedGetToBlock` stays default false. Phase 10 came later (D21).

**Phase 08 gate: PASS. `verifiedGetToBlock` stays default false** (DECISIONS D19, 2026-11-09).
- Workflow C, navigate → interact → verify (`goto <container>` and the task runner's GET_TO_BLOCK step).
- Baseline defect (legacy, live): a refused, unanswered or preempted open, a container left open, or a click that
  lands after a cancel is reported as done; a target that does not exist is explored for forever; the async rescan
  outlives its request (NPE).
- Slice: numbered request outcomes on `GetToBlockProcess` (always recorded, no behaviour change), and, behind the new
  default-false flag, success only on a server-opened menu of the right type from this request's click, failure with
  a reason otherwise, and a runner step that checks both.
- Held out (Tier L, seeds 88011/88012, 144 laps): flag on, 48/48 possible rows succeed, 0 false successes (legacy 40),
  32/32 impossible rows fail honestly (legacy 0, 8 hangs), no leak, no death, no exception; OPEN unchanged (37
  ticks), runner +2.9 µs per tick. Every pre-registered gate passes on each seed.
- No default changed. Phase 09 came later (D20).

**Phase 07 gate: FAIL. `pathingStateIdCache` stays default false** (DECISIONS D18, 2026-11-09).
- Long-distance routing. No new hierarchy: planning wait is 0.26% of trip ticks over 116 held-out headless trips, so
  a global layer has almost no travel time to save. The existing hierarchy stays off.
- The slice is a 1024-entry block-state id cache at the profiled hotspot (Option A), behind a new default-false flag.
  It changes no id and no cost.
- Held out (Tier S): about 4% less planning time per node (long routes 0.9624, CI 0.9565–0.9686; PathBench SHORT
  0.9429). No reliability or travel change, and cancel is unchanged.
- **Why FAIL:**
  - Guard G3 (exact equivalence) fails as written on 8 goal-mode pairs. The verified cause is a harness pairing
    defect: the two arms switch to different goals. Every correctly paired trip is identical.
  - The 30% target (P1) is missed in any case: the "40%" profile hotspot was safepoint bias, about 6% in truth.
- The live A/B was not run (the failure rule: stop). Nothing is claimed at Tier L.
- Phase 08 came later (D19).

**Phase 06A gate: PASS. `unifiedExecutor` stays default false** (DECISIONS D17, 2026-11-09).
- Phase 06A resolved the two Phase 06 blockers behind the same default-false flag. Phase 06 stays **FAIL** (below).
- **S1, input ownership:** on the lap-end cancel, `PathingBehavior.cancelEverything()` now tells the executor before
  any process's `onLostControl()` runs. Live, no executor counter moved between laps in either seed (Phase 06: 43 and
  41 early releases there), with 41 process cancels per seed. The other writers stay open (B6).
- **S2, slip reliability:** one bounded 3000-node retry when the replan after a correction comes back empty. On
  slippery ground it now arrives in 252 of 300 headless trials against legacy's 249 (Phase 06: 239 against 247).
  The retry never fired live, so S2 is evidenced at Tier S only.
- Live (Tier L), 2 fresh seeds × 96 laps: no improvement, no regression; 96/96 laps reached in each seed;
  client-tick overhead inconclusive (p95 +0.27 ms, CI −0.20..+0.91).
- **Promotion: not met.** Enabling the flag needs promotion evidence and the owner's separate authorization.
- Phase 07 came later (D18).

**Phase 06 gate: FAIL. `unifiedExecutor` stays default false** (DECISIONS D16, 2026-11-08).
- The unified executor owns the physics driver's movement keys behind a default-false flag. It was judged against
  criteria committed before any run.
- Ownership held in the headless batch and in every live lap: no early release, overwrite, stale write or
  conflicting press landed, and no key or lease was left behind (Tier S H3, Tier L L2).
- **Not exclusive on the live cancel path:** outside the laps, the lap-end cancel released the executor's keys
  without telling it (43 and 41 early releases in the two seeds), through the `clearAllKeys()` calls in four
  processes' `onLostControl()`. No key stayed down, but on the task's rule this alone keeps the flag off.
- Fewer replans in every disturbed headless family, 15% faster under conflicting inputs, cancel in 1 tick (median),
  and acceptable overhead (Tier S).
- **Fails on reliability:** on slippery ground it arrived in 239 of 300 headless trials against legacy's 247 (allowed
  loss 3). The lost trials end with the A* planner returning no plan.
- Live (Tier L), 2 seeds × 96 laps: IMPROVEMENT, with fewer drift replans in both seeds; 96/96 laps reached in each
  seed and lap times unchanged; client-tick overhead inconclusive (p95 +0.49 ms, CI +0.06..+1.17).
- Phase 07 came later (D18).

**Phase 04 gate: PROVISIONAL after live validation** (DECISIONS D14, 2026-11-08).
- All six modes have validated Tier-L bounds for the 26.3 (D12) model.
- The flag-on A/B shows no improvement and no regression.
- Client tick-time overhead with the flag on is inconclusive: p95 +0.59 ms, CI −0.01 .. +1.22 ms, against a 0.5 ms criterion.
- `predictionTolerance` stays default **false**.

**Phase 05 gate (unchanged): NO IMPROVEMENT. Legacy A* retained** (DECISIONS D13). Simulation-derived A* is built, tested, and kept behind `simEdgeSearch` (default **false**). Paired live A/B runs on a 26.3 client showed no reliability regression but slower laps and no gain in prediction accuracy. The Phase 04 validation did not touch it. Phase 06 came later (D16).

**Phase 05B diagnosis: class C, NO IMPROVEMENT** (DECISIONS D15, 2026-11-08).
- SimEdge's loss is first-plan latency: 12–16 motion states per cell inside the 32-block horizon. It is not the
  physics, and not the controller.
- The one pre-registered experiment ran SimEdge with `simEdgeHorizon` 8 (2 seeds × 96 laps). It cut the wait from
  12–13 ticks to 4–5 and lost no laps.
- It still did not beat legacy A* (D/A 0.978–1.015), and beat the existing SimEdge on ≥ 2 legs in one seed only.
- SimEdge stays disabled, kept as research code. No production default changed. Phase 06 came later (D16).

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
6. **Phase 06** (2026-11-08; DECISIONS D16; criteria pre-registered in BENCHMARKS before any data):
   - `UnifiedExecutor`: one lease, with an epoch, on the physics driver's MOVE, JUMP and SNEAK keys. Stale writes are
     rejected, other writers' presses are refused while it drives, a cancel is acknowledged only once the player is
     stopped on the ground, a fall preempts it, and every end releases the keys.
   - `CorrectionPolicy`: on a predicted-vs-observed miss, one bounded correction, then the existing drift replan.
   - All behind `unifiedExecutor` (default **false**). The A* planner, every other key writer and
     `predictionTolerance` are unchanged.
   - A headless harness and a 1800-trial batch, a live A/B on `SimEdgeCourse` (seeds 81011, 81012), and an
     exploratory slip diagnostic after the gate.
   - Raw rows, per-run outputs and analysis: `docs/ostinato2/raw/phase06/`.
7. **Phase 06A** (2026-11-09; DECISIONS D17; criteria pre-registered in BENCHMARKS before the S2 change and before
   any run):
   - `UnifiedExecutor.processesCancelled()`: on the refused branch of `PathingBehavior.cancelEverything()`, the
     executor lifts its keys and ends its lease before any process's `onLostControl()` runs.
   - One bounded retry (`CorrectionPolicy.RETRY_NODES` = 3000) when the replan after a correction returns no path.
   - Both behind the default-false flag. The A* first-search budget, the physics model and every other key writer
     are unchanged.
   - A 1800-trial headless batch (seed 9091) and a live A/B on `SimEdgeCourse` (seeds 86021, 86022).
   - Raw rows, per-run outputs and analysis: `docs/ostinato2/raw/phase06a/`.
8. **Phase 07** (2026-11-09; DECISIONS D18; criteria pre-registered in BENCHMARKS before any held-out or live run):
   - Audit of the long-trip path, and `LongRouteSim`, a headless whole-trip simulator over 8 synthetic families and
     5 modes (known, horizon, change, goal, cancel).
   - Baseline, dev corpus and a JFR profile, then a decision gate (Option A, no new hierarchy).
   - `StateIdCache` behind `pathingStateIdCache` (default **false**).
   - A held-out headless A/B (long routes, seeds 3001 and 3002; PathBench, seed 3101).
   - Raw rows, per-run outputs and analysis: `docs/ostinato2/raw/phase07/`.

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
- **B6. Direct key writers bypass the arbiter: OPEN** (F-M3).
  - 267 `setInputForceState`/`clearAllKeys` sites in 31 production files, recounted on this tree. The Phase 04
    harness `ToleranceTraceTest` adds 5 sites in 1 harness-only file, off by default.
  - **Phase 06 recount** (raw `grep`, which also counts comments and the harness): 277 matches in 33 files at the
    Phase 06 base `b27153fdf`, 279 at its head. Phase 06 migrated none of them. Only the `PhysicsTravel` path is
    owned, and only with `unifiedExecutor` on: while that lease is held, the gate refuses other writers' presses on
    MOVE, JUMP and SNEAK. With the flag off nothing changed.
  - **Phase 06 live finding:** releases always pass the gate, and on the lap-end cancel
    `PathingBehavior.cancelEverything()` reaches `onLostControl()` in `BastionProcess`, `GetToBlockProcess`,
    `PveProcess` and `PvpProcess`, which call `clearAllKeys()` directly. They release the executor's keys without
    telling it (43 and 41 early releases outside the laps). Follow-up: `cancelEverything()` tells the executor
    first, and those calls go through the arbiter.
  - **Phase 06A:** `PathingBehavior.cancelEverything()` now tells the executor first, on its refused branch
    (`0c354b804`). Live, no executor counter moved between laps. The four `clearAllKeys()` calls and the other direct
    `onLostControl()` callers (`SwarmBehavior`, `HybridMovementEngine`, `BombardLiveTest`) are not migrated, and the
    raw count is unchanged: 279 matches in 33 files.
  - Only `FallController`/`FallInput` use the arbiter (and the unified executor, flag on).
  - It blocks turning `inputArbitration` on in production, and it limits what the unified executor can own.
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

## Phase 06 (unified executor, 2026-11-08)

Scope: one owner for the physics driver's input, a predicted-vs-observed check, one bounded correction, all behind a
default-false flag. No A* replacement, no migration of other input sites, no `predictionTolerance` change, nothing
pushed or merged.

- **Commits** (local, `ostinato2/claude-review`, base `b27153fdf`):

  | commit | content |
  |---|---|
  | `2bdbca77c` | executor wiring behind the default-false flag |
  | `d22d54c63` | ownership tests |
  | `893f289e4` | the correction verdict moved into `CorrectionPolicy` |
  | `5159b4215` | correction policy tests |
  | `ba54b8934` | headless scenarios |
  | `a6ed76df8` | the live harness's unified arm |
  | `5138d1b15` | the headless batch bench and the analysis scripts |
  | `f7ad9d0af` | pre-registration, written before any run |
  | `dbade5d5d` | the exploratory slip diagnostic, after the gate |
  | the commit that last touched this file | results |

- **Tests:** `UnifiedExecutorTest` (27), `CorrectionPolicyTest` (17), `UnifiedHeadlessTest` (12) pass. Full suite:
  1595 tests, 1 failed (the pre-existing B5 `SecondoTest`), 23 skipped.
- **Evidence** (BENCHMARKS "Phase 06 results"):

  | tier | result |
  |---|---|
  | S, headless batch, 1800 trials × 2 | H1 clean identical (0 mismatches over 300). **H2 FAILS on slip**: 239 vs 247 arrived (allowed 3). H3 safety holds. H4 bounded. H5 fewer replans in all 4 disturbed families, no regression, conflict 15% faster. H6 cancel median 1 tick, max 12. H7 overhead acceptable (p95 +0.6 µs). |
  | L, seed 81011, 96 laps | Reliability holds (96/96, no stuck key, death or new fault). L2 holds per lap. Drift replans lower in both pairs (−0.458 and −0.542 a lap). Lap ticks unchanged; one slow leg (D/C ridges 1.033) does not repeat. |
  | L, seed 81012, 96 laps | Reproduces 81011: 96/96, L2 holds per lap, drift replans lower in both pairs (−0.458 a lap each), lap ticks unchanged. Overhead, both seeds pooled: p95 +0.491 ms (CI +0.061..+1.170), **inconclusive**. Tier L verdict: **IMPROVEMENT**. |
  | exploratory, after the gate | Slip loss is a planner node-budget effect. At 1500 (the production budget too) it reproduces the batch exactly; at ≥ 6000 both arms arrive 300/300, and unified spends less plan time. Not gate evidence. |

- **Classification: FAIL** (pre-registered rule: a reliability failure, H2). `unifiedExecutor` stays default false.
  The criterion was not changed and the batch was not re-run.
- **Ownership is not exclusive on the live cancel path** (BENCHMARKS, finding outside the laps; B6). On the
  task's rule this alone keeps the executor off, whatever the gate.

## Phase 06A (executor safety and reliability recovery, 2026-11-09)

Scope: resolve the two Phase 06 blockers, S1 (input ownership on the lap-end cancel) and S2 (the slip reliability
loss), with minimal changes behind the same default-false flag. No A* replacement, no physics rewrite, no general
recovery framework, no broad migration of input writers, no default change, nothing pushed or merged.

- **Commits** (local, `ostinato2/claude-review`, base `754afb520`):

  | commit | content |
  |---|---|
  | `0c354b804` | S1: the executor gives up its lease before the processes are cancelled |
  | `228d655f0` | ownership tests |
  | `5dc9e6b4f` | pre-registration, written before the S2 change and before any run |
  | `374570484` | S2: one bounded 3000-node retry |
  | `48fec9906` | retry tests and analysis-script checks |
  | `e65850c7e` | headless batch raw evidence |
  | `deec15be1` | live A/B raw evidence |
  | the commit that last touched this file | results |

- **Tests:** `UnifiedExecutorTest` (32), `CorrectionPolicyTest` (17), `CorrectionRetryTest` (4) and
  `UnifiedHeadlessTest` (12) pass. Full suite: 1604 tests, 1 failed (the pre-existing B5 `SecondoTest`), 23 skipped.
- **Evidence** (BENCHMARKS "Phase 06A results"):

  | tier | result |
  |---|---|
  | S, headless batch, seed 9091, 1800 trials × 2 | **H2 holds on slip**: 252 vs 249 arrived (Phase 06: 239 vs 247). 11 slip runs retried; all found a plan and arrived. R1 (at most one retry, a failed retry ends EMPTY_PLAN) and R2 (largest retry p95 87.63 ms, max 104.00 ms) hold. H1 identical (0 mismatches over 300). H3 holds. H4 bounded. H5 fewer replans in all 4 disturbed families, conflict 14% faster. H6 cancel median 1 tick, max 12. H7 acceptable (p95 +0.65 µs). |
  | L, seed 86021, 96 laps | 96/96, no stuck key, death or new fault. L2 holds per lap. **L2′: no executor counter moved between laps**, with 41 process cancels. Lap ticks and physics plans unchanged; drift replans lower in point estimate, but no CI excludes 0. The retry never fired. |
  | L, seed 86022, 96 laps | Reproduces 86021: 96/96, L2 and L2′ hold, 41 process cancels, no difference in any pair, the retry never fired. Overhead, both seeds pooled: p95 +0.270 ms (CI −0.203..+0.914), **inconclusive**. Tier L verdict: **no improvement, no regression**. |

- **Classification: PASS** (the pre-registered Phase 06A rule). Phase 06 stays FAIL (D16): this PASS covers its two
  blockers, not its gate.
- **Promotion: not met** (the Phase 06 PASS rule on the Phase 06A data: L5 inconclusive, Tier L no improvement).
  `unifiedExecutor` stays default false.
- **Ownership is established on the lap-end cancel path only** (the refused branch of
  `PathingBehavior.cancelEverything()`). B6 stays open.

## Phase 07 (long-distance routing, 2026-11-09)

Scope: improve long-distance navigation per unit of compute and complexity, with a hierarchy as one hypothesis.

Not in scope:
- Phase 08;
- a new physics engine, local planner, executor or scheduler;
- a default change.

Start HEAD `db28a1cc3` (clean).

- **Commits** (local, `ostinato2/claude-review`):

  | commit | content |
  |---|---|
  | `9591d363a` | long-route simulator and corpus |
  | `fa7165a32` | goal switch at a fixed travel time, summary checks, dev raw |
  | `2fc753991` | audit, baseline, dev corpus, profile, decision gate (Option A) |
  | `57364373c` | `StateIdCache` behind `pathingStateIdCache` (default false), tests |
  | `92961cddf` | dev A/B, profile diagnosis, pre-registration, held-out hooks, live arm, tools |
  | the commit that last touched this file | held-out raw evidence and results |

- **Flags:** `pathingStateIdCache`, new, default **false**. `pathingHierarchical` is unchanged (false).
- **Harness switches** (test or harness only):
  - `OSTINATO_LONGROUTE*`;
  - `OSTINATO_BENCH_STATEIDS`;
  - `OSTINATO_BENCH_SEED`;
  - `-Dostinato.legatotest.arms=stateids`.
- **Tests:** `StateIdCacheTest` (2), `LongRouteSimTest` (7, 1 skipped), `PathBenchTest` (4, 3 skipped) and
  `SettingCatalogTest` (3) pass.
  - Full suite at `57364373c`: 1613 tests, 2 failed, 24 skipped.
  - The failures were B5 and `SettingCatalogTest.descriptionsJsonIsUpToDate`, caused by the new setting. That one was
    fixed in `92961cddf` by regenerating `setting-descriptions.json`.
  - The full suite was not re-run after the fix.
- **Evidence** (BENCHMARKS "Phase 07 results"):

  | tier | result |
  |---|---|
  | S, long routes, seeds 3001/3002, 113 pairs | ms per node 0.9624 (CI 0.9565–0.9686): **P1 missed** (target ≤ 0.70). G1 lost 0 / gained 0. G2 total ticks 1.0000. **G3: 8 of 88 differ, all goal mode**: a harness pairing defect, every other pair identical. Cancel 0.01 ms. |
  | S, PathBench, seed 3101, 60 queries | G1 failed rows 30 vs 30. G4 SHORT 0.9429 (CI 0.9383–0.9699). ALL 0.9595. Travel cost identical. |
  | L | not run (stopped on FAIL) |

- **Classification: FAIL** (G3 as written). On valid pairs alone it would at best have been PARTIAL, since P1 is
  missed either way.
- **Harness defect (open):** `LongRouteSimTest.corpus` draws the goal-mode switch goal inside the per-arm loop (lines
  223 and 237). Draw it once per route before reusing the goal mode.

## Phase 08 (navigate → interact → verify, 2026-11-09)

Scope: one workflow made reliable end to end, with outcomes, ownership, cancellation and recovery, judged by a
server-confirmed postcondition. Not in scope: Phase 09, a new framework/scheduler/event bus/registry, rewrites, a
default change. Phase 06/07 flags stayed off.

Start HEAD `b9282552b` (clean apart from an untracked `scripts/__pycache__/`).

- **Commits** (local, `ostinato2/claude-review`):

  | commit | content |
  |---|---|
  | `248580b91` | `InteractLiveTest` live harness |
  | `b0a2766b9` | legacy baseline (seed 88001), A/B reader |
  | `6fb7e6c88` | `ProcessOutcome`, `GetToBlockProcess` outcomes, `verifiedGetToBlock` (default false), adapter check, `ProcessOutcomeTest` |
  | `a6c4006ee` | harness oracle and fixture fixes, dev raw, audit, baseline, **pre-registration** |
  | the commit that last touched this file | held-out raw evidence, results, D19 |

- **Flags:** `verifiedGetToBlock`, new, default **false**.
- **Harness switches** (harness only): `-Dostinato.interacttest`, `.reps`, `.only`, `.exit`, `-Dostinato.seed`.
- **Tests:** full suite at the slice: 1620 tests, 1 failed (B5 `SecondoTest`, pre-existing), 24 skipped.
- **Ownership:** the slice adds no key writer; `onLostControl()` still clears keys directly (B6, unchanged). No key
  held and no process active after any stop or lap, in either arm.
- **Evidence** (BENCHMARKS "Phase 08 results"):

  | tier | result |
  |---|---|
  | U | `ProcessOutcomeTest` 7/7 (lifecycle, sticky terminal state, no inheritance, lost control) |
  | S | none: there is no headless model of a container opening |
  | L, baseline 88001 (legacy) | completion 12/18, 12 FS, 6 HANG per arm, 9 stale-rescan NPEs |
  | L, held out 88011 + 88012 | B: 48/48, FS 0, impossible TF 32/32, HANG 0, leaks 0, 80/80 self-report agrees; A: 32/48, FS 40, HANG 8 |

- **Classification: PASS** (G1–G8, P1, P2 on each seed and pooled).
- **Dev findings fixed before pre-registration:** the harness oracle missed a close-and-reopen in one tick (now keyed
  on the menu object); natural chests cached outside loaded chunks kept "vanished" targets satisfiable (VANISH and
  SEALED now use an ender chest); a target Baritone cannot break is never known, so the flag-on path also fails
  instead of exploring when nothing was ever known.

## Phase 09 (capability modularity: container observation behind a version adapter, 2026-11-09)

Scope: the smallest production-relevant modularity improvement that reduces version coupling, with behaviour,
lifecycle semantics and performance preserved. Not in scope: Phase 10, a plugin system, registry, framework,
protocol or port of other versions, any flag or default change. Phase 04–08 flags stayed at their defaults.

Start HEAD `6b4f3d049` (clean apart from an untracked `scripts/__pycache__/`).

- **Commits** (local, `ostinato2/claude-review`):

  | commit | content |
  |---|---|
  | `b4830091f` | audit, architecture map, slice selection, contract (before any code change) |
  | `41c008f12` | `ContainerView`, `OpenVerifier`, `ClientContainers`; `GetToBlockProcess`, `IGetToBlockProcess`, `BaritoneTaskAdapter` on the contract; tests; 1.16 compile check; parity comparator |
  | `65846561c` | implementation notes, unit evidence, dev smoke, **pre-registration** |
  | the commit that last touched this file | held-out raw evidence, results, D20 |

- **Flags:** none added. `verifiedGetToBlock` unchanged (default false).
- **Tests:** `OpenVerifierTest` (11), `ClientContainersTest` (3), `InteractBoundaryTest` (3), `GetToBlockVerdictTest`
  (5), new. Full suite at `41c008f12`: 1642 tests, 1 failed (B5 `SecondoTest`, pre-existing), 24 skipped.
- **Evidence** (BENCHMARKS "Phase 09 results"):

  | tier | result |
  |---|---|
  | U | the rule (11 cases incl. stale/wrong container, refused open, reset, adapter failure; 0 bytes/call), the 26.3 mapping (exactly 6 container blocks in the registry), the boundary source scan, the runner verdict (stale id, every state, throwing check, contract defaults) |
  | U, second version | contract + rule unchanged + 1.16 adapter compile at `--release 8` against the 1.16.5 MCP jar; 26.3 adapter against it fails (control); `ProcessOutcome` fails (record) |
  | L, held out 89011 + 89012, base vs P09 | C1 PASS (P09 B: 48/48, FS 0, impossible TF 32/32, HANG 0, 80/80 agree); C2 160/160 class parity, 0 allowance used, B outcomes identical; S no leak, no death; L1–L5 within bounds |

- **Classification: PASS.**
- **Coupling, before → after:** menu API named in 2 files of the slice (7 imports, 4 sites) → 1 file
  (`ClientContainers`); the runner imported the implementation class and a menu class → neither.

## Phase 10 (final validation, feature disposition, production readiness, 2026-11-09)

- Commits (local only): `4c14331e4` (B6 magma release, B5 test), `d807a5240` (terrain harness, production lap arms,
  pre-registration), `8ed8d7e7b` (Run V raw), and the commit that last touched this file (SURFACE diagnostic, Run P
  and SURFACE raw, docs).
- Held out: Run V (seeds 90011/90012, 180 laps), Run P (seed 90021, 96 laps); diagnostic SURFACE (seed 90031, 6
  laps). Each held-out seed ran once; no threshold changed. Raw: `docs/ostinato2/raw/phase10/`.
- B6 audit table: DECISIONS D21. Open: `AirProcess` door air pocket (Important, rare); Deferred writers need
  migrating before `unifiedExecutor` or `inputArbitration` can be promoted.
- Asked by the owner: the bot sprints without sprint-jumping because `sprintJump` is default false since
  `46dce71f7` (travel bench faster off); it is not food conservation. Not changed.

## Next task

**Owner decisions. Phase 10 is complete (PARTIAL); no further phase was started.**

0. Phase 10 (D21, FINAL_REPORT.md):
   - Review and, if accepted, push and merge `ostinato2/claude-review` (nothing was pushed). The magma release is the
     only production behaviour change.
   - Archive or keep the RETIRE items (`predictionTolerance`, `simEdgeSearch`, `latticeExperiment`).
   - Before re-assessing `verifiedGetToBlock`: a navigation-stall bound, a retry on another instance after a refusal,
     LAG_LONG reported as UNKNOWN, and a fresh pre-registered terrain run.

Earlier items (unchanged):

0. Phase 09 (D20):
   - Nothing to promote: no flag, no default change. The refactor is live in the build as committed.
   - A 1.16 back-port of the interaction capability needs `ProcessOutcome` as a Java 8 class (not a record), then
     `tools/phase09/v1161/ClientContainers.java` as its adapter. Not started.
   - The other container observers (`BastionProcess` 5 sites, `InventoryBehavior` 1, `FreecamInventoryHud` 2) were
     not migrated; they can use `ContainerView` if a version port needs it.
   - `GetToBlockProcess` still names other version-specific APIs (blocks, `BlockPos`, `Pose`); only the container
     coupling was moved.

0. Phase 08 (D19):
   - Keep `verifiedGetToBlock` off until the owner decides on promotion. A PASS is not a promotion.
   - Promotion would accept one behaviour change: with the flag on, `goto <block>` with no known instance fails
     (INVALIDATED_GOAL) instead of exploring; tasks needing exploration use the EXPLORE step.
   - Before promotion, consider a second arena (natural terrain, several chests) and the furnace types, which are
     mapped but not exercised live.
   - The same pattern (request outcome + server-checked postcondition in the runner step) is the candidate for the
     runner's BUILD step (false success on preemption) and count-less MINE.
   - The stale-rescan NPE is fixed only with the flag on.
1. Phase 07 (D18):
   - Keep `pathingStateIdCache` off. It is behaviour-identical and saves about 4% of planner CPU (Tier S); it cannot
     meet a 30% target.
   - Either archive it, or approve a new pre-registered confirmation: goal-mode pairing fixed, the live A/B run, and
     a target sized for a 4% change.
   - Do not fund a new long-distance hierarchy for travel time: planning wait is about 0.3% of trip ticks headless.
   - If planner CPU matters, profile live with `-XX:+DebugNonSafepoints` first.
   - Fix the goal-mode pairing in `LongRouteSimTest.corpus` before the harness is reused.
   - The "about 4% longer" hierarchy note in `docs/pathbench/LONG_HORIZON.md:34` and `docs/guides/movement.md:41`
     compares different query sets (0.9658 on the queries both solved). Not edited.
2. Phase 06A (D17):
   - Keep `unifiedExecutor` off. Enabling it needs promotion evidence and the owner's separate authorization.
   - Before any default change, finish B6 on the paths the executor can run on: the four `onLostControl()`
     `clearAllKeys()` calls and the other direct `onLostControl()` callers go through the arbiter or tell the
     executor first.
   - Decide whether to fund promotion evidence as its own pre-registered phase: an overhead measure that can resolve
     0.5 ms (96 client-tick pairs could not, in Phase 06 or in Phase 06A), and a live course where corrections
     escalate and the retry has occasion to fire.
3. Accept the Phase 04 gate as PROVISIONAL with `predictionTolerance` off. The alternative is to fund a new
   pre-registered drift-check design (bound × margin, or persistence) and its own A/B.
4. SimEdge (D15):
   - Keep it disabled.
   - Do not fund another latency fix on this course: even zero wait gains about 2–3% on steps only.
   - It can only pay off on momentum-decided routes (gaps, sprint jumps, long ascent chains). That needs a new
     pre-registered scenario family as its own phase. Otherwise archive it.
5. Separately:
   - `SimTrace`'s land prediction on water ticks (D14 finding 2);
   - B5 Secondo bookkeeping;
   - B6 key-writer migration, which also bounds what the unified executor can own;
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
