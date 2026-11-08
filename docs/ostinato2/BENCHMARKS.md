# Ostinato 2.0 — benchmarks

Session date: 2026-11-04. Machine: Temurin 25.0.4.1, 8 CPUs, test heap max 3072 MB. No `java`/`javaw` process was running before the build. Priority: BelowNormal. Native crate was not built (`-x nativeHost`).

Raw files, copied from `Ostinato-secondo/build/pathbench/` after the run:

- `docs/ostinato2/raw/phase00-short.md`
- `docs/ostinato2/raw/phase00-short-raw.csv`
- `docs/ostinato2/raw/phase00-short-fingerprint.txt`

`build/pathbench/` is a Gradle output and is not the record. The `docs/ostinato2/raw/` copies are.

## Command

From `Ostinato-secondo` at `2f4176049` (same tree as `origin/main` `67e12b96e`):

```
$env:OSTINATO_BENCH = 'phase00-short'
$env:OSTINATO_BENCH_REPS = '1'
$env:OSTINATO_BENCH_ONLY = 'SHORT'
.\gradlew.bat --offline --no-daemon --no-build-cache --max-workers=2 cleanTest test -x nativeHost -Postinato_bench --tests baritone.pathing.calc.PathBenchTest --tests baritone.pathing.kinematic.PlayerSimTest --tests baritone.pathing.kinematic.SimTraceTest --tests baritone.pathing.travel.TravelStackTest --tests baritone.pathing.movement.TickSimTest --tests baritone.pathing.movement.LineWalkTest --tests baritone.pathing.movement.MomentumJumpCostTest --tests baritone.pathing.movement.movements.LadderGrabTest --tests baritone.pathing.movement.movements.SteeredFallTest --tests baritone.pathing.kinematic.JumpSearchTest --tests baritone.pathing.physics.PhysicsPathfinderTest --tests baritone.secondo.SecondoTest
```

`BUILD SUCCESSFUL` in 27s. `:test` executed. 66 tests, 0 failures, 2 skipped (`PathBenchTest.engines` needs `OSTINATO_BENCH=engines`; `trainDecisionMatrix` needs `OSTINATO_MATRIX_TRAIN`).

`PathBench.bench` warms up once, then times `OSTINATO_BENCH_REPS` passes. The CSV is the timed pass only. `ok` means `calculate0` returned a path whose destination is in the goal before the 10s timeout. That is planner success (S), not a player walking the path (L).

Cost in the score line is the path's cost, which the harness treats as travel ticks. It is not a measured executor tick count.

CPU utilization was not sampled. `OSTINATO_BENCH_PROFILE` (JFR) was off. The report's `nodes/sec` and `cpus 8` are not a CPU percentage.

## SHORT stock A* (this session, VERIFIED)

One timed rep after one warmup. Failures 0 of 20. Median 1.6 ms, p95 88.4 ms. Total timed search 334 ms, 13 MB allocated on the test thread, max peak heap 217 MB, mean path length 40.0, mean cost 260.5, mean time-to-arrive 260.8 (cost + plan ms/50).

| terrain | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|
| FLAT | 0/4 | 0.5 | 0.9 | 42 | 277.0 | 0.2 |
| HILLS | 0/4 | 1.7 | 16.6 | 338 | 228.9 | 0.5 |
| CAVES | 0/4 | 1.2 | 6.1 | 244 | 262.4 | 0.3 |
| WATER | 0/4 | 0.3 | 7.9 | 36 | 212.2 | 0.3 |
| BREAK_PLACE | 0/4 | 74.7 | 92.9 | 13624 | 321.8 | 1.9 |

Per-query cost, nodes, ms, and `allocBytes` are in the raw CSV. The fingerprint matches `src/test/resources/pathbench/golden.txt` for these 20 queries (example: `FLAT/SHORT/0 ok 300.365091 132`).

`stockAStarMatchesGoldenCosts` also passed. It replans every non-timeout golden query at or under 300_000 nodes, including MEDIUM, and asserts cost and node count. It does not write timings. MEDIUM and LONG wall times for this commit are **UNKNOWN**. `docs/pathbench/baseline.md` (3 reps, 2026-10-17 table) is **REPORTED** and its node medians do not match `golden.txt`.

Full historical command, not re-run: `scripts/pathbench-phase1.sh <label> normal|pi` (5 reps, `cleanTest`, all lengths). Pi profile was not run.

## Unit tests that passed (U unless noted)

| Class | Tests | What they cover |
|---|---|---|
| `PlayerSimTest` | 13 | Sprint speed, Speed/Jump Boost, soul sand, honey, jump apex, step-up. Tick model only |
| `SimTraceTest` | 3 | Step and open-loop error math on a fake floor. Not a live client |
| `TickSimTest` | 3 | Ascend, walk-off, land, standstill costs |
| `LineWalkTest` | 1 | Line-walk cost |
| `MomentumJumpCostTest` | 2 | Sprint-jump costs (printed 2-block `5.036`, 3-block `7.420`) |
| `JumpSearchTest` | 6 | Jump search |
| `LadderGrabTest` | 8 | Ladder grab costs |
| `SteeredFallTest` | 5 | Steered fall costs |
| `PhysicsPathfinderTest` | 3 | Flat walk, 2-block gap, corner, on `PlayerSim.World` (S) |
| `TravelStackTest` | 5 | Auto order and named pins |
| `SecondoTest` | 13 | Companion goal policy. Includes "planned APIs absent, invoke is a noop" |

No prediction-error number against a real player was produced. `SimTrace` records `maxStep`, `rmsStep`, `openLoopEnd`, ground mismatches, and yaw lag when `kinematicTrace` is on in game (`SimTrace` lines 141–151). That path was not exercised. Live error is **UNKNOWN**.

## Scenario coverage

| Scenario | This session | Needs a live client |
|---|---|---|
| Walking, planner | SHORT FLAT/HILLS/CAVES/WATER/BREAK_PLACE plans reached the goal cell. `PhysicsPathfinderTest.walksAcrossFlatGround` | Walking the plan with keys: yes |
| Turning | `PhysicsPathfinderTest.turnsACorner` (physics search). Aim packets: not run | Yes for `LookBehavior` / mouse |
| Jumping | `JumpSearchTest`, `PlayerSimTest.jumpApex`, `MomentumJumpCostTest`, physics 2-block gap | Yes for `KinematicController` / `MovementJump.update` |
| Momentum | Cost unit test only. `OSTINATO_BENCH_MOMENTUM` not set | Yes to see a sprint-jump land |
| Parkour | Not in the default bench (`allowParkour` is off in `PathBench.apply(BARITONE)`). `SimDrillStage` not run | Yes for execution |
| Ladders | `LadderGrabTest` costs. `PlayerSim` climb branch not separately timed | Yes for `LadderController` |
| Fluids | WATER SHORT plans. No swim-controller run | Yes for `SwimController` and real flow |
| Falls | `SteeredFallTest` costs | Yes for `FallController` and damage |
| Long routes | Not timed. Golden asserts cost and nodes for non-timeout queries ≤300k nodes. LONG and timeout queries were excluded by that test | Planning can be headless. Execution yes |
| Mining / placement | BREAK_PLACE SHORT plans (break and place allowed by the stock bench). No blocks were broken | Yes |
| Cancellation | Not tested. No `PathExecutor` test exists | Yes |
| Recovery (`M01`, `M05`, `JumpFailures`, route forget) | Not tested | Yes |
| Prediction error | `SimTraceTest` arithmetic only | Yes for a real player |

Headless and re-run below as unit tests: `PathBench` golden costs, `PlayerSim` ticks, `PhysicsPathfinder`. `PathExecutor`, look, break/place helpers, chunk edges, hunger sprint, inventory, and lagback still need a client. The Phase 01 feature under test is the input arbiter, which has no client types.

## Not run

- `OSTINATO_BENCH` MEDIUM, LONG, 5 reps, pi heap, JFR, weighted A*, HPA, engines comparison.
- `NativeParityTest`, `NativePlannerParityTest`.
- `SimDrillStage`, `PathingQueueTest`, `AvoidanceTest`.
- Any Fabric client or dedicated server.

## Phase 01 tests (2026-11-04, VERIFIED)

No `java` or `javaw` process was running before the build. Shell priority BelowNormal. `OSTINATO_BENCH` was unset, so the bench, engines, and matrix tests skipped. Native crate was not built (`-x nativeHost`). This run did not write a new SHORT timing table. The phase 00 numbers above were not re-measured.

From `Ostinato-secondo` at `2f4176049` plus the uncommitted Phase 01 edits:

```
.\gradlew.bat --offline --no-daemon --max-workers=2 test -x nativeHost --tests baritone.utils.InputArbiterTest --tests baritone.gui.SettingCatalogTest --tests baritone.pathing.travel.TravelStackTest --tests baritone.pathing.kinematic.PlayerSimTest --tests baritone.pathing.kinematic.SimTraceTest --tests baritone.pathing.movement.TickSimTest --tests baritone.pathing.movement.LineWalkTest --tests baritone.pathing.movement.MomentumJumpCostTest --tests baritone.pathing.movement.movements.LadderGrabTest --tests baritone.pathing.movement.movements.SteeredFallTest --tests baritone.pathing.kinematic.JumpSearchTest --tests baritone.pathing.physics.PhysicsPathfinderTest --tests baritone.pathing.calc.PathBenchTest --tests baritone.secondo.SecondoTest --tests baritone.utils.PhysicalInputTest
```

`BUILD SUCCESSFUL` in 46s. 87 tests, 0 failures, 0 errors, 3 skipped. 84 executed.

