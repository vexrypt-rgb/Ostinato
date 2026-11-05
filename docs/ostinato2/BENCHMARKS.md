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