| Class | Tests | Fail | Skip | What |
|---|---|---|---|---|
| `InputArbiterTest` | 8 | 0 | 0 | U. Stale epoch, simultaneous claims, jump preemption, cancel versus release, fail, emergency, complete frames, flag default off |
| `SettingCatalogTest` | 3 | 0 | 0 | U. `inputArbitration` has a category and a description |
| `PhysicalInputTest` | 10 | 0 | 0 | U. Includes the physicalInput default scan |
| `PathBenchTest` | 4 | 0 | 3 | `stockAStarMatchesGoldenCosts` passed. Skips need `OSTINATO_BENCH` or `OSTINATO_MATRIX_TRAIN` |
| `PlayerSimTest` | 13 | 0 | 0 | U |
| `SimTraceTest` | 3 | 0 | 0 | U |
| `TickSimTest` | 3 | 0 | 0 | U |
| `LineWalkTest` | 1 | 0 | 0 | U |
| `MomentumJumpCostTest` | 2 | 0 | 0 | U |
| `JumpSearchTest` | 6 | 0 | 0 | U |
| `LadderGrabTest` | 8 | 0 | 0 | U |
| `SteeredFallTest` | 5 | 0 | 0 | U |
| `PhysicsPathfinderTest` | 3 | 0 | 0 | S, `PlayerSim.World` |
| `TravelStackTest` | 5 | 0 | 0 | U |
| `SecondoTest` | 13 | 0 | 0 | U |

`PhysicalInputTest` and `SettingCatalogTest` were not in the phase 00 command. They passed here. No live (L) fall was run.

## Recheck after the merge (2026-11-04, VERIFIED)

From `Ostinato-combined` on `combine/main-maze-ppe`, dirty with the Phase 02 sources. No `java` or `javaw` before the build. BelowNormal. `OSTINATO_BENCH` unset. `-x nativeHost`. Offline, no daemon, max workers 2.

```
.\gradlew.bat --offline --no-daemon --max-workers=2 -x nativeHost test --tests baritone.contract.ComparisonHarnessTest --tests baritone.utils.InputArbiterTest --tests baritone.gui.SettingCatalogTest --tests baritone.pathing.calc.PathBenchTest.stockAStarMatchesGoldenCosts --tests baritone.pathing.physics.PhysicsPathfinderTest
```

`BUILD SUCCESSFUL` in 34s. 16 tests, 0 failures, 0 errors, 0 skipped.

| Class | Tests | Fail | What |
|---|---|---|---|
| `PathBenchTest.stockAStarMatchesGoldenCosts` | 1 | 0 | Phase 00. Golden cost and node lines still match. 10.5s. Timing table was not re-measured |
| `InputArbiterTest` | 8 | 0 | Phase 01. Same cases as the first pass |
| `SettingCatalogTest` | 3 | 0 | Phase 01. Categories and descriptions, including the merged settings |
| `PhysicsPathfinderTest` | 3 | 0 | Flat, gap, corner. Unchanged class |
| `ComparisonHarnessTest` | 1 | 0 | Phase 02. Report matches `src/test/resources/contract/flat-sprint.txt` |

Phase 02 report, also in `docs/ostinato2/raw/phase02-flat.txt`:

```
physics-search EQUIVALENT PlayerSim-1.16-land-tick executed=true reached=true ticks=18 cost=18.0 end 0.5000 0.0000 5.2136
trivial-sprint EQUIVALENT PlayerSim-1.16-land-tick executed=true reached=true ticks=18 cost=18.0 end 0.5000 0.0000 5.2136
ppe-held-sprint NOT_EQUIVALENT PPE-26.3-living-tick executed=true reached=true ticks=40 cost=19.0 status OK end 0.5000 64.0000 11.2389 held-input different world y
stock-astar NOT_EQUIVALENT planner-cost-not-PlayerSim executed=false reached=false ticks=-1 cost=- not executed; PathExecutor is not headless and stock A* cost is not a PlayerSim tick
```

The two equivalent rows share the PlayerSim world, the settled start, and the budgets. PPE's 40 ticks are the held horizon, not a stop on the goal. Its cost 19 is the first recorded sample inside the goal column. Stock A* was not executed in this harness. The golden test above is the separate Phase 00 recheck. No live client.

## Phase 03 lattice (2026-11-04, VERIFIED)

From `Ostinato-combined` on `combine/main-maze-ppe`. No `java` or `javaw` before the build. BelowNormal. `OSTINATO_BENCH` unset. `-x nativeHost`. Offline, no daemon, max workers 2.

```
.\gradlew.bat --offline --no-daemon --max-workers=2 -x nativeHost test --tests baritone.lattice.LatticeExperimentTest --tests baritone.gui.SettingCatalogTest --tests baritone.pathing.physics.PhysicsPathfinderTest --tests baritone.contract.ComparisonHarnessTest
```

`BUILD SUCCESSFUL` in 31s. 13 tests, 0 failures, 0 errors, 0 skipped. No client.

| Class | Tests | Fail | What |
|---|---|---|---|
| `LatticeExperimentTest` | 6 | 0 | Phase 03. Two full corpus runs, 1.509s for the bench test. Discrete columns matched |
| `SettingCatalogTest` | 3 | 0 | `latticeExperiment` is categorized and described. Default in source is false |
| `PhysicsPathfinderTest` | 3 | 0 | Unchanged class |
| `ComparisonHarnessTest` | 1 | 0 | Phase 02 golden report still matches |

Decision **REVISE**. Reason from `docs/ostinato2/raw/phase03-summary.txt`:

```
beam 12/12, weighted 12/12, physics 12/12, chosen beam, dev unique 0; shared median ticks beam 18.0 vs physics 17.5 (ratio 1.029, bound 1.15); baseline expansions are UNKNOWN, so the 3x expansion clause cannot pass; held-out beam 16/16, physics 14/16
```

Max divergence 0.000000. Three trials per case. The bench test ran the corpus twice and the discrete columns matched. Latency and allocated bytes are recorded per trial in `docs/ostinato2/raw/phase03-trials.csv` (252 data rows) and are not golden. Physics rows use expansions `-1` and divergence `na`. Physics missed held-out `gap-2-far` and `corner-long` inside node budget 1500. The lattice tick cap of 400 did not bind. Full suite was not run.

---

## Claude review reruns (2026-11-04, branch `ostinato2/claude-review`, local only)

Same machine and toolchain as above. Priority BelowNormal, `--max-workers=2`, `-x nativeHost`, no client. Raw files are in `docs/ostinato2/raw/review/`. Seeds: the lattice and PathBench worlds are deterministic and hand-built or fixed, so there is no world seed. The bootstrap seed is `0x0A571A70` (10,000 resamples). The random flag-off drift test uses seed `0x040D21F7`.

### Phase 00 at the Phase 03 endpoint, 5 reps (VERIFIED, Tier-S)

```
$env:OSTINATO_BENCH='phase00-review'; $env:OSTINATO_BENCH_REPS='5'; $env:OSTINATO_BENCH_ONLY='SHORT'
gradlew --offline --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2G test -x nativeHost --tests *.PathBenchTest --tests *.PlayerSimTest --tests *.SimTraceTest --tests *.TravelStackTest --tests *.TickSimTest --tests *.LineWalkTest --tests *.MomentumJumpCostTest --tests *.LadderGrabTest --tests *.SteeredFallTest --tests *.JumpSearchTest --tests *.PhysicsPathfinderTest --tests *.SecondoTest
```

- Result: 65 passed, 1 failed (`SecondoTest`, F-M6, a pre-existing failure), 2 skipped.
- The node/cost/path fingerprint is **identical** to Grok's `raw/phase00-short-fingerprint.txt`.
- Raw files: `raw/review/phase00-review-raw.csv`, `phase00-review-fingerprint.txt`, `phase00-review.md`.
- Spread of the per-rep median ms over the 5 reps:

  | Terrain | Min | Max | Grok (1 rep, REPORTED) |
  |---|---|---|---|
  | FLAT | 0.298 | 0.311 | 0.56 |
  | HILLS | 4.064 | 4.287 | 4.59 |
  | CAVES | 1.366 | 1.395 | 1.42 |
  | WATER | 2.730 | 3.141 | 2.84 |
  | BREAK_PLACE | 68.2 | 74.7 | 81.5 |

- Grok's FLAT and BREAK_PLACE values fall outside this spread, most likely because of warmup. Timing is not golden; only the fingerprint is.

### Phase 03 review bench, 20 trials (VERIFIED, Tier-S)

```
$env:OSTINATO_BENCH='phase03-review'; $env:OSTINATO_LATTICE_TRIALS='20'; $env:OSTINATO_WRITE_DOCS='1'
gradlew --offline --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2G test -x nativeHost --tests baritone.lattice.LatticeStockAStarTest
```

- Raw files:
  - `raw/review/phase03-trials.csv`: 28 cases × 3 methods × 20 trials.
  - `raw/review/phase03-summary.txt`: the decision, per-case rows and the `stats` lines.
  - `raw/review/phase03-stock.csv` and `phase03-stock-summary.txt`: stock A*, 28 cases × 2 configs × 20 trials.
- Decision under D9's rule: **CONTINUE, PROVISIONAL** (see DECISIONS D9 status). Reproducible: the discrete columns are identical across trials. Divergence 0, which is self-consistency only.
- Latency, nearest-rank percentiles pooled over every trial (ms):

  | Split | Method | Success | Wilson 95% | p50 | p95 | p99 |
  |---|---|---|---|---|---|---|
  | dev | beam | 12/12 | [0.758, 1] | 0.276 | 1.375 | 1.853 |
  | dev | weighted A* | 12/12 | [0.758, 1] | 0.086 | 0.582 | 0.962 |
  | dev | PhysicsPathfinder | 12/12 | [0.758, 1] | 2.302 | 23.394 | 32.241 |
  | dev | stock A* | 12/12 | [0.758, 1] | 0.234 | 0.639 | 1.030 |
  | dev | stock A* + parkour | 12/12 | [0.758, 1] | 0.809 | 1.896 | 2.958 |
  | held | beam | 16/16 | [0.806, 1] | 0.137 | 0.929 | 1.175 |
  | held | weighted A* | 16/16 | [0.806, 1] | 0.026 | 0.200 | 0.211 |
  | held | PhysicsPathfinder | 14/16 | [0.640, 0.965] | 1.944 | 33.380 | 34.801 |
  | held | stock A* | 16/16 | [0.806, 1] | 0.131 | 0.344 | 0.561 |
  | held | stock A* + parkour | 16/16 | [0.806, 1] | 0.737 | 1.468 | 1.720 |

- Fisher two-sided, beam vs physics success: held p = 0.4839, dev p = 1.0.
- Bootstrap 95% CI of the median travel-tick ratio vs physics:

  | Split | Beam | Weighted |
  |---|---|---|
  | dev | [0.944, 1.286] | [1.028, 1.500] |
  | held | [0.920, 1.241] | [1.000, 1.241] |

- Shared dev median expansions: beam 102, weighted 27.5, physics 76.5.
- Stock A* travel is the A* cost estimate, NOT_EQUIVALENT to simulated ticks. Stock without parkour bridges gaps by placing blocks (gap-2: estTicks 74.3); with parkour it jumps (26.6).

### Phase 04 per-mode prediction error

**Tier-L (historical live 26.3 SIMTRACE traces, 2026-10-14; no client run in this review)**

```
python docs/ostinato2/tools/live_trace_modes.py <Ostinato-bastion-263>/fabric/run/client/simtrace docs/ostinato2/raw/review/phase04-live-trace-modes.csv
```

One-step Euclidean error (blocks):

| Mode | n | p50 | p95 | p99 | max | ticks over 0.05 |
|---|---|---|---|---|---|---|
| sprint-ground | 4008 | 0.0013 | 0.1437 | 0.2235 | 0.2448 | 633 |
| sprint-jump-air | 9477 | 0.0003 | 0.0105 | 0.0983 | 0.6305 | 168 |
| climb-jump | 739 | 0.0003 | 0.1737 | 0.2374 | 0.5357 | 96 |

- Ticks with yaw lag ≤ 1° have p99 ≤ 0.098; yaw lag dominates the tail.
- Open-loop end error per run, p50: jump 0.125, climb 0.813, chain 2.787.
- Not covered by any trace: walking, falling without input, fluids, and ladder climbing proper.

**Tier-S cross-model (PlayerSim 1.16 model vs PPE 26.3 model, identical terrain, held input)**

```
gradlew --offline --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2G test -x nativeHost --tests baritone.physics.CrossModelModesTest
```

| Mode | Ticks | Max open-loop distance | Max per-tick displacement difference |
|---|---|---|---|
| walk | 20 | 0.000001 | 0.000000 |
| sprint | 20 | 0.000000 | 0.000000 |
| sprint-jump | 20 | 0.000004 | 0.000005 |
| fall (from 20 blocks) | 30 | 0.000006 | 0.000007 |
| water walk | 20 | 0.000000 | 0.000000 |
| ladder jump | 20 | 0.000004 | 0.000007 |

- Raw files: `raw/review/phase04-crossmodel.csv` (every tick) and `phase04-crossmodel-summary.txt`.
- The water and ladder cases engage their mechanics: water walk covers about 0.1 block/tick vs about 0.22 for a land walk, and the ladder rises about 0.118/tick.

**Consumer overhead (VERIFIED, Tier-S microbench)**

```
$env:OSTINATO_BENCH='phase04'
gradlew --offline --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2G test -x nativeHost --tests baritone.pathing.physics.PredictionToleranceTest
```

Method: 3000 batches of 200 ticks after 500 warmup batches, with the paths interleaved ABAB. One sample is a batch's mean ns per tick. Raw data: `raw/review/phase04-overhead.csv`.

| Path | p50 ns | p95 ns | p99 ns |
|---|---|---|---|
| SimTrace observe+commit, flag off | 1698.0 | 2250.5 | 3234.0 |
| SimTrace observe+commit, flag on | 1704.5 | 2270.0 | 3593.0 |
| PhysicsTravel predict+drift, flag off | 105.5 | 132.5 | 186.0 |
| PhysicsTravel predict+drift, flag on | 109.0 | 135.0 | 202.5 |

- Flag on adds at most 0.36 µs per tick at p99, against a 50 ms client tick.
- The flag-off path differs from the pre-Phase-04 code by one null check per call (SimTrace) and two settings reads per tick (PhysicsTravel). The bench does not include the settings reads.
- Real client tick-time p95/p99 is Tier-L and was **not measured** (blocker B2 in STATUS).

### Planner regression check at the Phase 04 HEAD (VERIFIED, Tier-S)

The same command as the Phase 00 5-rep run, with `OSTINATO_BENCH=phase04-review` and `*.PredictionToleranceTest` and `*.InputArbiterTest` added:

- Result: 85 tests, 1 failed (`SecondoTest`, F-M6), 3 skipped.
- The fingerprint is **identical** to the Phase 00 baseline.
- Raw files: `raw/review/phase04-review-raw.csv`, `phase04-review-fingerprint.txt`, `phase04-review.md`.

Per-rep median ms (5 reps):

| Terrain | Phase 03 endpoint | Phase 04 HEAD |
|---|---|---|
| FLAT | 0.298–0.311 | 0.303–0.511 (one outlier rep) |
| HILLS | 4.064–4.287 | 4.005–4.276 |
| CAVES | 1.366–1.395 | 1.343–1.418 |
| WATER | 2.730–3.141 | 2.660–2.822 |
| BREAK_PLACE | 68.2–74.7 | 66.8–68.9 |

- Pooled p95 over all 100 timed queries: 76.96 ms at the Phase 03 endpoint, 69.09 ms at Phase 04.
- Phase 04 does not touch the planner, so no regression is expected, and none is seen.

### Full suite

Command: `gradlew --offline --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2G test -x nativeHost --continue`

| Commit | Tests | Failed | Skipped | Notes |
|---|---|---|---|---|
| Phase 03 endpoint `09631d874` | 1465 | 1 | 18 | `SecondoTest`, F-M6 |
| Phase 04 HEAD, before the catalog fix | 1482 | 3 | 20 | `SecondoTest`, plus `SettingCatalogTest.everySettingHasACategory` and `.descriptionsJsonIsUpToDate`, both for the new setting. Fixed in `54423c807`. |
| Phase 04 HEAD (`54423c807` tree) | 1482 | 1 | 20 | `SecondoTest` only |

- The two extra skips are the opt-in benches `LatticeStockAStarTest.reviewBench` and `PredictionToleranceTest.overheadBench`.
- After the suite ran, `git status` showed no change under `docs/ostinato2/raw/` (F-M4 fixed).

## physics-263 (2026-11-04/05, branch `ostinato2/claude-review`, local only; DECISIONS D12)

### Native parity (VERIFIED, Tier-U)

`gradlew ... test -Postinato_native_require --tests "baritone.pathing.kinematic.*" --tests "baritone.contract.*"`

- 180 tests, 0 failed, 3 skipped.
- `NativeParityTest` ran 24/24 with nothing skipped. It compares the Java and Rust full 21-double state bitwise over random worlds, which now include bed-bounce cells.

### PlayerSim vs PPE (VERIFIED, Tier-S)

`CrossModelModesTest` now asserts that every PlayerSim coordinate is within 0.5 float ulp of PPE's float output.

| mode | maxOpenError | maxFloatUlps (26.3 PlayerSim, fixed PPE) | maxOpenError, 1.16 PlayerSim vs unfixed PPE |
|---|---|---|---|
| walk | 1.906e-07 | 0.475 | 5.970e-07 (1.73 ulps: fails the bound) |
| sprint | 2.090e-07 | 0.489 | 4.900e-07 |
| sprint-jump | 3.425e-06 | 0.470 | 3.522e-06 |
| fall | 3.783e-06 | 0.496 | 5.545e-06 |
| water-walk | 5.781e-08 | 0.488 | 8.849e-08 |
| ladder-jump | 3.752e-06 | 0.492 | 3.748e-06 |

- The 1e-6-scale open errors are PPE's float output quantization at y≈64, not model disagreement.
- Before PPE's sprint-speed fix, the 26.3 PlayerSim was 1.85 ulps off on sprint. That gap is how the PPE bug was found.
- Raw files: `raw/review/physics263-crossmodel.csv`, `physics263-crossmodel-summary.txt`, and `physics263-crossmodel-old116-summary.txt` (the 1.16 model, run in a throwaway worktree at `9b5bbfa7f`).
- Both models were written from the same decompiled sources, so this agreement is not independent evidence against the game.

### Planner regression (VERIFIED, Tier-S)

This is the Phase 04 command (5 reps, SHORT) with `OSTINATO_BENCH=physics263-review`, plus the spectate tests, `PlayerSim263Test` and `CrossModelModesTest`.

- Result: 151 tests, 1 failed (`SecondoTest`, F-M6), 3 skipped.
- The fingerprint is **identical** to `raw/review/phase04-review-fingerprint.txt`, so the float-drag changes did not move any planner node, cost or path.
- All 20 queries median 1.9 ms, p95 87.4 ms. Timing is not golden.
- Raw files: `raw/review/physics263-review-raw.csv`, `physics263-review-fingerprint.txt`, `physics263-review.md`.

### Full suite (VERIFIED)

`gradlew ... test -Postinato_native_require --continue`: 1517 tests, 1 failed (`SecondoTest`, F-M6), 18 skipped.

- That is 35 more tests than the Phase 04 HEAD row's 1482. Eight of them are `PlayerSim263Test`.
- That run used `-Postinato_native_require`, and the earlier rows used `-x nativeHost`, so 2 fewer were skipped. The rest of the difference was not broken down per class.
- `InputDecoderTest` was changed afterwards (its keyboard truth is now a 26.3 client) and passed 5/5 in the regression run above.

### Not run

- No live client.
- The D11 Tier-L bounds were not re-measured for the new model (STATUS B1).

## Phase 05: simulation-derived A* (2026-11-05, branch `ostinato2/claude-review`, local only)

### Pre-registered gate (written before any live data)

- **Arms** (`-Dostinato.legatotest.arms=simedge`, course `SimEdgeCourse`: slalom, ridges, bumps, steps): A legacy A*;
  B `simEdgeSearch`; C legacy again; D `simEdgeSearch` again. Latin-square order, 6 reps, seed 71006. A second run on
  seed 71007 is the reproducibility check. Everything else is the same in all arms (Legato and kinematicLine off).
- **Reliability (must hold, both runs):** reached(B) ≥ reached(A) and reached(D) ≥ reached(C); no lap with stuck keys;
  no fault code in B/D that A/C never show; simedge exceptions = 0.
- **Noise control:** C/A per-leg median ratios. If any leg's A/A' spread exceeds 3%, a ticks claim on that leg needs
  its effect to exceed that spread.
- **Improvement (at least one, in both replications B/A and D/C, and in both seeds):**
  1. Execution efficiency: paired lap-ticks ratio ≤ 0.97 on ≥ 2 legs, no leg ≥ 1.03.
  2. Planning accuracy: on complete first plans, the median |predicted − actual| / actual of the sim model (predSim,
     against lap ticks minus planWait) is ≥ 25% lower than the legacy model's (predLegacy), with a paired bootstrap
     95% CI of the difference excluding 0. This is measured on all laps (the models priced on the same path).
  3. Route quality: none claimed separately; the headless bench reports it under the sim model (Tier S only).
- **Otherwise:** classified "no improvement". `simEdgeSearch` stays off (it is off by default either way), and the
  result is documented.
- Baseline fixes found during the phase go in their own commits and apply to every arm.
- **Addendum (2026-11-05 05:10, before the counted run).** An aborted first attempt, 3 laps, showed that the
  first plan is usually a partial segment (`complete=false`), so measure 2 would have few samples. The harness
  now also records `segTicks`, the lap tick at which the feet first stand in the first plan's last cell.
  `simedge-ab.py` adds a "first segment" accuracy table, with actual = segTicks − planWait, over every lap that got
  there. Measure 2 is now judged on the first-segment table, with the same 25% / CI rule, because it covers more laps.
  The complete-plan table is still reported and must not point the other way: its CI must not lie entirely below 0.
  A second attempt was aborted after 6 laps: the first version of `segTicks` (feet in the cell) missed even complete
  plans, because a lap ends within 0.8 of the goal centre. `segTicks` now uses the lap's own arrival test: within 0.8
  horizontally of the cell centre and within 1 block of its height. Only `segTicks` values were inspected, not
  accuracy. Neither aborted attempt (3 + 6 laps) is counted.

### Headless (Tier S): `SimEdgeTest.benchmark`, PathBench queries, 2 reps, 10 s budget per query

Command: `OSTINATO_SIMEDGE_BENCH=1 OSTINATO_BENCH_REPS=2 OSTINATO_SIMEDGE_HORIZON=<h> gradlew test -Postinato_bench --tests baritone.pathing.calc.SimEdgeTest.benchmark`.
Raw tables: `build/pathbench/simedge-h0.md` and `simedge-h32.md` (not committed). The A* rows are the same run's
legacy arm.

| run | planner | failures / 60 | nodes/s | MB allocated | median ms | p95 ms |
|---|---|---|---|---|---|---|
| 1 | A* (legacy) | 4 (BREAK_PLACE LONG ×4) | 136,068 | 3,431 | 115.0 | 10,011.8 |
| 1 | sim-edge, no horizon | **20** (HILLS MED 3, HILLS LONG 4, CAVES LONG 1, WATER LONG 4, BREAK_PLACE MED 4, LONG 4) | 36,576 | 410,725 | 1,872.3 | 10,008.6 |
| 2 | A* (legacy) | 4 (BREAK_PLACE LONG ×4) | — | — | 113.5 | 10,006.5 |
| 2 | sim-edge, horizon 32 | **5** (BREAK_PLACE MED 1, LONG 4) | 78,298 | 104,100 | 505.3 | 9,999.7 |

- All sim-edge failures are timeouts. There were no exceptions, no fallbacks and no capped searches.
- Run 1 showed the cost of simulating every edge. Run 2's horizon is an algorithmic change (PHASE05_AUDIT §11).
- The one extra run-2 failure is a BREAK_PLACE MEDIUM query. A* already needs a median 4.7 s there, and sim-edge's
  median is 8.3 s, so one query passed the 10 s budget. In the client the planner has Baritone's own short timeouts
  and returns its best partial path. Headless plan time is a cost, not a live reliability result.
- Plan time with horizon 32 is about 1.5–3× A*'s on the hard terrains (HILLS LONG median 5.1 s against 2.6 s) and
  close to A* on FLAT.

Route quality: every path that both planners found in run 2 is re-priced under the sim model from an unknown start,
same evaluator for both.

| terrain | pairs | A* path, sim price | sim-edge path, sim price | median ratio | turns A* / sim-edge |
|---|---|---|---|---|---|
| FLAT | 12 | 998.8 | 999.0 | 1.0000 | 0.5 / 0.6 |
| HILLS | 12 | 1269.3 | 1263.7 | 0.9950 | 19.1 / 20.3 |
| CAVES | 12 | 1624.7 | 1622.6 | 0.9993 | 24.1 / 24.4 |
| WATER | 12 | 1114.4 | 1112.0 | 0.9993 | 5.2 / 5.9 |
| BREAK_PLACE | 6 | 855.3 | 855.6 | 0.9999 | 0.4 / 0.8 |

Run 1 (no horizon) gave HILLS 0.9845, CAVES 0.9917, WATER 0.9893 on its fewer completed pairs (5, 11, 8).

Live course, headless (`SimEdgeCourseTest`, unknown start, sim price):

| leg | A* | sim-edge | ratio |
|---|---|---|---|
| slalom | 344.75 | 338.79 | 0.9827 |
| ridges | 333.20 | 329.26 | 0.9882 |
| bumps | 297.62 | 298.34 | 1.0024 |
| steps | 323.56 | 317.53 | 0.9814 |

These are the sim model's own prices, so they show only that the search optimises what it measures. Whether the
executor gets the time back is the live A/B's question.

### Live A/B (Tier L), seed 71006, 6 reps, 96 laps

Command (PowerShell): `$env:JAVA_TOOL_OPTIONS='-Dostinato.noMouseGrab=true -Dostinato.windowPos=2600,40
-Dostinato.testHarness=true -Dostinato.legatotest=true -Dostinato.legatotest.exit=true
-Dostinato.legatotest.arms=simedge -Dostinato.legatotest.reps=6 -Dostinato.seed=71006'; .\gradlew.bat
:fabric:runClient -PclientXmx=1536M` on the 26.3 dev client, Peaceful. A fresh `fabric/run/client/options.txt`
needs `onboardAccessibility:false`, or the harness waits behind the onboarding screen. Analysis: `python scripts/simedge-ab.py build/simedge-live-71006.txt`,
which holds the counted run's `LEGATOTEST` lines only. The client's `legatotest-result.txt` also holds the 9 aborted
laps, so it was not used. Full output: `build/simedge-live-71006-analysis.md` (not committed).

**Reliability:** A 24/24, B 24/24, C 24/24, D 24/24 reached. No fault codes in any arm, no stuck keys.
`simedge searches=59 fallbacks=0 capped=0 exceptions=0`. Holds.

**Lap ticks**, paired by leg and rep, median ratio with bootstrap 95% CI:

| leg | B/A | D/C | C/A (noise) | turned A/B | jumps A/B |
|---|---|---|---|---|---|
| slalom | 1.039 (1.028–1.208) | 1.035 (1.025–1.044) | 1.001 (0.947–1.013) | 552/686 | 0/0 |
| ridges | 1.090 (1.059–1.116) | 1.068 (1.060–1.078) | 0.993 (0.982–1.015) | 1246/1600 | 9/8 |
| bumps | 0.997 (0.995–1.000) | 0.995 (0.990–1.000) | 1.000 (0.998–1.003) | 328/663 | 0/1 |
| steps | 1.036 (1.025–1.045) | 1.029 (1.022–1.045) | 1.000 (0.998–1.005) | 734/1159 | 3/5 |

- The C/A noise is within 1% at the median on every leg; one slalom pair reached 0.947.
- The sim-edge arms are **slower** on three legs, in both replications, and even on bumps. Measure 1 fails in the
  wrong direction (≥ 1.03 on slalom, ridges and steps).
- Diagnostic (not a gate measure): most of the slowdown is planning. The sim search's first plan waits a median
  12–14 ticks against 2–3 for A*. With that wait removed, the ratios are slalom 1.004 / 0.999, ridges 1.051 / 1.025,
  bumps 0.993 / 0.992, steps 0.997 / 0.997. Driving is even except on ridges, which stay slower, and bumps, which
  gain 0.7%, well under the 3% bar.
- Sim-edge laps mostly turn more: arm B has 1.2–2.0× A's `turned` degrees on every leg; arm D has more than C on
  ridges, bumps and steps but fewer on slalom (480 vs 552). They jump about the same.

**Planning accuracy**: relative error of predicted ticks against actual ticks, both models priced on the same first
plan from the lap's start body.

| table | set | n | legacy model | sim model | median paired difference (legacy − sim) | 95% CI |
|---|---|---|---|---|---|---|
| first segment (gate) | all arms | 93 | 0.020 | 0.032 | −0.006 | −0.014..−0.004 |
| first segment | legacy arms A,C | 47 | 0.068 | 0.104 | −0.032 | −0.032..−0.012 |
| first segment | sim arms B,D | 46 | 0.011 | 0.011 | −0.005 | −0.005..−0.003 |
| complete plans | all arms | 85 | 0.020 | 0.032 | −0.007 | −0.015..−0.005 |

- The sim model is slightly **less** accurate than the legacy closed-form costs. The CI excludes 0 on the wrong
  side, so measure 2 fails.
- By leg, the first-segment signed bias (legacy / sim) is: slalom +0.001 / +0.008, ridges +0.209 / +0.230,
  bumps −0.008 / +0.005, steps +0.074 / +0.104.
- Both models over-predict the jump legs, by about 20% on ridges and 7–10% on steps. The executor (with
  `kinematicTravel` on, as in every arm) crosses ridges faster than either model's MovementAscend-shaped jump. The
  sim copies `MovementAscend`'s jump rule, not the kinematic driver, which is risk R2 of the audit.

### Live A/B (Tier L), seed 71007, 6 reps, 96 laps (reproducibility run)

Same command with `-Dostinato.seed=71007`. Analysis: `build/simedge-live-71007.txt` and `-analysis.md` (not committed).

**Reliability:** 24/24 in every arm. No faults, no stuck keys. `simedge searches=58 fallbacks=0 capped=0
exceptions=0`. Holds.

| leg | B/A | D/C | C/A (noise) | B/A, D/C without first-plan wait (diagnostic) | turned A/B |
|---|---|---|---|---|---|
| slalom | 1.029 (0.995–1.047) | 1.032 (1.025–1.041) | 0.996 (0.920–1.006) | 0.996 / 1.000 | 612/652 |
| ridges | 1.102 (1.050–1.130) | 1.070 (1.056–1.095) | 1.020 (0.977–1.071) | 1.071 / 1.034 | 1320/1524 |
| bumps | 0.992 (0.985–0.998) | 0.992 (0.983–1.000) | 1.000 (1.000–1.002) | 0.990 / 0.990 | 354/714 |
| steps | 1.014 (1.003–1.022) | 1.005 (0.998–1.021) | 1.000 (0.990–1.000) | 0.980 / 0.971 | 748/1339 |

Planning accuracy (paired difference legacy − sim error; negative means the sim model is worse):

| table | n | legacy | sim | difference | 95% CI |
|---|---|---|---|---|---|
| first segment (gate), all arms | 94 | 0.063 | 0.059 | −0.005 | −0.014..−0.005 |
| complete plans, all arms | 85 | 0.017 | 0.029 | −0.006 | −0.014..−0.005 |

- The pattern of seed 71006 repeats: slower on ridges and slalom, about even on bumps (−0.8%), and per-lap
  predictions slightly worse under the sim model.
- One difference: with the first plan's wait removed, steps driving is 2–3% faster here (CIs below 1), where seed
  71006 showed 0.997. That gain is smaller than the planning wait it costs, it did not reproduce across seeds, and
  it is a diagnostic, not a gate measure.

### Phase 05 gate classification

| criterion | 71006 | 71007 | verdict |
|---|---|---|---|
| Reliability | holds | holds | **no regression** |
| 1. Execution efficiency | fails (1.03–1.09 slower on 3 legs) | fails (ridges 1.07–1.10, slalom 1.03) | fail |
| 2. Planning accuracy | fails (CI of difference below 0) | fails (CI below 0) | fail |
| 3. Route quality | Tier S only: 0.98–1.00 under the sim model's own price | — | not claimed |

**Result: NO IMPROVEMENT.** `simEdgeSearch` stays off, and legacy A* is the planner (DECISIONS D13).

## Phase 04 live validation (2026-11-08, branch `ostinato2/claude-review`, local only)

Scope: close the Phase 04 gate (STATUS B1–B3) with live 26.3 client evidence for the current (D12, 26.3) `PlayerSim`.
Phase 05 is not touched. `predictionTolerance` stays default **false**.

### Pre-registered criteria (written 2026-11-08, before any data was collected)

**Instruments** (harness-only; every one is off unless its `-D` property is set):

- `-Dostinato.tolerancetrace=true`: `ToleranceTraceTest`. It runs in a fresh Peaceful world and builds a course high
  over spawn: a flat field, a field of 1-high bumps, a 1-deep and a 3-deep still-water pool, and a ladder wall and a vine wall.
  - Each tick it drives the player with seeded scripted controls (forward, sprint, jump and a yaw). They are applied
    exactly as `PhysicsTravel` applies them: `setYRot`, the look target at pitch 0, and forced keys through
    `PlayerMovementInput`.
  - It predicts the next tick from the state it read (`ClientWorld.readPlayer`, `copyFrom`), with `jumpTicks` tracked
    from its own predictions as `PhysicsTravel` does.
  - At the next tick it writes the one-step error to `tolerance-trace.csv`.
  - The mode is `PredictionTolerance.classify(pre-tick state, forward, sprint)`, which is the consumers' own call.
- `-Dostinato.tickClock=true`: client tick time, from `Minecraft.tick` HEAD to RETURN, through a one-line hook in the
  existing tick-start mixin.
- `-Dostinato.legatotest.arms=tolerance`: `LegatoLiveTest` arms on `SimEdgeCourse`, in a Latin square:

  | Arm | `predictionTolerance` |
  |---|---|
  | A | off |
  | B | on |
  | C | off |
  | D | on |

  `-Dostinato.legatotest.stack=<travelStack>` sets the travel stack for all arms. Rows add tick-time p50/p95/p99/max,
  `PhysicsTravel` plans and drift replans, and `PhysicsTravel` driver time.

**Predictor per mode.** Each mode uses the simulator's own model for that mode:

- `tick()` for WALK, SPRINT, SPRINT_JUMP, FALL and CLIMB. `tick()` handles ladders and vines.
- `tickWater()` for FLUID.

Two diagnostics are written but are not part of the gate:

- the land `tick()` error on water ticks, which is what `SimTrace` computes there;
- the error with the yaw the client actually used, which is the model error with controller yaw lag removed.

**Excluded rows** (counted and reported, never silently dropped):

- the tick a server position or motion packet for the player arrived;
- the 3 ticks after a teleport;
- rest ticks: on the ground, no keys, horizontal speed under 0.003;
- ticks where the keys the client used differ from the scripted keys (an instrument fault).

Every other tick counts, including yaw lag, landings, collisions, wall and bump contact, and climb exits.

**Sessions.** Dev seed 81001, held-out seed 81002. Same course, different scripted control sequences. Target: ≥ 2000
usable ticks per mode per session; a session stops at 30,000 ticks.

**Bounds (B1):**

- The bound for a mode is the dev session's p99 one-step Euclidean error.
- On the held-out session, a mode **validates** when:
  - n ≥ 300 in both sessions, and
  - the held-out share of ticks over the dev bound is ≤ 2.0%.
- A mode with n < 300 in either session is **insufficient data**.
- A mode with n ≥ 300 and a held-out share over 2.0% is **not validated**. The bound is not re-derived from held-out
  data.
- **Unacceptable prediction error (FAIL):** any mode's held-out p99 > 0.30 blocks with n ≥ 300. That is above every
  historical live p99 of the old model (max 0.2374), and above one tick of steady ground sprint (about 0.28).
- Validated modes replace their `PredictionTolerance.MEASURED` value with the dev p99, labelled Tier L, 26.3 model.
  That changes flag-on behaviour only; the default stays off.
- Modes that do not validate keep their current value. Their basis is relabelled as **not validated** for the 26.3
  model. The old 1.16-model numbers are never relabelled as validated.
- The A/B runs with the table as it stands after this step.

**Overhead (B2).** Measured in the A/B laps:

- Per lap: p95 and p99 of client tick time.
- Pairs: B against A and D against C, same leg and repetition, pooled over both A/B runs.
- Statistic: the median paired difference (on − off), with a bootstrap 95% CI (10,000 resamples, fixed seed).
- **Acceptable:** the CI upper bound is ≤ 0.5 ms for both p95 and p99. That is 1% of the 50 ms tick.
- **FAIL:** a CI lower bound is > 0.5 ms.
- **Otherwise:** inconclusive.

**A/B (B3).** `-Dostinato.legatotest.arms=tolerance -Dostinato.legatotest.stack=physics`, 6 reps (96 laps).

- Seeds: 81011, then 81012 for reproducibility.
- Why `physics`: under the production `auto` stack, `PHYSICS` drove at most 3 ticks in any of the 96 Phase 05 laps
  (`build/simedge-live-71006.txt`). The flag's only behaviour change is `PhysicsTravel`'s drift check, so an `auto` A/B
  would measure almost nothing.

Criteria:

- **Reliability (must hold in both seeds):**
  - reached(B) ≥ reached(A) − 1 and reached(D) ≥ reached(C) − 1;
  - no lap with stuck keys;
  - no fault code in B/D that A/C never show;
  - no death.
- **Noise:** C/A per-leg median lap-ticks ratio. A ticks claim on a leg needs its effect to exceed that leg's A/A′
  spread.
- **Improvement (any one, in both replications B/A and D/C, in both seeds):**
  1. Completion time: paired lap-ticks ratio ≤ 0.97 on ≥ 2 legs, with no leg ≥ 1.03.
  2. Recovery: per-lap cancels + rewinds lower with the flag on (paired mean difference < 0, bootstrap 95% CI
     excluding 0).
  3. Reliability: strictly more laps reached in B than in A and in D than in C.
- **Regression (FAIL):**
  - reliability fails, or
  - lap-ticks ratio ≥ 1.03 on ≥ 2 legs in both replications and both seeds, beyond the A/A′ spread.
- **Otherwise:** "no improvement". The implementation stands, but no navigation benefit is claimed.

**Phase 04 gate:**

- **PASS:** all six modes validated, no unacceptable error, overhead acceptable, and no A/B regression.
- **FAIL:** unacceptable error, overhead FAIL, or A/B regression.
- **PROVISIONAL:** anything else, such as a mode with insufficient data, a mode not validated, or an inconclusive
  overhead result.

**Addendum (written 2026-11-08 during the dev session, before the held-out session ran):**

- The trace CSV writes errors with 6 decimals. Every usable FALL row seen so far is below 5e-7: no-key airborne ticks are
  gravity and drag only, which the 26.3 model reproduces to float precision.
- A dev p99 that rounds to 0 at that resolution is recorded as "< 1e-6". Its bound is set to the instrument resolution,
  1e-6. That is not a tuned value: a bound of exactly 0 would call every non-zero rounding error a miss.
- The held-out share over the bound is counted at the same resolution.
- The smoke run that checked the harness (seed 81009, target 60 per mode) is a harness check only. Its rows are kept in
  `build/phase04/smoke-81009/` and are not used for any bound.
- The smoke run used a 4-deep pool by mistake. The dev and held-out sessions use the pre-registered 3-deep pool.

### Live traces (B1), 26.3 client, D12 model (VERIFIED, Tier L)

Commands (PowerShell, repo root, nothing else running; one client session per seed):

```
$env:JAVA_TOOL_OPTIONS='-Dostinato.noMouseGrab=true -Dostinato.windowPos=2600,40 -Dostinato.testHarness=true -Dostinato.tickClock=true -Dostinato.tolerancetrace=true -Dostinato.tolerancetrace.exit=true -Dostinato.seed=81001'
.\gradlew.bat --offline --no-daemon "-Dorg.gradle.jvmargs=-Xmx1536M" :fabric:runClient -PclientXmx=1536M *> trace-81001.log
# held-out: the same with -Dostinato.seed=81002
# each session writes fabric/run/client/tolerance-trace.csv and tolerancetrace-result.txt, moved out before the next
python scripts/tolerance-trace.py docs/ostinato2/raw/phase04/trace-81001.csv.gz docs/ostinato2/raw/phase04/trace-81002.csv.gz
```

Raw rows, per-session summaries and the analysis output: `docs/ostinato2/raw/phase04/`.

| session | client ticks | segments | falls | fall-offs | deaths | key-mismatch exclusions |
|---|---|---|---|---|---|---|
| dev 81001 | 14,614 | 233 | 127 | 0 | 0 | 0 |
| held-out 81002 | 14,850 | 240 | 136 | 0 | 0 | 0 |

**Bounds** (dev p99 is the bound; held-out share over it):

| mode | dev n | dev p50 | dev p95 | dev p99 (bound) | dev max | held-out n | held-out p99 | held-out over bound | status |
|---|---|---|---|---|---|---|---|---|---|
| WALK | 2069 | 0.000846 | 0.001954 | 0.003547 | 0.005995 | 2076 | 0.003233 | 17/2076 = 0.82% | validated |
| SPRINT | 2000 | 0.001234 | 0.002235 | 0.004708 | 0.005901 | 2017 | 0.005054 | 31/2017 = 1.54% | validated |
| SPRINT_JUMP | 2110 | 0.000237 | 0.000415 | 0.000452 | 0.017837 | 2078 | 0.000454 | 21/2078 = 1.01% | validated |
| FALL | 2000 | < 1e-6 | < 1e-6 | < 1e-6 → 0.000001 (addendum) | < 1e-6 | 2000 | < 1e-6 | 0/2000 = 0.00% | validated |
| FLUID (`tickWater`) | 2072 | 0.000143 | 0.000319 | 0.000346 | 0.040430 | 2020 | 0.000346 | 17/2020 = 0.84% | validated |
| CLIMB (ladders, vines) | 2000 | 0.000000 | 0.000308 | 0.001103 | 0.001663 | 2062 | 0.001135 | 22/2062 = 1.07% | validated |

**B1: 6/6 validated.** No mode is near the 0.30 FAIL line; the largest held-out p99 is 0.005054 (SPRINT).
`PredictionTolerance.MEASURED` now holds the six dev p99s, Tier L, basis "live 26.3 trace, D12 model".

**Excluded rows** (counted, not dropped silently):

| mode | dev packet | dev settle | dev keys | dev rest | held-out packet | held-out settle | held-out keys | held-out rest |
|---|---|---|---|---|---|---|---|---|
| WALK | 44 | 56 | 0 | 1410 | 64 | 60 | 0 | 1551 |
| FALL | 73 | 385 | 0 | 0 | 62 | 408 | 0 | 0 |
| FLUID | 0 | 45 | 0 | 21 | 0 | 45 | 0 | 60 |
| CLIMB | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 2 |

SPRINT and SPRINT_JUMP had no excluded rows. Settle rows are the 3 ticks after each teleport (fall and pool
segments). Rest rows are standing still between segments.

**Diagnostics (not gated), p99 of usable rows:**

| mode | dev, yaw the client used | held-out, yaw the client used | dev, land `tick()` | held-out, land `tick()` |
|---|---|---|---|---|
| WALK, SPRINT, SPRINT_JUMP, FALL, CLIMB | 0.000000 | 0.000000 | = primary | = primary |
| FLUID | 0.000000 | 0.000000 | **0.385000** | **0.385000** |

- **With the yaw the client applied, the D12 model's p99 is 0 in every mode.** Nearly all the residual error is the
  applied yaw differing from the commanded yaw: p50 0.56°, p99 1.02°, max 5.5° (both sessions). That gap exists for
  every consumer that sets yaw the same way (`PhysicsTravel` does), so it belongs in the bound.
- **FLUID, land `tick()`: p99 0.385.** `SimTrace` predicts every tick with `tick()` (`SimTrace.java:109`), including
  water ticks. Its flag-on FLUID verdicts therefore judge a land prediction against a water bound. That is a consumer
  mismatch, not a model error (`tickWater` p99 0.000346). It is diagnostic only (`SimTrace` drives nothing), and is
  recorded in D14, not fixed here.
- Script coverage: commanded turns are at most 30°/tick (p99 30.0), under the 32°/tick aim cap. Turns over the cap are
  not sampled. `PhysicsTravel` turns in place, keys up, when its step is over the cap (`PhysicsTravel.java:151`).

**Tails (max rows, not gated):**

| session, mode | max | rows | cause |
|---|---|---|---|
| dev SPRINT_JUMP | 0.0178 | 3 | leaving the pool with the swimming flag still set; error with the yaw used is the same, so model, not yaw |
| dev FLUID | 0.0404 | 2 | jump at the water surface, dy −0.04; the water-jump is unmodelled (D12) |
| held-out SPRINT | 0.0890 | 1 | error with the yaw used is 0: a 0.4° yaw difference at a bump edge |
| held-out WALK | 0.0294 | 1 | walking into the ladder wall; error with the yaw used is the same, so a model residual at ladder contact |

### Live A/B and tick time (B2, B3), seeds 81011 and 81012 (VERIFIED, Tier L)

The table above is in `MEASURED` for every flag-on arm. Commands (PowerShell, repo root, nothing else running):

```
$env:JAVA_TOOL_OPTIONS='-Dostinato.noMouseGrab=true -Dostinato.windowPos=2600,40 -Dostinato.testHarness=true -Dostinato.tickClock=true -Dostinato.legatotest=true -Dostinato.legatotest.exit=true -Dostinato.legatotest.arms=tolerance -Dostinato.legatotest.stack=physics -Dostinato.legatotest.reps=6 -Dostinato.seed=81011'
.\gradlew.bat --offline --no-daemon "-Dorg.gradle.jvmargs=-Xmx1536M" :fabric:runClient -PclientXmx=1536M *> ab-81011.log
# reproducibility: the same with -Dostinato.seed=81012
# each run appends fabric/run/client/tolerance-ticks.csv, moved out before the next
python scripts/tolerance-ab.py docs/ostinato2/raw/phase04/ab-81011-lines.txt docs/ostinato2/raw/phase04/ab-81012-lines.txt
python scripts/tolerance-ab-diag.py docs/ostinato2/raw/phase04/ab-81011-ticks.csv.gz docs/ostinato2/raw/phase04/ab-81011-lines.txt docs/ostinato2/raw/phase04/ab-81012-ticks.csv.gz docs/ostinato2/raw/phase04/ab-81012-lines.txt
```

`ab-*-lines.txt` holds every `LEGATOTEST` line of the run log. The script output is in `ab-analysis.txt` and
`ab-diag.txt`. Each run took 29 minutes, 96 laps each.

**Reliability (both seeds: holds):**

| seed | A reached | B reached | C reached | D reached | faults | stuck keys | deaths | cancels + rewinds |
|---|---|---|---|---|---|---|---|---|
| 81011 | 24/24 | 24/24 | 24/24 | 24/24 | none | 0 | 0 | 0 in every lap |
| 81012 | 24/24 | 24/24 | 24/24 | 24/24 | none | 0 | 0 | 0 in every lap |

**Lap ticks, per-leg median paired ratio** (reached pairs, n = 6 each):

| leg | 81011 B/A | 81011 D/C | 81011 C/A (noise) | 81012 B/A | 81012 D/C | 81012 C/A (noise) |
|---|---|---|---|---|---|---|
| slalom | 0.997 | 0.999 | 0.999 | 0.994 | 0.996 | 0.999 |
| ridges | 1.005 | 0.998 | 0.996 | 0.991 | 1.015 | 0.991 |
| bumps | 1.003 | 1.000 | 0.998 | 1.000 | 1.000 | 1.000 |
| steps | 0.988 | 0.988 | 1.010 | 0.962 | 0.975 | 0.977 |

- Completion time: one leg in one replication is ≤ 0.97 beyond its noise (81012 steps B/A 0.962; D/C 0.975). The
  criterion needs ≥ 2 legs in both replications and both seeds: **not met**. No leg is ≥ 1.03.
- Recovery: there was nothing to recover from. No lap in any arm cancelled or rewound, so the paired difference is 0
  with a CI of 0: **no improvement**.
- Reliability: every arm reached 24/24, so it is not strictly more: **no improvement**.
- Regression: none. Reliability holds, and no leg is ≥ 1.03.

**B3 verdict: no improvement.** The implementation stands, but no navigation benefit is claimed.

**Tick time (B2), per-lap quantiles, pairs B–A and D–C, both seeds pooled, n = 96:**

| statistic | median off | median on | median paired diff (on − off) | bootstrap 95% CI | verdict |
|---|---|---|---|---|---|
| p95 (gated) | 17.50 ms | 18.80 ms | +0.591 ms | −0.014 .. +1.224 ms | inconclusive (upper > 0.5) |
| p99 (gated) | 87.18 ms | 92.37 ms | −2.979 ms | −4.511 .. −1.300 ms | acceptable |
| p50 (diagnostic) | 5.20 ms | 5.31 ms | +0.085 ms | +0.064 .. +0.150 ms | — |
| max (diagnostic) | 124.72 ms | 118.81 ms | −1.882 ms | −5.822 .. −0.418 ms | — |

**B2 verdict: inconclusive.** The p95 CI crosses the 0.5 ms line: it is not shown to be under it (acceptable), nor
over it (FAIL). This is with the flag on. With the flag off (the default) the old check runs unchanged, so production
pays nothing (`PredictionToleranceTest.flagOff*`). Seed 81011 alone gave p95 +1.215 ms (CI +0.109 .. +1.793).

**Where the flag-on time goes** (diagnostic, not pre-registered, never gated; `scripts/tolerance-ab-diag.py`):

| measure (96 pairs) | flag off | flag on |
|---|---|---|
| `PhysicsTravel` drift replans, total | 60 | 1650 |
| `PhysicsTravel` plans, total | 3111 | 4433 |
| `PhysicsTravel` time per lap, paired on − off | — | median +26.4 ms, mean +50.3 ms |
| degrees turned per lap, paired on − off | — | median +146°, mean +343° |
| PHYSICS-driven ticks: p50 / p95 / p99 | 5.40 / 20.84 / 65.60 ms (n=16,994) | 5.57 / 23.23 / 62.93 ms (n=17,014) |
| BARITONE-driven ticks: p50 / p95 / p99 | 5.21 / 7.73 / 122.91 ms | 5.25 / 7.85 / 118.68 ms |

With the 26.3 bounds, the flag-on drift check fires about 17 times a lap where the old summed-axis 0.1 check fires
about 0.6 times. Each firing is a fresh `PhysicsPathfinder` search, on the tick. That is the p95 shift. Laps take
the same number of ticks, and turn more. The per-lap p99 and max are set by the 3 or so slowest ticks of a
~300-tick lap. Those ticks are mostly BARITONE-driven (p99 of 119–123 ms in both arms), not PHYSICS-driven, so the
replans do not move them. Their cause was not investigated. Laps allocate 0.9–2.1 GB each.

### Phase 04 gate classification (pre-registered rule)

| requirement | result |
|---|---|
| All six modes validated (B1) | **met**: 6/6, held-out share over the bound 0–1.54% (criterion ≤ 2.0%) |
| No unacceptable prediction error | **met**: largest held-out p99 0.005054 (criterion ≤ 0.30) |
| Overhead acceptable (B2) | **not shown**: p95 inconclusive (CI −0.014 .. +1.224 ms), p99 acceptable |
| No A/B regression (B3) | **met**: reliability holds in both seeds, no slow legs. Result: no improvement |

**Gate: PROVISIONAL.** It is not PASS, because acceptable overhead is not shown. It is not FAIL: there is no
unacceptable error and no regression, and the overhead is not shown to be over 0.5 ms. The pre-registration names
an inconclusive overhead result as PROVISIONAL. More seeds were not run to narrow the CI, because the
pre-registration fixed two runs.

### Full suite at the validation HEAD (VERIFIED, Tier U)

`.\gradlew.bat --offline --no-daemon --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx1536M" cleanTest test -x nativeHost`:
1535 tests, 1 failed, 19 skipped, 4 min 22 s. The failure is the pre-existing B5
`SecondoTest.plannedApisAreAbsentOnThisBranchAndInvokeIsANoop`, the same counts as at the Phase 05 HEAD. The targeted
classes all pass:
- `PredictionToleranceTest` (7, 1 env-gated skip);
- `SimEdgeTest` (16, 1 env-gated skip);
- `SimTraceTest`, `PhysicsPathfinderTest`, `TravelStackTest`, `SettingCatalogTest`, `PlayerSim263Test` and
  `CrossModelModesTest`.

## Phase 05B: SimEdge diagnosis (2026-11-08, branch `ostinato2/claude-review`, local only)

Instrumentation is commit e071cc7d9 (flag-gated, no behaviour change). The raw evidence is in
`docs/ostinato2/raw/phase05b/`.

### Planning cost, headless (VERIFIED, Tier S)

`OSTINATO_SIMEDGE_PROFILE=1 .\gradlew.bat test --tests baritone.pathing.calc.SimEdgeProfileTest`. Each run plans
every `SimEdgeCourse` leg from the lap start body (at rest, facing +x) with the live budget of 500 ms primary and
2000 ms failure. It does 3 warm-up rounds, then 6 counted rounds. Raw: `headless-profile-h32.txt`.

| leg | A* ms | SimEdge ms | SimEdge result | nodes / cells | sims | sim ms | legacy move evals (distinct) | alloc MB |
|---|---|---|---|---|---|---|---|---|
| slalom | 7 | 501 | **timed out**, partial (44 of 60 cells) | 11648 / 908 | 84222 | 292 | 1147014 (89892) | 393 |
| ridges | 8 | 503 | **timed out**, partial (54 of 76) | 11968 / 976 | 85754 | 294 | 1181367 (96624) | 391 |
| bumps | 2 | 17 | complete | 518 / 220 | 2337 | 8 | 51183 (21681) | 13 |
| steps | 5 | 502 | **timed out**, partial (50 of 69) | 9728 / 599 | 73347 | 298 | 957627 (59301) | 383 |

A* allocates about 1 MB per plan and never times out.

### Planning cost, live (VERIFIED, Tier L)

The diagnostic run was seed 71006, 2 reps, 32 laps, with `-Dostinato.legatotest.arms=simedge
-Dostinato.legatotest.trace=true -Dostinato.simedge.profile=true`. Otherwise it used the Phase 05 command. Digest
(`python scripts/simedge-prof.py`): `diag-71006-profile-digest.txt`.

- **First plan:**

  | leg | A* first plan | SimEdge first plan |
  |---|---|---|
  | bumps | 3 ms | 27 ms |
  | ridges | 20 ms | 807 ms |
  | slalom | 12 ms | 853 ms |
  | steps | 8 ms | 722 ms |

  The SimEdge first plan timed out in 4/16 laps. The median `planWait` was 2 ticks for A* in every leg. For SimEdge
  it was 2 on bumps, 17.5 on ridges, 18.5 on slalom and 16 on steps.
- **Where SimEdge time goes, all 20 searches:**
  - Simulation takes 50–58% and legacy move evaluation 19–22%. Open set, states and GC take the remaining 23–28%.
  - There are 12.5–15.9 expanded states per cell, against 1 in A*, and 7.2–7.6 sims per expansion.
  - Legacy moves are evaluated 12–16× per distinct (cell, move): once for each motion state of the cell.
  - Only 1.8–2.3% of sims are exact repeats, so an input-keyed sim cache would save almost nothing.
  - Allocation is a median 175 MB per search, against 4 MB for A*.
- **Harness confound (not fixed: it applies to every arm, and Phase 05 ran with it):**
  - PathingBehavior's same-start timeout escalation (`plannedStarts`) persists between laps, because the 4 laps of a
    leg run back to back with an equal goal.
  - The first lap of each leg block plans with a 500 ms primary budget; the next three get 2000, 4000 and 8000 ms.
  - A* is unaffected, since it finishes in milliseconds.
  - SimEdge waits a median 12 ticks at position 0, where it returns a partial plan, and 16.5–18 ticks at positions
    1–3, where it runs to a complete plan.

### Where SimEdge laps lose time (VERIFIED, Tier L, diagnostic run)

Medians are per leg over 4 laps per arm. "Driving" is lap ticks minus the ticks before the first path.

| leg | ticks A* / SimEdge | wait before the first path | driving difference |
|---|---|---|---|
| slalom | 339 / 355 | 1 / 17.5 | −0.5 |
| ridges | 270 / 292 | 1 / 16.5 | +6.5 |
| bumps | 298 / 296 | 1 / 1 | −2 |
| steps | 293 / 306.5 | 1 / 15 | −0.5 |

- No lap had a mid-lap gap with no path.
- Progress against time (ticks to pass x = 10, 20, …, 80):
  - On steps SimEdge is ahead by 2, 7 and 4 ticks at x = 10, 20 and 30, then even.
  - On ridges it is 1 tick ahead to x = 30, then 3–7 behind. But from x = 33 on, both arms' first plans are the same
    cells and moves, so that loss is not a route choice. The C/A ridges noise this run was 1.015 and 1.064.
  - On bumps SimEdge is 2–2.5 ticks ahead from x = 60.
- The route differences are all near the start. From rest facing +x, SimEdge goes straight first (steps: along
  z = 70–71 to x = 14, then diagonals) where A* takes the diagonal line at once.
- Both of Phase 05's Tier-L runs fit this picture. Over the course, the first-plan wait was about 7.9 ticks per lap
  against about 2.75 ticks per lap of driving difference.

### Multi-tick prediction divergence (VERIFIED, offline replay of the live trace)

`OSTINATO_SIMEDGE_TRACE=<dir> .\gradlew.bat test --tests baritone.pathing.calc.SimEdgeDivergenceTest`. Raw:
`diag-71006-divergence-summary.txt`. The course is rebuilt headless. Windows containing a position packet or a
sneak are left out (there were none).

**Recorded inputs**, open loop. Horizontal position error (m), median / p90, over all drivers:

| k ticks | 1 | 2 | 5 | 10 | 20 | 40 |
|---|---|---|---|---|---|---|
| error | 0.001 / 0.006 | 0.002 / 0.016 | 0.005 / 0.057 | 0.011 / 0.151 | 0.028 / 0.503 | 0.105 / 1.478 |
| samples | 9815 | 9783 | 9687 | 9527 | 9207 | 8567 |

Vertical error has median and p90 of 0 at every k.

**Planner-assumed inputs**: SimEdge's own edge controller, chained along the path the executor was running from
each recorded state.

| k ticks | 1 | 2 | 5 | 10 | 20 | 40 |
|---|---|---|---|---|---|---|
| error | 0.007 / 0.046 | 0.015 / 0.093 | 0.044 / 0.297 | 0.067 / 0.637 | 0.098 / 1.245 | 0.167 / 1.689 |
| covered / samples (KINEMATIC) | 6600 / 6951 | 5767 / 6919 | 5157 / 6823 | 4370 / 6663 | 2999 / 6343 | 975 / 5849 |

- A chain stops at the first edge the sim does not model (Line, Descend). BARITONE-driven ticks are all on such
  edges, so they have no coverage.
- At 10–20 ticks the planner's assumed trajectory diverges 2–4× more at p90 than the recorded inputs do. The medians
  stay under 0.1 m.

**Steering**: |SimEdge yaw − executor yaw|, in degrees.

| driver and edge | median | p90 |
|---|---|---|
| KINEMATIC traverse | 4.8 | 31.4 |
| KINEMATIC diagonal | 1.3 | 30.3 |
| KINEMATIC ascend | 1.9 | 24.0 |

- The KinematicController aims along the polyline, so it cuts corners at turns. SimEdge aims at the destination
  cell centre.
- Sprint matches on 95% of traverse ticks and on every ascend tick.
- Ascend jumps: only 8 ground ticks were seen, so this is not judged.

**Per-edge duration**, executor ticks minus predicted ticks, median / mean |error|, over 1853 completed edges:

| move (driver) | n | from the recorded entry state, one edge | chained from the plan's start body | legacy cost |
|---|---|---|---|---|
| Traverse (KINEMATIC) | 1236 | 0.00 / 0.10 | 0.00 / 0.54 | +0.44 / 0.55 |
| Diagonal (KINEMATIC) | 231 | 0.00 / 0.06 | 0.00 / 0.16 | −0.04 / 0.23 |
| Ascend (KINEMATIC) | 12 | 0.00 / 0.67 | −4.00 / 3.18 | −3.63 / 2.96 |
| Descend (KINEMATIC) | 42 | not modelled | not modelled | −4.32 / 4.32 |
| Line (BARITONE) | 332 | not modelled | not modelled | +0.03 / 0.21 |

### Useful alternatives (VERIFIED, Tier L, diagnostic run)

Each lap's first plan was priced from the rest start body under the sim model and the legacy model. That price was
compared with the executor's ticks over the same completed prefix (`diag-71006-divergence-firstpaths.csv`).

| leg | A* path | SimEdge path |
|---|---|---|
| slalom | sim 341.8, legacy 331.7, realized 332–336 | sim 335.8, legacy 331.7, realized 332–333 |
| bumps | sim 293.6, realized 295 | sim 294.3, realized 292–293 |

- The executor follows SimEdge's routes at the speed the sim predicts. Those routes are worth 0–3 ticks per lap
  (≤ 1%). That gain is smaller than one first-plan wait.

### Single largest bottleneck

**First-plan latency.** SimEdge's search keeps 12–16 motion states per cell inside its 32-block horizon. Each
expanded state runs about 7 edge sims and about 100 legacy move evaluations.

- On 3 of 4 legs the first plan exceeds the 500 ms primary budget, live and headless.
- The lap starts about 15 ticks late, which is 70–100% of the SimEdge loss on slalom, ridges and steps.
- The controller mismatch is not the bottleneck. Per-edge timing from the real entry state is accurate (traverse
  mean error 0.10 ticks). Where the routes are equal (ridges past x = 33), the arms differ by no more than the A/A'
  noise.

### Pre-registered experiment (written 2026-11-08, before any experiment data)

- **Hypothesis.** The route value SimEdge adds is near the start, and its cost is the multi-state search over the
  whole 32-block horizon. Simulating only within 8 blocks of the search start should:
  - cut the first plan to a fraction of the budget;
  - keep the near-start choice (straight-ahead acceleration from rest);
  - be legacy beyond that.
- **Change.**
  - `simEdgeHorizon` is 8 in the experiment arm. It is an existing setting, read only when `simEdgeSearch` is on.
  - No planner code changes. Production defaults stay as they are: `simEdgeSearch` false, `simEdgeHorizon` 32.
- **Choice of 8.**
  - In the diagnostic run, sim cells grew roughly with the horizon in the 20-wide strips, at about 1000 cells for
    32. A quarter of that should bring the ridges, slalom and steps first plans from 0.5–0.9 s to roughly 0.1–0.2 s.
  - 8 blocks cover acceleration from rest (about 3 blocks) and a turn.
  - The value was not tuned on any data.
- **Arms** (`-Dostinato.legatotest.arms=simedge5b`; course, Latin square, budgets and harness as in Phase 05;
  trace and profile off):

  | arm | planner |
  |---|---|
  | A | legacy A* |
  | B | `simEdgeSearch`, horizon 32: the existing SimEdge |
  | C | legacy A* (A/A' control) |
  | D | `simEdgeSearch`, horizon 8: the experiment |

  Runs: 6 reps on each of seeds 71006 and 71007, the Phase 05 seeds. That is 2 × 96 laps.
- **Reliability (must hold in both seeds):**
  - reached(D) ≥ reached(A) and reached(D) ≥ reached(C);
  - no stuck keys;
  - no fault code in D that A and C never show;
  - simedge exceptions = 0.
- **Noise control:** C/A per-leg median ratio. A claim on a leg whose |C/A − 1| exceeds 3% needs an effect larger
  than that spread.
- **M1, against the original baseline** (Phase 05 measure 1, unchanged): paired lap-ticks median ratios D/A and
  D/C ≤ 0.97 on ≥ 2 legs, and no leg ≥ 1.03, in both seeds.
- **M2, against the existing SimEdge** (same rule): D/B ≤ 0.97 on ≥ 2 legs, and no leg ≥ 1.03, in both seeds.
- **Mechanism (reported; required for a class B claim):** median `planWait`(D) − median `planWait`(A) ≤ 3 ticks on
  every leg, in both seeds.
- **Classification:**

  | class | condition |
  |---|---|
  | A — improvement | reliability holds and M1 is met in both seeds, for D/A and D/C |
  | B — partial | reliability holds, M1 is not met, M2 is met in both seeds and the mechanism holds |
  | D — regression | reliability fails in either seed, or D/B ≥ 1.03 on ≥ 2 legs in both seeds |
  | C — no improvement | anything else |

- **Archive rule for SimEdge (recommendation only).** Recommend archiving SimEdge as a planner if no leg's D/A
  median ratio is ≤ 0.99 in both seeds, that is, if not even a 1% gain shows anywhere once latency is cut.
  Otherwise keep it as disabled research code.
- **Reported, not gating:**
  - the headless profile at horizon 8 (`OSTINATO_SIMEDGE_HORIZON=8`);
  - per-leg `planWait` and `planUs`;
  - the driving difference (ticks minus first-path wait).
- **On failure,** no further optimization attempt is made in Phase 05B.
