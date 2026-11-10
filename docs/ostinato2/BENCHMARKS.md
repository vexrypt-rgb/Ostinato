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

### Experiment: SimEdge with `simEdgeHorizon` 8 (pre-registered above)

Experiment commit `b78f7d56b`: the harness arms and the profile test's horizon variable. No planner code changed.
Analysis: `python scripts/simedge5b-ab.py docs/ostinato2/raw/phase05b/ab-71006-lines.txt
docs/ostinato2/raw/phase05b/ab-71007-lines.txt`. It uses the same median and bootstrap (4000 resamples, seed 1) as
`scripts/simedge-ab.py`. Raw: `ab-71006-lines.txt`, `ab-71007-lines.txt` (every `LEGATOTEST` line of each counted run)
and `headless-profile-h8.txt`.

Command (PowerShell, 26.3 dev client, Peaceful, second monitor):
`$env:JAVA_TOOL_OPTIONS='-Dostinato.noMouseGrab=true -Dostinato.windowPos=2600,40 -Dostinato.testHarness=true
-Dostinato.legatotest=true -Dostinato.legatotest.exit=true -Dostinato.legatotest.arms=simedge5b
-Dostinato.legatotest.reps=6 -Dostinato.seed=<71006|71007>'; .\gradlew.bat :fabric:runClient -PclientXmx=1536M`.
Trace and profile were off. Every lap row records `simEdge` and `horizon`. The script checked that every lap of
every arm ran as pre-registered:

| arm | simEdgeSearch | horizon |
|---|---|---|
| A | false | 32 |
| B | true | 32 |
| C | false | 32 |
| D | true | 8 |

#### Headless (Tier S, reported, not gating)

`OSTINATO_SIMEDGE_PROFILE=1 OSTINATO_SIMEDGE_HORIZON=8`, otherwise as the profile above. Each cell is a median of 6
rounds.

| leg | A* ms | SimEdge h32 ms | SimEdge h8 ms | h8 result | h8 cells / nodes / sims | h8 alloc MB (h32) |
|---|---|---|---|---|---|---|
| slalom | 7 | 501 (timed out, 6/6) | 133 | complete, 6/6 | 1467 / 4083 / 18176 | 92 (393) |
| ridges | 9 | 503 (timed out, 6/6) | 201 | complete, 6/6 | 1538 / 5348 / 28267 | 140 (391) |
| bumps | 2 | 17 | 4 | complete, 6/6 | 224 / 240 / 72 | 2 (13) |
| steps | 5 | 502 (timed out, 6/6) | 136 | complete, 6/6 | 907 / 3824 / 20485 | 100 (383) |

At horizon 8 there are 2.8–4.2 nodes per cell on slalom, ridges and steps (1.1 on bumps), against 12.5–15.9 at
horizon 32. On those three legs the first plan still takes 19–27× A*'s time.

#### Live A/B (Tier L), seeds 71006 and 71007, 6 reps each, 2 × 96 laps

**Reliability (both seeds):**
- 24/24 reached in every arm, with no fault codes in any arm and no stuck or held keys.
- `simedge searches=54` (71006) and 53 (71007), with `fallbacks=0 capped=0 exceptions=0` in both.
- **Holds.**

**Lap ticks**, paired by leg and rep, median ratio (bootstrap 95% CI), n = 6 per cell:

| seed | leg | D/A | D/C | D/B | B/A | C/A (noise) |
|---|---|---|---|---|---|---|
| 71006 | slalom | 1.009 (0.963–1.012) | 0.997 (0.994–1.006) | **0.962** (0.921–0.978) | 1.044 (1.031–1.054) | 1.006 (0.967–1.013) |
| 71006 | ridges | 1.005 (0.995–1.057) | 1.005 (0.988–1.039) | 0.972 (0.902–0.997) | 1.057 (1.020–1.124) | 1.002 (0.989–1.034) |
| 71006 | bumps | 0.997 (0.997–1.003) | 0.998 (0.997–1.002) | 1.003 (1.002–1.007) | 0.995 (0.993–0.997) | 1.000 (0.998–1.002) |
| 71006 | steps | 0.988 (0.986–0.998) | 0.990 (0.986–1.010) | **0.961** (0.944–0.975) | 1.032 (1.019–1.047) | 0.998 (0.978–1.010) |
| 71007 | slalom | 1.013 (0.957–1.019) | 1.015 (1.004–1.018) | 0.977 (0.935–0.980) | 1.037 (1.017–1.046) | 1.000 (0.946–1.007) |
| 71007 | ridges | 1.015 (0.988–1.054) | 1.020 (1.002–1.061) | **0.931** (0.889–0.950) | 1.112 (1.061–1.140) | 1.006 (0.956–1.013) |
| 71007 | bumps | 1.000 (1.000–1.000) | 1.000 (1.000–1.000) | 1.012 (1.002–1.015) | 0.988 (0.985–0.998) | 1.000 (1.000–1.000) |
| 71007 | steps | 0.978 (0.969–1.005) | 0.978 (0.969–1.005) | 0.981 (0.946–0.988) | 1.012 (0.995–1.033) | 1.000 (0.985–1.016) |

- The C/A noise is within 0.6% at the median on every leg in both seeds, so the 3% noise rule does not bind.
- **Baseline unchanged.** B/A reproduces Phase 05's B/A on every leg within its CI:
  - Phase 05, 71006: 1.039, 1.090, 0.997, 1.036. Phase 05, 71007: 1.029, 1.102, 0.992, 1.014.
  - Arm A medians differ from Phase 05's by at most 3 ticks per leg:

    | leg | Phase 05 (71006 / 71007) | Phase 05B (71006 / 71007) |
    |---|---|---|
    | slalom | 339 / 341 | 337.5 / 338.5 |
    | ridges | 269.5 / 269.5 | 272.5 / 269 |
    | bumps | 299 / 298 | 299 / 298 |
    | steps | 294.5 / 295 | 295 / 295 |

**Mechanism: median first-plan wait (ticks), and median planner time per lap (ms):**

| seed | leg | wait A | wait B | wait C | wait D | D − A | planner A / B / D |
|---|---|---|---|---|---|---|---|
| 71006 | slalom | 2.5 | 12.5 | 2 | 4 | +1.5 | 8 / 685 / 103 |
| 71006 | ridges | 2 | 12 | 2 | 5 | +3 | 9 / 555 / 165 |
| 71006 | bumps | 2 | 2 | 2 | 2 | 0 | 3 / 15 / 3 |
| 71006 | steps | 2 | 12.5 | 2 | 5 | +3 | 7 / 528 / 116 |
| 71007 | slalom | 2 | 13 | 2 | 5 | +3 | 7 / 645 / 111 |
| 71007 | ridges | 2 | 13 | 3 | 5 | +3 | 8 / 571 / 164 |
| 71007 | bumps | 2 | 2 | 2 | 2 | 0 | 3 / 16 / 3 |
| 71007 | steps | 2 | 12.5 | 2 | 5 | +3 | 7 / 539 / 125 |

- D − A ≤ 3 on every leg in both seeds: **holds**, though at the limit on 5 of 8 leg-seeds.
- The latency cut is real: D's first plan is 4–5 ticks against B's 12–13.
- **Escalation confound, without profiling:**
  - B's wait is 11.5–12 at block position 0 and 12–13 at positions 1–3.
  - D's is 4–5 at every position, and A's and C's 2–2.5.
  - So the confound moves B by at most about 1.5 ticks and D not at all; it cannot change the classification. It was
    larger in the profiled diagnostic run because profiling slows the search.

**Diagnostic, not gating.** Driving is lap ticks minus first-plan wait, as a paired median ratio:

| seed | leg | D/A | D/B | B/A | C/A |
|---|---|---|---|---|---|
| 71006 | slalom | 1.004 | 0.984 | 1.003 | 1.006 |
| 71006 | ridges | 0.997 | 0.996 | 1.022 | 1.004 |
| 71006 | bumps | 0.997 | 1.005 | 0.993 | 1.000 |
| 71006 | steps | **0.980** (0.978–0.990) | 0.986 | 0.997 | 0.998 |
| 71007 | slalom | 1.006 | 1.000 | 1.006 | 1.000 |
| 71007 | ridges | 1.006 | 0.951 | 1.073 | 1.002 |
| 71007 | bumps | 1.000 | 1.014 | 0.986 | 1.000 |
| 71007 | steps | **0.969** (0.961–0.995) | 1.007 | 0.974 | 1.000 |

- This is the ceiling for any further SimEdge latency fix on this course. Even with no wait at all, horizon 8
  drives 2–3% faster than A* on steps only; slalom, ridges and bumps are even.
- Steps gains about 6–9 ticks while driving and loses 3 waiting. The gain is near the start, as the diagnosis found.
- On bumps, horizon 32 drives 0.7–1.4% faster than A* and horizon 8 does not. That small gain comes from simulating
  far from the start, which is cheap only on bumps (17 ms).
- Ridges B/A 1.022/1.073 in driving: the horizon-32 search's partial first plans also cost route quality. Horizon 8
  removes that (D/A 0.997/1.006).

#### Phase 05B classification

| criterion | 71006 | 71007 | verdict |
|---|---|---|---|
| Arms as pre-registered | yes | yes | — |
| Reliability | holds | holds | holds |
| M1 D/A (≤ 0.97 on ≥ 2 legs, no leg ≥ 1.03) | 0 legs ≤ 0.97; none ≥ 1.03 | 0 legs; none ≥ 1.03 | **not met** |
| M1 D/C | 0 legs; none ≥ 1.03 | 0 legs; none ≥ 1.03 | **not met** |
| M2 D/B | 2 legs (slalom 0.962, steps 0.961); none ≥ 1.03 | 1 leg (ridges 0.931); none ≥ 1.03 | **not met** (71007) |
| Mechanism (wait D − A ≤ 3) | holds | holds | holds |
| Regression (D/B ≥ 1.03 on ≥ 2 legs, both seeds) | 0 legs | 0 legs | no |

**Class C: NO IMPROVEMENT.**
- Horizon 8 removes most of SimEdge's planning loss. D/B is below 1 on 6 of 8 leg-seeds, and D is never ≥ 1.03
  against any arm.
- But it is not faster than legacy A* (M1). Its gain over the existing SimEdge (M2) reaches 0.97 on two legs only in
  seed 71006.
- The mechanism holds, but class B needs M2 in both seeds, so it is not class B.

**Archive rule:** steps D/A is 0.988 (71006) and 0.978 (71007), ≤ 0.99 in both seeds. The pre-registered rule
therefore recommends **keeping SimEdge as disabled research code**, not archiving it.

**Algorithmic or harness?** The D-versus-B difference is algorithmic: the same search simulates less, and no other
code changed. Nothing in the baseline changed:
- arm A reproduces Phase 05;
- the Phase 05B instrumentation sits behind `static final` flags read from system properties (`SimEdge.PROFILE`,
  the harness trace), which the JIT folds when they are off; both were off in the A/B;
- the escalation confound was left as in Phase 05.

#### Full suite at the Phase 05B results commit (VERIFIED, Tier U)

Same command as Phase 04: 1537 tests, 1 failed, 21 skipped, 4 min 26 s. The failure is the pre-existing B5
`SecondoTest.plannedApisAreAbsentOnThisBranchAndInvokeIsANoop`. The 2 extra tests and 2 extra skips against Phase 04
are the env-gated `SimEdgeProfileTest` and `SimEdgeDivergenceTest`, which skip without their variables.

## Phase 06: unified executor (2026-11-08, branch `ostinato2/claude-review`, local only)

Scope: the `unifiedExecutor` setting (default **false**).
- When on, the physics driver path (`PhysicsTravel`) drives the player's movement keys under one owner:
  - a `UnifiedExecutor` lease with an epoch;
  - writes from a stale epoch are rejected;
  - a cancel is acknowledged only once the player is stopped on the ground;
  - an emergency can preempt the lease;
  - the keys are released when the lease ends.
- Each tick, the driver compares its prediction with what the client observed. On a miss it gets one bounded
  correction (`CorrectionPolicy`). If the correction does not bring it back, it escalates to the existing drift
  replan.
- Unchanged:
  - the A* planner;
  - every other input site (only the `PhysicsTravel` path is migrated);
  - `predictionTolerance`.
- The flag stays default false in Phase 06, whatever the result below.

### Pre-registered criteria (written 2026-11-08, before any data was collected)

**Already seen, not gate evidence.**
- The 12 fixed scenarios in `UnifiedHeadlessTest` were run during development: walk, turn, sprint, jump,
  collision, velocity change, nudge, slippery ground, cancel, cancel mid jump, conflicting inputs, and release after
  failure.
- Their results were seen before this was written. They are unit evidence only and are not used for any criterion
  below.
- The batch draws fresh trials from seeds that were never run.

**Tiers.**
- **Tier S** is the headless batch. `UnifiedHarness` copies the driver tick on block worlds. It is not `PhysicsTravel`
  and not the client.
- **Tier L** is the live A/B on the 26.3 client.
- No Tier-L claim is made from headless results.

**Statistics.** Every bootstrap here uses:
- 10,000 resamples;
- `random.Random(20261108)`;
- the percentile 95% interval (sorted resample index 250 and 9749).

#### Tier S: headless batch (`UnifiedBatchBench`, judged by `scripts/unified-batch.py`)

**Run.**
- Command: `-Postinato_bench --tests "*UnifiedBatchBench"`.
- Trials: N = 300 per family, six families, so 1800 trials and 3600 runs.
- Seeds:
  - Family `k` (0-based, in the order below) draws from `new Random(6061 + k)`.
  - Every trial is generated before any run.
  - The warm-up draws from 7071 (4 trials per family, both arms) and is thrown away.
- Arm order alternates: legacy first on even trials, unified first on odd.
- Per run, the bench records:
  - outcome, ticks, plans, drift replans, jumps and sprints;
  - the end position and end state;
  - the keys down at the end, and whether the lease is still held;
  - cancel latency;
  - per-tick driver time (p50/p95/p99/max) and plan time;
  - thread CPU time and allocated bytes;
  - every executor counter.

**Families.** The model always assumes slipperiness 0.6. Start (0.5, 0.5) unless noted. 200 ticks max unless noted.

| Family | World | Draws |
|---|---|---|
| clean | ground x −3..3, z −1..14 | start x ∈ {−2.5 … 3.5} (integer + 0.5); goal x likewise; goal z ∈ {4.5 … 13.5} |
| kick | ground x −4..4, z −1..14; goal (0.5, 10.5) | velocity kick at tick 2..15; angle uniform 0..2π; magnitude uniform 0.05..0.6 |
| slip | ground x −3..3, z −1..14 | the world's slipperiness uniform 0.65..0.98; goal as in clean; 400 ticks max |
| conflict | ground x −4..4, z −1..12; goal z ∈ {5.5 … 10.5} | another behaviour presses `after` (LEFT, RIGHT or BACK) once the driver has written, and `before` (none, LEFT or RIGHT) ahead of it, from tick 1..8 for 3..20 more ticks, every 1..3 ticks |
| cancel | 50/50: the gap world (two pads, goal z 6.5) or the long world (x −1..1, z −1..20, goal z 16.5) | a cancel request every tick from tick 2..25 |
| edge | a 1-wide walkway z −1..14; goal (0.5, 12.5) | sideways kick at tick 2..15, vx = ±uniform(0.1, 1.0) |

The disturbed families are kick, slip, conflict and edge.

**H1. Clean: the flag changes nothing where nothing pushes the player.**
- Holds when, in every clean trial, legacy and unified are equal on outcome, ticks, plans, drift replans, end x/y/z,
  jumps and sprints.
- Also required: unified DIVERGENCES = 0, legacy drift replans = 0, and n > 0.

**H2. Reliability.** Per family, with allowed = ceil(1% of n):
- Non-cancel families: unified ARRIVED ≥ legacy ARRIVED − allowed.
- Cancel family: a cancel stop is the intended outcome, so the test is the other way round. Unified outcomes other
  than ARRIVED or CANCEL_STOP must be ≤ legacy outcomes other than ARRIVED, + allowed.
- Any REFUSED outcome in either arm fails H2. REFUSED means the executor refused its only driver.

**H3. Safety, every unified trial.**
- RELEASED_EARLY, OVERWRITTEN and STALE_REJECTS are 0.
- No conflicting press lands while the lease is held.
- For every outcome except TIMEOUT:
  - no key is down at the end;
  - the lease is not held;
  - the executor's final state matches the outcome:

    | Outcome | Final state |
    |---|---|
    | ARRIVED | COMPLETED_VERIFIED |
    | CANCEL_STOP | CANCELLED_CONFIRMED |
    | EMPTY_PLAN | FAILED |

- Every CANCEL_STOP ends on the ground.
- TIMEOUT outcomes are counted and reported, not judged on keys and lease: the harness does not hand the lease back
  on a timeout.
- CONFLICTS (presses refused) and ERASED are diagnostic.

**H4. The correction is bounded and counted.** Every unified trial must satisfy:
- CORRECTIONS ≤ plans;
- CORRECTIONS_ACCEPTED ≤ CORRECTIONS;
- ESCALATIONS = drift replans.

In addition, every legacy trial has all executor counters at 0.

**H5. Disturbed families: plans and ticks, paired unified − legacy.**
- Plans:
  - Statistic: the mean paired difference, with its bootstrap CI.
  - Recorded as **fewer replans** (Tier S) if the CI upper bound is < 0.
- Ticks:
  - Statistic: the mean of the unified/legacy ratio, over pairs where both arrived, with its bootstrap CI.
  - **Regression** if the CI lower bound is > 1.03.
  - **Faster** (Tier S) if the CI upper bound is < 0.97.

**H6. Cancel latency.** Cancel latency is the number of ticks from the first request to the stop. Legacy waits for the
run to end. Over cancel-family trials where the unified request came before arrival, H6 holds when:
- no unified trial lacks a stop or an arrival after the request;
- the median of the unified latencies is ≤ 10 ticks and the max is ≤ 20 ticks;
- among pairs where both latencies are defined and legacy's is ≥ 5, unified is strictly lower in ≥ 90% (1.0 when
  there are no such pairs).

Notes:
- Unified slower than legacy in a pair is reported, not gated. A mid-air request waits for the landing, and legacy can
  arrive first.
- No such trial at all fails H6.

**H7. Overhead.**
- Data: per-run driver tick time, p95 and p99, paired unified − legacy, all families pooled (plan ticks included).
- Statistic: the median paired difference, with its bootstrap CI.
- **Acceptable:** the CI upper bound is ≤ 0.5 ms for both p95 and p99.
- **FAIL:** a CI lower bound is > 0.5 ms.
- **Otherwise:** inconclusive.
- Diagnostic only: p50, max, per-family plan time, CPU and allocation, and the clean-only p50 difference.

**Tier S fails** if any of H1–H6 fails or H7 is FAIL.

#### Tier L: live A/B (`LegatoLiveTest`, judged by `scripts/unified-ab.py`)

**Run.**
- Properties: `-Dostinato.legatotest.arms=unified -Dostinato.legatotest.stack=physics`, 6 reps, on the simedge course
  (slalom, ridges, bumps, steps), so 96 laps per seed.
- Seeds: 81011, then 81012 for reproducibility.
- Arms: A and C have `unifiedExecutor` off; B and D have it on. `predictionTolerance` is at its default (off) in
  every arm.
- Replications: B/A and D/C. Noise: C/A (A against A′).
- Why `physics`: the executor only owns the `PhysicsTravel` path. The same reason is given in Phase 04 B3.

**L1. Reliability, in each seed.** All of the following must hold:
- the flag is on in every B/D lap and off in every A/C lap, and `predictionTolerance` is off in every lap;
- reached(B) ≥ reached(A) − 1 and reached(D) ≥ reached(C) − 1;
- no stuck-key log line;
- no death;
- no fault code in B/D that A/C never show.

**L2. Ownership safety, in each seed.** All of the following must hold:
- in every B/D lap, RELEASED_EARLY, OVERWRITTEN and STALE_REJECTS are 0;
- no executor counter except CANCEL_LATENCY_MAX moves in an A/C lap;
- OWNED_TICKS > 0 over the B/D laps (otherwise nothing was tested);
- every B/D lap has CORRECTIONS ≤ physics plans and ESCALATIONS = drift replans.

**L3. Completion time.**
- Statistic: per leg, the median paired lap-ticks ratio over pairs where both laps reached.
- A leg is:
  - **fast** when the ratio is ≤ 0.97 and 1 − ratio exceeds that leg's |C/A − 1|;
  - **slow** when the ratio is ≥ 1.03 and ratio − 1 exceeds that leg's |C/A − 1|.
- A pair (B/A or D/C) shows:
  - a **time improvement** with ≥ 2 fast legs and no leg ≥ 1.03 at all;
  - **slower** with ≥ 2 slow legs.

**L4. Replans and recovery.**
- Measures, per lap: physics plans, drift replans, and cancels + rewinds.
- Statistic: the mean paired difference (on − off) over all pairs, with its bootstrap CI.
- **Lower** if the mean is < 0 and the CI upper bound is < 0.
- **Higher** if the CI lower bound is > 0.
- Also recorded: whether strictly more laps reached with the flag on.

**L5. Overhead.**
- Data: client tick time, p95 and p99 per lap, paired on − off for the same seed, leg and repetition, both seeds pooled.
- Statistic: the median paired difference, with its bootstrap CI.
- **Acceptable:** the CI upper bound is ≤ 0.5 ms for both p95 and p99.
- **FAIL:** a CI lower bound is > 0.5 ms.
- **Otherwise:** inconclusive.
- Diagnostic only: p50, max and allocation.

**L6. Cancel latency: not measured live.** The course cancels only at lap end, so the live number would not mean
anything. Cancel latency is a Tier-S result only (H6).

**Tier L verdict.**
- **FAIL:** any of the following:
  - L1 or L2 fails in either seed;
  - L3 is slower in both replications and both seeds;
  - physics plans are higher (L4) in both replications and both seeds;
  - L5 is FAIL.
- **IMPROVEMENT:** any one of time (L3), physics plans, drift replans, cancels + rewinds (L4), or strictly more
  reached improves in both replications and both seeds.
- **Otherwise:** "no improvement, no regression".

If the live run cannot be done, that is recorded as a blocker, and no Tier-L claim is made.

#### Phase 06 gate

- **PASS:** all of the following:
  - Tier S does not fail;
  - H3 and L2 hold;
  - H7 and L5 are acceptable;
  - the Tier L verdict is IMPROVEMENT.
- **FAIL:** any of the following:
  - a safety violation (H3 or L2);
  - a reliability failure (H2 or L1);
  - H1, H4 or H6 fails;
  - a regression (H5 regression, L3 slower, or L4 more plans as defined above);
  - overhead FAIL (H7 or L5).
- **PARTIAL:** anything else. For example:
  - safety holds and nothing regresses, but improvements are Tier S only;
  - the live evidence is missing or incomplete;
  - an overhead result is inconclusive.
- Whatever the class, `unifiedExecutor` stays default false in Phase 06. Enabling it is a later decision.

### Phase 06 results

Judged against the criteria above, which were committed (`f7ad9d0af`) before the batch or the live A/B ran. No
criterion was changed after a result was seen.

#### Tier S: headless batch (VERIFIED, Tier S)

Command: `gradlew test -Postinato_bench --tests "*UnifiedBatchBench"` with `OSTINATO_WRITE_DOCS=1`, at `f7ad9d0af`.
Analysis: `python scripts/unified-batch.py docs/ostinato2/raw/phase06/headless-batch.csv`.
Raw (`docs/ostinato2/raw/phase06/`):
- `headless-batch.csv`: one row per run, 3600 rows, every counter;
- `headless-batch-run.txt`: seeds, n, wall time, JVM, OS;
- `headless-analysis.txt`: the script's full output.

The run: seed 6061, warm-up seed 7071, n = 300 per family, so 1800 trials and 3600 runs. Wall time 96.6 s, Java
25.0.4.1, Windows 11, 8 CPUs. Unified ran first in 900 trials and second in 900.

**H1 clean: holds.** Over 300 clean trials, legacy and unified are equal on every compared field (0 mismatches).
Unified DIVERGENCES and legacy drift replans are both 0. Both arms end 252 ARRIVED and 48 EMPTY_PLAN.

**H2 reliability: FAILS (slip).** Allowed loss is ceil(1% of 300) = 3.

| family | legacy arrived | unified arrived | other ends (legacy / unified) | holds |
|---|---|---|---|---|
| clean | 252 | 252 | 48 / 48 EMPTY_PLAN | yes |
| kick | 300 | 300 | — | yes |
| slip | 247 | **239** | 53 / **61** EMPTY_PLAN | **NO** (loss 8 > 3) |
| conflict | 300 | 300 | — | yes |
| cancel | 300 | 45 + 255 CANCEL_STOP | — | yes (0 unintended ends) |
| edge | 238 | 238 | 62 / 62 EMPTY_PLAN | yes |

There were no REFUSED or TIMEOUT ends in either arm. In the slip family:
- 9 trials arrived with legacy only: 19, 39, 43, 60, 108, 138, 174, 200 and 265.
- 1 trial arrived with unified only: 113.
- Every lost trial ended EMPTY_PLAN: the A* planner used up its node budget (1500 in the harness) and returned no
  plan.

**H3 safety: holds.**
- Over every unified trial: RELEASED_EARLY, OVERWRITTEN and STALE_REJECTS are 0, and no conflicting press landed.
- Every non-timeout end has no key down, no lease held, and the final state that matches its outcome.
- Every CANCEL_STOP ended on the ground. There were no TIMEOUT ends.
- Diagnostic: the gate refused 2422 presses (CONFLICTS), and ERASED is 1570. In the legacy arm, 2506 conflicting
  presses landed.

**H4 correction bounded and counted: holds** (0 violations). Legacy executor counters are all 0.

| family | divergences | corrections | accepted | escalations (= unified drift replans) | legacy drift replans |
|---|---|---|---|---|---|
| clean | 0 | 0 | 0 | 0 | 0 |
| kick | 281 | 281 | 30 | 251 | 281 |
| slip | 1905 | 1207 | 725 | 1180 | 1931 |
| conflict | 0 | 0 | 0 | 0 | 2501 |
| cancel | 0 | 0 | 0 | 0 | 0 |
| edge | 300 | 300 | 21 | 279 | 300 |

In conflict, unified has no divergences at all, because the gate refused the other behaviour's presses before the game
tick. Legacy let them land and replanned 2501 times.

**H5 plans and ticks: no regression.** Paired unified − legacy, bootstrap 95% CI:

| family | plans mean diff (CI) | fewer replans | arrived pairs | ticks mean ratio (CI) | faster / equal / slower | regression |
|---|---|---|---|---|---|---|
| kick | −0.100 (−0.133..−0.067) | yes | 300 | 1.0002 (1.0000..1.0005) | 0 / 298 / 2 | no |
| slip | −2.517 (−3.107..−1.967) | yes | 238 | 0.9999 (0.9988..1.0011) | 14 / 212 / 12 | no |
| conflict | −8.337 (−8.933..−7.773) | yes | 300 | **0.8530** (0.8416..0.8637) | 297 / 3 / 0 | no |
| edge | −0.070 (−0.100..−0.043) | yes | 238 | 1.0011 (1.0005..1.0018) | 0 / 227 / 11 | no |

Fewer replans in all four disturbed families (Tier S). Faster in conflict only (Tier S).

**H6 cancel latency: holds.**
- 258 cancel trials had the request before arrival. Unified ended 255 CANCEL_STOP and 3 ARRIVED.
- Unified latency: median 1 tick, max 12. Legacy, which waits for the run to end: median 35.
- No unified trial lacked a stop or an arrival after the request. Unified was never slower than legacy.
- Among the 229 pairs where legacy latency is ≥ 5, unified was strictly lower in 229 (100%).

**H7 overhead: acceptable.** Per-run driver tick time, paired unified − legacy, all families pooled:

| statistic | median diff (95% CI) | legacy median | unified median |
|---|---|---|---|
| p95 | +0.60 µs (+0.50..+0.70) | 182.30 µs | 27.10 µs |
| p99 | −64.90 µs (−107.40..−9.20) | 15425.40 µs | 14050.35 µs |
| p50 (diagnostic) | +0.00 µs (+0.00..+0.00) | 0.30 µs | 0.30 µs |

Caveats:
- **p99 equals the maximum in every one of the 3600 runs.** Every run has fewer than 100 ticks (median 34), so the
  nearest-rank p99 is the run's slowest tick, which is a planning tick. The p99 row measures replanning, not the
  executor.
- Clean only, where both arms do the same work: p50 diff +0.100 µs (CI +0.100..+0.100).
- Thread CPU time is only resolved to 15.625 ms on this machine, so its per-family medians are coarse.

Diagnostic medians per run, legacy → unified:

| family | plan ms | alloc KB |
|---|---|---|
| clean | 17.692 → 17.811 | 45356.5 → 45357.7 |
| kick | 10.625 → 10.438 | 32293.7 → 31211.2 |
| slip | 33.843 → 32.289 | 96090.2 → 90568.6 |
| conflict | 19.684 → 2.707 | 58605.7 → 7789.6 |
| cancel | 25.053 → 25.012 | 61584.6 → 61553.6 |
| edge | 29.408 → 29.248 | 81579.6 → 79160.0 |

**Tier S: FAIL** (H2, slip). Everything else held: H1, H3, H4 and H6 hold, H5 has no regression, and H7 is
acceptable. Fewer replans in kick, slip, conflict and edge, and faster in conflict (Tier S only).

#### Tier L: live A/B on the 26.3 client (VERIFIED, Tier L)

Command, per seed (PowerShell, from the repository root; one client at a time, no other JVM running):

```
$env:JAVA_TOOL_OPTIONS='-Dostinato.noMouseGrab=true -Dostinato.windowPos=2600,40 -Dostinato.testHarness=true -Dostinato.tickClock=true -Dostinato.legatotest=true -Dostinato.legatotest.exit=true -Dostinato.legatotest.arms=unified -Dostinato.legatotest.stack=physics -Dostinato.legatotest.reps=6 -Dostinato.seed=<seed>'
.\gradlew.bat --offline --no-daemon "-Dorg.gradle.jvmargs=-Xmx1536M" :fabric:runClient -PclientXmx=1536M *> ab-<seed>.log
```

Analysis: `python scripts/unified-ab.py docs/ostinato2/raw/phase06/ab-81011-lines.txt docs/ostinato2/raw/phase06/ab-81012-lines.txt`.
Raw (`docs/ostinato2/raw/phase06/`):
- `ab-<seed>-lines.txt`: every `LEGATOTEST` line of the client log, one row per lap with the per-lap executor counters;
- `ab-<seed>-result.txt`: the harness's own result file;
- `ab-<seed>-ticks.csv.gz`: the per-tick `unified-ticks.csv`;
- `ab-analysis.txt`: the script's full output for both seeds.

Both seeds ran to the end: 96 of 96 laps per seed, on the `physics` stack, the flag on in every B/D lap and off in
every A/C lap, `predictionTolerance` off in every lap.

**L1 reliability: holds in both seeds.**

| seed | A reached | B reached | C reached | D reached | stuck-key lines | deaths | new B/D faults |
|---|---|---|---|---|---|---|---|
| 81011 | 24/24 | 24/24 | 24/24 | 24/24 | 0 | 0 | none |
| 81012 | 24/24 | 24/24 | 24/24 | 24/24 | 0 | 0 | none |

Physics plans / drift replans over the 24 laps of each mode (diagnostic):

| seed | A | B | C | D |
|---|---|---|---|---|
| 81011 | 800 / 20 | 777 / 9 | 788 / 19 | 772 / 6 |
| 81012 | 786 / 19 | 775 / 8 | 784 / 20 | 781 / 9 |

**L2 ownership safety: holds in both seeds** (judged per B/D lap, as pre-registered).
- In every B/D lap, RELEASED_EARLY, OVERWRITTEN and STALE_REJECTS are 0.
- No executor counter moved in any A/C lap.
- OWNED_TICKS over the B/D laps: 8811 (81011) and 8746 (81012), of which INTACT_TICKS 8650 and 8590.
- In every B/D lap, CORRECTIONS ≤ physics plans and ESCALATIONS = drift replans.

Executor counters summed over the laps of each mode:

| seed | mode | ACQUIRES | COMPLETIONS | FAILURES | CANCEL_STOPS | DIVERGENCES | CORRECTIONS | ACCEPTED | ESCALATIONS |
|---|---|---|---|---|---|---|---|---|---|
| 81011 | B | 137 | 54 | 59 | 1 | 16 | 16 | 7 | 9 |
| 81011 | D | 141 | 58 | 59 | 4 | 15 | 15 | 9 | 6 |
| 81012 | B | 140 | 57 | 59 | 3 | 12 | 11 | 4 | 8 |
| 81012 | D | 137 | 57 | 56 | 4 | 14 | 14 | 5 | 9 |

In 81012, DIVERGENCES (26) is one more than CORRECTIONS (25): one miss came after the plan's correction was used, so
it escalated at once without a second correction (ACCEPTED 9 + ESCALATIONS 17 = 26). That is the pre-registered
policy. See the finding below on the run totals, which are not all inside the laps.

**L3 completion time: no time improvement, not slower.** Median paired lap-ticks ratio per leg, 6 pairs each:

| seed | leg | B/A | D/C | C/A (noise) |
|---|---|---|---|---|
| 81011 | slalom | 0.996 | 1.000 | 0.996 |
| 81011 | ridges | 0.993 | **1.033** | 0.993 |
| 81011 | bumps | 1.000 | 0.995 | 1.003 |
| 81011 | steps | 0.975 | 1.002 | 0.992 |
| 81012 | slalom | 0.997 | 0.997 | 0.999 |
| 81012 | ridges | 0.996 | 1.006 | 0.978 |
| 81012 | bumps | 0.998 | 1.000 | 1.000 |
| 81012 | steps | 0.980 | 0.988 | 1.003 |

No leg is fast in any pair. One leg is slow beyond noise: D/C ridges in 81011 (1.033 against noise 0.007). It is one
leg in one replication of one seed, and it does not repeat in 81012 (1.006), so "slower" (≥ 2 slow legs) is not met.

**L4 replans and recovery: drift replans lower in both replications and both seeds.** Paired on − off per lap,
n = 24, bootstrap 95% CI:

| seed | pair | physics plans | drift replans | cancels + rewinds |
|---|---|---|---|---|
| 81011 | B/A | −0.958 (−2.125..+0.208), no difference | **−0.458 (−0.792..−0.167), lower** | 0 (0..0) |
| 81011 | D/C | −0.667 (−1.833..+0.583), no difference | **−0.542 (−0.792..−0.292), lower** | 0 (0..0) |
| 81012 | B/A | −0.458 (−1.833..+0.958), no difference | **−0.458 (−0.792..−0.167), lower** | 0 (0..0) |
| 81012 | D/C | −0.125 (−1.167..+0.917), no difference | **−0.458 (−0.750..−0.167), lower** | 0 (0..0) |

Physics plans are not higher anywhere. Strictly more reached: no (24/24 in every mode).

**L5 overhead: inconclusive.** Client tick time per lap, paired on − off, both seeds pooled, n = 96:

| statistic | median diff (95% CI) | median off | median on | result |
|---|---|---|---|---|
| p95 | +0.491 ms (+0.061..+1.170) | 18.96 ms | 18.81 ms | CI upper > 0.5, lower < 0.5 |
| p99 | +0.272 ms (−1.677..+1.926) | 88.80 ms | 91.95 ms | CI upper > 0.5, lower < 0.5 |
| p50 (diagnostic) | −0.003 ms (−0.035..+0.027) | 5.57 ms | 5.61 ms | |
| max (diagnostic) | +2.718 ms (−3.116..+6.054) | 134.49 ms | 129.96 ms | |
| alloc KB (diagnostic) | +1277.5 (−12972.5..+13728.0) | 1489241.5 | 1479118.5 | |

Neither FAIL (no CI lower bound > 0.5 ms) nor acceptable (both CI upper bounds > 0.5 ms). The client tick includes
rendering and the integrated server, so its spread (p99 CI about 3.6 ms wide) is far larger than the executor's own
cost (Tier S H7: p95 +0.6 µs). 96 pairs cannot resolve a 0.5 ms bound on this client.

**L6 cancel latency: not measured live**, as pre-registered (the course cancels only at lap end).

**Tier L verdict: IMPROVEMENT** (drift replans lower in both replications and both seeds). L1 and L2 hold in both
seeds. L3 is not slower and physics plans are not higher in both replications and both seeds. L5 is inconclusive,
which is not a Tier L FAIL but keeps the gate from PASS.

**Finding outside the laps: the lap-end cancel releases the executor's keys without acknowledging it (diagnostic,
not a pre-registered measure).**

L2 is judged per B/D lap, as pre-registered, and holds. The run totals are not all in the laps:

| counter | seed 81011 total | in the laps | seed 81012 total | in the laps |
|---|---|---|---|---|
| RELEASED_EARLY | 43 | 0 | 41 | 0 |
| HANDOVERS | 43 | 0 | 41 | 0 |
| COMPLETIONS | 155 | 112 | 155 | 114 |

Every other counter's total equals the sum of its laps. In 81011, 43 B/D laps had one CANCEL_REFUSAL and no
CANCEL_STOP, and 5 had two or three refusals and a stop; in 81012, 41 had one refusal and no stop, and 7 had two refusals and a stop. The extra counts are exactly those laps.

What happens at the end of a B/D lap that ends while the player still moves (all in one client tick, then the next):
1. The harness calls `PathingBehavior.cancelEverything()` (`LegatoLiveTest.java:542`). The executor refuses the
   segment cancel because the player is not yet stopped on the ground (CANCEL_REFUSALS, counted in the lap).
2. `PathingBehavior.cancelEverything()` then calls `PathingControlManager.cancelEverything()` regardless
   (`PathingBehavior.java:536`), which calls `onLostControl()` on every process. `BastionProcess` (line 4256),
   `GetToBlockProcess` (194), `PveProcess` (466) and `PvpProcess` (972) call `clearAllKeys()` there
   unconditionally. They are not the lease owner, but the gate lets releases through by design, so the keys the
   driver wrote earlier in that tick go up.
3. The harness logs the lap row and turns the flag off (`mode(0)`, line 584). At the end of the tick the executor
   sees its owned keys up while it still holds the lease and counts RELEASED_EARLY. The next tick it hands the lease
   back (HANDOVERS) and settles COMPLETED_VERIFIED (COMPLETIONS). These land after the lap row, so in no lap.

Effect: the keys end up, no key stays down (0 stuck-key lines, "keys held right after cancel" never logged), no
conflicting press lands, and no lease is left held. Legacy behaves the same way: there the same calls release the
keys at once. But the executor's cancel protocol (acknowledge once stopped on the ground) is bypassed: a B6 writer
releases the executor's keys without telling it. So, on the live cancel path, the executor does not have exclusive
ownership of its keys. Under the task's rule ("if exclusive input ownership cannot be established for that path, do
not enable the new executor") this alone keeps `unifiedExecutor` off, whatever the gate.

Had RELEASED_EARLY been judged on run totals rather than per lap, L2 would fail in both seeds. The
gate class is FAIL either way (H2). The criterion was not changed.

Follow-up (not done in Phase 06): make `PathingBehavior.cancelEverything()` tell the executor that the processes are
cancelled before `onLostControl()` runs, and route the `onLostControl()` `clearAllKeys()` calls through the arbiter
(part of B6).

#### Slip diagnostic (exploratory, after the gate; not gate evidence)

Run after the gate class was fixed, to find out why unified loses slip arrivals. It changes no criterion, and its
result does not change the class. Production uses the same node budget as the harness (`PhysicsTravel.NODE_BUDGET`
= 1500).

Command (PowerShell): `$env:OSTINATO_SLIP_DIAG='1'; $env:OSTINATO_WRITE_DOCS='1'; .\gradlew.bat test -Postinato_bench
--tests "*UnifiedSlipDiagnostic" -x nativeHost`. Raw: `docs/ostinato2/raw/phase06/slip-diagnostic.txt`.

It re-runs the gated slip family (seed 6061, n = 300) on the same `UnifiedHarness`, then, for each trial that only
one arm reached, re-plans from the failing arm's state at its empty plan, and from the other arm's state at the same
tick, with node budgets 1500, 3000, 6000 and 12000. It then runs the whole family again at each budget.

**At budget 1500 it reproduces the gated batch exactly:** 247 against 239 arrived, the same 9 legacy-only trials and
the same unified-only trial (113).

Per trial:
- In 8 of the 9 legacy-only trials, unified ends at tick 3 after 2 divergences, 1 accepted correction and 1
  escalation; the replan after the escalation is empty. In trial 39 it ends at tick 7 (6 divergences, 3 corrections).
  Legacy, in the same trials, arrives in 48–69 ticks.
- From unified's failing state, budget 1500 finds no plan in all 9; 3000 and above find one (34–48 steps).
- Planned from legacy's state at the same tick, budget 1500 also finds no plan in 7 of the 9 (it does in 39 and 138).
  The two states differ mainly in the speed across the course: |vx| ≈ 0.022–0.024 for unified against ≈ 0.010 for
  legacy at tick 3. So keeping the plan through the first slip ticks leaves unified with more sideways speed when it
  finally replans, and that state is harder for the planner. Legacy did not ask for a plan from a state the planner
  cannot solve; the diagnostic does not show which later states it planned from.
- Trial 113 (legacy only fails, at tick 11): both arms are in the same state at that tick; 1500 finds no plan, 3000
  finds 38 steps.

Whole slip family at each budget (legacy, unified):

| node budget | arrived | EMPTY_PLAN | legacy only | unified only | total plan ms |
|---|---|---|---|---|---|
| 1500 (gated) | 247, 239 | 53, 61 | 9 | 1 | 15611, 13198 |
| 3000 | 293, 291 | 7, 9 | 2 | 0 | 23600, 19265 |
| 6000 | 300, 300 | 0, 0 | 0 | 0 | 24126, 20583 |
| 12000 | 300, 300 | 0, 0 | 0 | 0 | 24084, 20495 |

Reading (exploratory): the loss is a planner node-budget effect that the correction exposes, not a fault in the
ownership or the correction bookkeeping. At budget 6000 or more neither arm loses a slip trial, and unified spends
less total planning time than legacy at every budget. This supports, as the next pre-registered experiment, a replan
with a larger budget when the replan after an escalation comes back empty. It is not evidence for the Phase 06 gate,
and the budget was not changed.

#### Phase 06 classification: FAIL

Under the pre-registered gate rule:
- **FAIL by a reliability failure (H2, slip):** 239 against 247 arrived, allowed loss 3. That alone fixes the class.
- Every other FAIL condition is clear: H3 and L2 hold (no safety violation in the laps), L1 holds, H1, H4 and H6
  hold, there is no regression (H5, L3, L4), and no overhead FAIL (H7 acceptable, L5 inconclusive).
- Without H2 the class would be PARTIAL, not PASS: L5 is inconclusive, and the gate needs H7 and L5 acceptable.
- Separately from the gate, exclusive input ownership is not established on the live cancel path (finding above),
  which on the task's own rule keeps the executor off.

`unifiedExecutor` stays default **false**. No criterion was changed, and neither the batch nor the live A/B was
re-run after its result was seen.

Full suite on the results commit: 1595 tests, 1 failed (the pre-existing B5 `SecondoTest`), 23 skipped.

## Phase 06A: executor safety and reliability recovery (2026-11-09, branch `ostinato2/claude-review`, local only)

Phase 06 stays **FAIL** as recorded above (H2 in the slip family: unified 239/300 against legacy 247/300, 3 allowed).
Nothing in this section reclassifies it. Phase 06A works on the two blockers Phase 06 left, behind the same
`unifiedExecutor` flag (default **false**), and is judged on fresh seeds against the criteria below.

**S1, input ownership.**
- The blocker:
  - In the Phase 06 live A/B, 84 RELEASED_EARLY events (43 in seed 81011, 41 in 81012) fell between laps, outside
    every lap row.
  - Cause: the lap-end `PathingBehavior.cancelEverything()` was refused (not safe to cancel). Then
    `PathingControlManager.cancelEverything()` ran `onLostControl()`, whose `clearAllKeys()` lifted the leased keys
    without telling the executor.
- The fix (`0c354b804`):
  - On the refused branch, `cancelEverything()` now calls `UnifiedExecutor.processesCancelled()` before the processes
    are cancelled.
  - The driver releases its frame, confirms the release with the arbiter, and ends its lease as CANCELLED_CONFIRMED,
    counted as PROCESS_CANCELS. If the arbiter cannot verify the release, the lease ends as UNKNOWN instead.
  - The safe branch was already in the right order: `secretInternalSegmentCancel()` releases the leases before it
    clears the keys.
  - A refused segment may keep running. If it does, its next driver tick takes a fresh lease, so the ownership it
    holds is notified, not stale.
- Unit tests (`228d655f0`, `UnifiedExecutorTest`, 32 tests, all passing):
  - keys cleared behind a held lease are counted as RELEASED_EARLY;
  - a process cancel mid-move gives up the lease before the keys go up;
  - a process cancel between laps leaves nothing behind for the next ticks;
  - a segment that runs on takes a fresh lease and stops later;
  - a process cancel on a partly preempted lease leaves the preemptor its resource.
- Not changed:
  - emergency preemption (still immediate; its existing tests are unchanged);
  - the other direct key writers (no migration);
  - the other direct `onLostControl` callers.

**S2, slip reliability.**
- The 9 slip trials that legacy reached and unified did not all ended EMPTY_PLAN: the replan that followed an escalated
  correction exhausted the 1500-node budget.
- The post-hoc diagnostic (`raw/phase06/slip-diagnostic.txt`, Phase 06 seeds, not gate evidence) reran the whole slip
  family at 3000 nodes:
  - legacy reached 293 of 300 and unified 291;
  - every one of the 9 states found a plan.

**S2 recovery: A is selected, B is not attempted.**
- The options:
  - **A, bounded retry.** When the replan a correction escalated to returns no path, search once more with a 3000-node
    budget.
  - **B, surface-aware correction suppression.** Skip the correction on slippery ground.
- Why A:
  - **Correctness.** The diagnostic shows the larger budget finds a plan from every failing state. B has no direct
    evidence, and it does not address the budget mechanism (52 of the slip empty plans are shared by both arms).
  - **Complexity.** A adds one constant, a lazily built second planner and two counters. B needs a surface signal the
    model does not have (the model assumes slipperiness 0.6 everywhere).
  - **Cost.** A costs time only on an empty correction-triggered replan, and then at most one search of ≤ 3000 nodes.
    B would remove the correction's benefit on ice.
- B is not implemented in Phase 06A. If A fails, the slip blocker stays open.

**How A is implemented (decided before the batch).**
- New constant: `CorrectionPolicy.RETRY_NODES = 3000`.
- The retry, in `PhysicsTravel` and its mirror in `UnifiedHarness`. It applies only when all of these hold:
  - the executor is on;
  - the correction triggered the replan (`drifted` after the divergence check, i.e. a MISSED or ESCALATED verdict);
  - the 1500-node search returned no plan.

  Then the driver searches once more with a 3000-node planner.
- New counters:
  - CORRECTION_RETRIES counts each retry;
  - CORRECTION_RETRY_PLANS counts each retry that found a plan.
- A retry does not count as a plan or a drift replan, so H4's ESCALATIONS = drift replans still holds.
- If the retry is also empty, the existing failure path runs unchanged: cooldown, `fail(EMPTY_PLAN)`, drop.
- With the flag off, and in legacy, nothing changes and no retry happens.
- The harness records each run's total and largest retry time (`retryNs`, `retryNsMax`).

### Pre-registered criteria (written 2026-11-09, before any Phase 06A data was collected)

**Already seen, not gate evidence.**
- The Phase 06 batch and live A/B, the slip diagnostic, and the S1 unit tests above.
- The 3000-node budget was chosen from the slip diagnostic, on Phase 06 seeds.
- The Phase 06A batch and live runs use seeds that were never run.

**Tiers and statistics.** As in Phase 06:
- Tier S is headless.
- Tier L is live.
- No Tier-L claim is made from headless results.
- Every bootstrap uses 10,000 resamples, `random.Random(20261108)`, and the percentile 95% interval.

#### Tier S: headless batch (`UnifiedBatchBench`, judged by `scripts/unified-batch.py`)

**Run.**
- Command: `-Postinato_bench --tests "*UnifiedBatchBench"`, with the environment
  `OSTINATO_BATCH_SEED=9091 OSTINATO_BATCH_WARMUP_SEED=9191 OSTINATO_BATCH_PHASE=phase06a OSTINATO_WRITE_DOCS=1`.
- The overrides change only the seeds and the output directory. Their defaults stay the Phase 06 values.
- Seeds:
  - Family `k` draws from `new Random(9091 + k)`.
  - The warm-up draws from 9191 and is thrown away.
- Trials, families, arm order and per-run records are as in Phase 06: N = 300 per family, six families, 1800 trials and
  3600 runs. In addition, each run records `retryNs`, `retryNsMax` and the two new counters.
- Raw output: `docs/ostinato2/raw/phase06a/`.

**H1–H7.** As in Phase 06, unchanged. H4's rule that every legacy trial has all executor counters at 0 covers the two
new counters.

**R1. The retry is bounded and counted.** Every unified run must satisfy:
- CORRECTION_RETRIES ≤ ESCALATIONS;
- CORRECTION_RETRY_PLANS ≤ CORRECTION_RETRIES;
- CORRECTION_RETRIES − CORRECTION_RETRY_PLANS ≤ 1, and when it is 1 the run ends EMPTY_PLAN (a failed retry ends the
  run).

**R2. Search and tick-time budget.**
- **Search.**
  - At most one retry follows a replan, so a replan tick searches at most 1500 + 3000 = 4500 nodes.
  - This holds by construction, and a unit test checks the retry budget and the one-retry limit.
- **Time** (headless, Java planner), over unified runs with at least one retry. Holds when:
  - the per-run largest retry time (`retryNsMax`) has p95 ≤ 150 ms and max ≤ 250 ms;
  - the per-run largest tick time (`tickMaxNs`) has p95 ≤ 250 ms and max ≤ 400 ms.
- If no run retries, R2 time is "not exercised". That is not a failure on its own, but S2 cannot then be resolved,
  because the slip mechanism check below requires a retry plan.
- Diagnostic only:
  - total retry time per family, and its share of plan time;
  - the calibration these bounds came from (Phase 06 data): a failed 1500-node search takes about 50 ms; in slip
    EMPTY_PLAN runs, tickMax had a median of 49 ms and a max of 95 ms.

**Slip mechanism.**
- Holds when CORRECTION_RETRY_PLANS > 0 over the unified slip runs.
- Diagnostic: how many of those runs arrived.

**S2 is resolved** when all of the following hold:
- H2 in every family;
- R1 and R2;
- the slip mechanism check.

**Tier S fails** if any of H1–H6 or R1 fails, or H7 is FAIL.

#### Tier L: live A/B (`LegatoLiveTest`, judged by `scripts/unified-ab.py`)

**Run.**
- As in Phase 06:
  - properties `-Dostinato.legatotest.arms=unified -Dostinato.legatotest.stack=physics`;
  - 6 reps on the simedge course, so 96 laps per seed;
  - A and C with the flag off, B and D with it on, and `predictionTolerance` off in every arm.
- Seeds: 86021, then 86022.
- Required live scenarios:
  - the simedge legs (slalom, ridges, bumps, steps) under both arms;
  - the lap-end cancel after every lap. When the segment is not safe to cancel, that cancel is refused while the player
    is still moving. That is the path S1 fixes.

**L1–L6 and the Tier L verdict.** As in Phase 06, unchanged.

**L2′. Ownership across lap boundaries, in each seed.**
- Data: the log's run totals (`LEGATOTEST unified totals`). Unlike the lap rows, they include the ticks between laps.
- Holds when all of the following are true:
  - the run totals of RELEASED_EARLY, OVERWRITTEN, STALE_REJECTS and UNKNOWNS are 0;
  - there is no stuck-key log line ("keys still held" or "keys held right after");
  - PROCESS_CANCELS > 0 in the run totals, meaning the refused-cancel path was exercised. If it is 0 and everything
    else holds, L2′ is "not exercised" rather than holding.
- Diagnostic only, per counter: run total − lap sum (events between laps).
  - A segment that keeps running after a refused cancel takes a fresh lease between laps. So ACQUIRES, COMPLETIONS or
    HANDOVERS may move there.
  - That is notified ownership, not a violation.
  - In Phase 06 the gaps held COMPLETIONS +43/+41, HANDOVERS +43/+41 and RELEASED_EARLY +43/+41.

**Live diagnostics.** CORRECTION_RETRIES and CORRECTION_RETRY_PLANS over the B/D laps.

**S1 is resolved** when all of the following hold:
- the `UnifiedExecutorTest` ownership tests pass;
- H3 holds;
- L2 holds in both seeds;
- L2′ holds, and is exercised, in both seeds.

#### Phase 06A gate

- **PASS:** S1 and S2 are both resolved, and nothing regresses. All of the following:
  - Tier S does not fail;
  - L1 holds in both seeds;
  - there is no H5 regression, no L3 slower and no L4 more plans (as defined in Phase 06);
  - neither H7 nor L5 is FAIL;
  - the full suite shows no new failure beyond the pre-existing B5 `SecondoTest`.
- **FAIL:** any of the following:
  - a safety violation: H3, L2 or L2′ fails (a non-zero total or a stuck key);
  - a reliability failure: H2 or L1;
  - H1, H4, H6 or R1 fails;
  - a regression, as defined in Phase 06;
  - overhead FAIL (H7 or L5);
  - neither blocker is resolved.
- **PARTIAL:** anything else. For example:
  - one blocker is resolved and the other is not;
  - R2 is missed;
  - the live evidence is unavailable, or L2′ is not exercised.

**Promotion is a separate question.**
- The Phase 06 PASS rule (Tier S does not fail, H3 and L2 hold, H7 and L5 acceptable, Tier L IMPROVEMENT) is applied
  to the Phase 06A data and reported on its own line.
- Even if that rule is met, `unifiedExecutor` stays default false. Enabling it needs the owner's separate
  authorization.

**Stop conditions.**
- The focused tests (`UnifiedExecutorTest`, `CorrectionPolicyTest`, `UnifiedHeadlessTest`) must pass before the batch.
- The batch runs once:
  - A run that fails before writing its CSV produced no result, and may be repeated once with the same seeds.
  - A written CSV is final: no re-run and no new seeds.
- Live:
  - 86021 runs first.
  - A stuck key or a death in 86021 is a result (FAIL), and 86022 is not run.
  - A client crash is recorded with its log, and the same seed runs once more.
  - A second crash, or the client failing to start twice, makes the live evidence unavailable (PARTIAL at best).
- No criterion changes after a result is seen.
- If A fails, B is not attempted in Phase 06A. The slip blocker is recorded as open.

### Phase 06A results

Judged against the criteria above, which were committed (`5dc9e6b4f`) before the S2 change was written and before the
batch or the live A/B ran. No criterion was changed after a result was seen. Phase 06 stays **FAIL**.

**Focused tests, before the batch** (the stop condition): `CorrectionPolicyTest` 17, `CorrectionRetryTest` 4,
`UnifiedHeadlessTest` 12 and `UnifiedExecutorTest` 32; all 65 pass, 0 skipped.

#### Tier S: headless batch (VERIFIED, Tier S)

Command: `gradlew test -Postinato_bench --tests "*UnifiedBatchBench"` with `OSTINATO_BATCH_SEED=9091
OSTINATO_BATCH_WARMUP_SEED=9191 OSTINATO_BATCH_PHASE=phase06a OSTINATO_WRITE_DOCS=1`, at `48fec9906`.
Analysis: `python scripts/unified-batch.py docs/ostinato2/raw/phase06a/headless-batch.csv`.
Raw (`docs/ostinato2/raw/phase06a/`):
- `headless-batch.csv`: one row per run, 3600 rows, every counter, plus `retryNs` and `retryNsMax`;
- `headless-batch-run.txt`: seeds, n, wall time, JVM, OS;
- `headless-analysis.txt`: the script's full output.

The run ran once: seed 9091, warm-up seed 9191 (thrown away), n = 300 per family, so 1800 trials and 3600 runs. Wall
time 96.7 s, Java 25.0.4.1, Windows 11, 8 CPUs. Unified ran first in 900 trials and second in 900. Neither seed had
been run before.

**H1 clean: holds.** Over 300 clean trials, legacy and unified are equal on every compared field (0 mismatches). Both
arms end 255 ARRIVED and 45 EMPTY_PLAN.

**H2 reliability: holds in every family.** Allowed loss is ceil(1% of 300) = 3.

| family | legacy arrived | unified arrived | other ends (legacy / unified) | holds |
|---|---|---|---|---|
| clean | 255 | 255 | 45 / 45 EMPTY_PLAN | yes |
| kick | 300 | 300 | — | yes |
| slip | 249 | **252** | 51 / 48 EMPTY_PLAN | yes (unified +3) |
| conflict | 300 | 300 | — | yes |
| cancel | 300 | 49 + 251 CANCEL_STOP | — | yes (0 unintended ends) |
| edge | 228 | **256** | 72 / 44 EMPTY_PLAN | yes (unified +28) |

There were no REFUSED or TIMEOUT ends in either arm. The edge gain comes from the retry (R1 below: 28 retry plans in
edge); edge was not a target of Phase 06A, and its gain is reported, not claimed.

**H3 safety: holds.**
- Over every unified trial: RELEASED_EARLY, OVERWRITTEN and STALE_REJECTS are 0, and no conflicting press landed.
- Every non-timeout end has no key down, no lease held, and the final state that matches its outcome.
- Every CANCEL_STOP ended on the ground. There were no TIMEOUT ends.
- Diagnostic: the gate refused 2242 presses (CONFLICTS), and ERASED is 1454. In the legacy arm, 2320 conflicting
  presses landed.

**H4 correction bounded and counted: holds** (0 violations). Legacy executor counters, the two new ones included, are
all 0.

| family | divergences | corrections | accepted | escalations (= unified drift replans) | legacy drift replans |
|---|---|---|---|---|---|
| clean | 0 | 0 | 0 | 0 | 0 |
| kick | 286 | 286 | 36 | 250 | 286 |
| slip | 2129 | 1370 | 789 | 1340 | 1961 |
| conflict | 0 | 0 | 0 | 0 | 2314 |
| cancel | 0 | 0 | 0 | 0 | 0 |
| edge | 300 | 300 | 14 | 286 | 300 |

A retry is not counted as a drift replan, so escalations still equal unified drift replans in every family.

**H5 plans and ticks: no regression.** Paired unified − legacy, bootstrap 95% CI:

| family | plans mean diff (CI) | fewer replans | arrived pairs | ticks mean ratio (CI) | faster / equal / slower | regression |
|---|---|---|---|---|---|---|
| kick | −0.120 (−0.157..−0.083) | yes | 300 | 1.0002 (1.0000..1.0005) | 0 / 298 / 2 | no |
| slip | −2.023 (−2.480..−1.567) | yes | 249 | 1.0004 (0.9993..1.0015) | 16 / 217 / 16 | no |
| conflict | −7.713 (−8.257..−7.180) | yes | 300 | **0.8645** (0.8534..0.8752) | 296 / 4 / 0 | no |
| edge | −0.047 (−0.073..−0.023) | yes | 228 | 1.0009 (1.0004..1.0015) | 0 / 219 / 9 | no |

Fewer replans in all four disturbed families (Tier S). Faster in conflict only (Tier S).

**H6 cancel latency: holds.**
- 262 cancel trials had the request before arrival. Unified ended 251 CANCEL_STOP and 11 ARRIVED.
- Unified latency: median 1 tick, max 12. Legacy, which waits for the run to end: median 35.
- No unified trial lacked a stop or an arrival after the request. Unified was never slower than legacy.
- Among the 222 pairs where legacy latency is ≥ 5, unified was strictly lower in 222 (100%).

**H7 overhead: acceptable.** Per-run driver tick time, paired unified − legacy, all families pooled:

| statistic | median diff (95% CI) | legacy median | unified median |
|---|---|---|---|
| p95 | +0.65 µs (+0.60..+0.70) | 465.10 µs | 26.25 µs |
| p99 | −23.65 µs (−51.10..+2.85) | 14576.50 µs | 14242.30 µs |
| p50 (diagnostic) | +0.00 µs (+0.00..+0.00) | 0.30 µs | 0.30 µs |

As in Phase 06, p99 is the run's slowest tick (every run is under 100 ticks), which is a planning tick. Clean only,
where both arms do the same work: p50 diff +0.100 µs (CI +0.100..+0.100).

Diagnostic medians per run, legacy → unified:

| family | plan ms | alloc KB |
|---|---|---|
| clean | 14.459 → 13.805 | 41789.8 → 41791.1 |
| kick | 11.216 → 10.842 | 34277.6 → 32679.6 |
| slip | 34.908 → 28.960 | 100322.8 → 86145.1 |
| conflict | 22.657 → 2.929 | 69181.1 → 7789.7 |
| cancel | 23.494 → 23.484 | 72145.5 → 72131.0 |
| edge | 31.010 → 30.656 | 88065.9 → 87181.8 |

**R1 the retry is bounded and counted: holds** (0 violations over every unified run).

| family | escalations | retries | retry plans | runs that retried | failed retries that ended EMPTY_PLAN |
|---|---|---|---|---|---|
| clean | 0 | 0 | 0 | 0 | — |
| kick | 250 | 0 | 0 | 0 | — |
| slip | 1340 | 11 | 11 | 11 | — (none failed) |
| conflict | 0 | 0 | 0 | 0 | — |
| cancel | 0 | 0 | 0 | 0 | — |
| edge | 286 | 72 | 28 | 72 | 44 / 44 |

No run retried more than once. Legacy never retried.

**R2 search and tick time: holds.** Over the 83 unified runs with a retry (nearest-rank p95):

| measure | p95 | bound | max | bound |
|---|---|---|---|---|
| largest retry per run (`retryNsMax`) | 87.63 ms | ≤ 150 | 104.00 ms | ≤ 250 |
| largest tick per run (`tickMaxNs`) | 123.67 ms | ≤ 250 | 147.77 ms | ≤ 400 |

The search bound (≤ 1500 + 3000 nodes on a replan tick) holds by construction and is checked in `CorrectionRetryTest`.
Diagnostic: retry time is 605 ms of 13293 ms unified plan time in slip (4.6%) and 4766 ms of 14232 ms in edge (33.5%).
In edge most retries fail (44 of 72), and a failed 3000-node search is the most expensive case.

**Slip mechanism: holds.** 11 retry plans over the unified slip runs, in 11 runs; all 11 arrived.

**Tier S: no failure.** H1, H2, H3, H4, H6, R1 and R2 hold, H5 has no regression, H7 is acceptable, and the slip
mechanism holds. Fewer replans in kick, slip, conflict and edge, and faster in conflict (Tier S only).

**S2 (slip reliability): resolved at Tier S.** H2 holds in every family, R1 and R2 hold, and the slip mechanism holds.
Slip went from unified 239 vs legacy 247 (Phase 06, seed 6061) to 252 vs 249 (Phase 06A, seed 9091).

#### Tier L: live A/B on the 26.3 client (VERIFIED, Tier L)

Command, per seed (PowerShell, from the repository root; one client at a time, no other JVM running), at `e65850c7e`
(the same code as `48fec9906`):

```
$env:JAVA_TOOL_OPTIONS='-Dostinato.noMouseGrab=true -Dostinato.windowPos=2600,40 -Dostinato.testHarness=true -Dostinato.tickClock=true -Dostinato.legatotest=true -Dostinato.legatotest.exit=true -Dostinato.legatotest.arms=unified -Dostinato.legatotest.stack=physics -Dostinato.legatotest.reps=6 -Dostinato.seed=<seed>'
.\gradlew.bat --offline --no-daemon "-Dorg.gradle.jvmargs=-Xmx1536M" :fabric:runClient -PclientXmx=1536M *> ab-<seed>.log
```

Analysis: `python scripts/unified-ab.py docs/ostinato2/raw/phase06a/ab-86021-lines.txt docs/ostinato2/raw/phase06a/ab-86022-lines.txt`.
Raw (`docs/ostinato2/raw/phase06a/`):
- `ab-<seed>-lines.txt`: every `LEGATOTEST` line of the client log, one row per lap with the per-lap executor
  counters, and the run totals;
- `ab-<seed>-result.txt`: the harness's own result file;
- `ab-<seed>-ticks.csv.gz`: the per-tick `unified-ticks.csv`;
- `ab-analysis.txt`: the script's full output for both seeds.

86021 ran first, then 86022. Each ran once, with no crash. Both seeds ran to the end: 96 of 96 laps per seed, on the
`physics` stack, the flag on in every B/D lap and off in every A/C lap, `predictionTolerance` off in every lap.
Neither seed had been run before.

**L1 reliability: holds in both seeds.**

| seed | A reached | B reached | C reached | D reached | stuck-key lines | deaths | new B/D faults |
|---|---|---|---|---|---|---|---|
| 86021 | 24/24 | 24/24 | 24/24 | 24/24 | 0 | 0 | none |
| 86022 | 24/24 | 24/24 | 24/24 | 24/24 | 0 | 0 | none |

Physics plans / drift replans over the 24 laps of each mode (diagnostic):

| seed | A | B | C | D |
|---|---|---|---|---|
| 86021 | 777 / 14 | 774 / 11 | 779 / 17 | 779 / 11 |
| 86022 | 773 / 11 | 783 / 10 | 780 / 18 | 780 / 10 |

**L2 ownership safety: holds in both seeds** (judged per B/D lap, as pre-registered).
- In every B/D lap, RELEASED_EARLY, OVERWRITTEN and STALE_REJECTS are 0.
- No executor counter moved in any A/C lap.
- OWNED_TICKS over the B/D laps: 8709 (86021) and 8798 (86022), of which INTACT_TICKS 8547 and 8644.
- In every B/D lap, CORRECTIONS ≤ physics plans and ESCALATIONS = drift replans.

Executor counters summed over the laps of each mode:

| seed | mode | ACQUIRES | COMPLETIONS | FAILURES | CANCEL_STOPS | DIVERGENCES | CORRECTIONS | ACCEPTED | ESCALATIONS | PROCESS_CANCELS |
|---|---|---|---|---|---|---|---|---|---|---|
| 86021 | B | 137 | 52 | 61 | 4 | 20 | 20 | 9 | 11 | 20 |
| 86021 | D | 139 | 55 | 60 | 3 | 19 | 19 | 8 | 11 | 21 |
| 86022 | B | 138 | 57 | 57 | 3 | 12 | 11 | 2 | 10 | 21 |
| 86022 | D | 135 | 55 | 56 | 4 | 14 | 14 | 4 | 10 | 20 |

In 86022 B, DIVERGENCES (12) is one more than CORRECTIONS (11): one miss came after the plan's correction was used,
so it escalated at once (ACCEPTED 2 + ESCALATIONS 10 = 12). That is the pre-registered policy, as in Phase 06.

**L2′ ownership across lap boundaries: holds, and is exercised, in both seeds.**

Run total / sum over the laps, from the `LEGATOTEST unified totals` line and the lap rows:

| counter | 81011 (Phase 06) | 81012 (Phase 06) | 86021 | 86022 |
|---|---|---|---|---|
| RELEASED_EARLY | 43 / 0 | 41 / 0 | **0 / 0** | **0 / 0** |
| OVERWRITTEN | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| STALE_REJECTS | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| UNKNOWNS | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| HANDOVERS | 43 / 0 | 41 / 0 | 0 / 0 | 0 / 0 |
| COMPLETIONS | 155 / 112 | 155 / 114 | 107 / 107 | 112 / 112 |
| PROCESS_CANCELS | (no counter) | (no counter) | 41 / 41 | 41 / 41 |

- The four safety totals are 0 in both seeds, there is no stuck-key line, and "keys held right after cancel" was never
  logged. PROCESS_CANCELS is 41 in each seed, so the refused-cancel path was exercised.
- Diagnostic: run total − lap sum is empty in both seeds. No executor counter moved between laps, against
  COMPLETIONS, HANDOVERS and RELEASED_EARLY +43/+41 in Phase 06.

Lap-end diagnostic (not a pre-registered measure; counted from the per-lap `ux={…}` counters in
`ab-<seed>-lines.txt`). B/D laps by (CANCEL_REFUSALS, CANCEL_STOPS, PROCESS_CANCELS) in the lap:

| pattern | 81011 (Phase 06) | 81012 (Phase 06) | 86021 | 86022 |
|---|---|---|---|---|
| 1 refusal, no stop, no process cancel | 43 | 41 | 0 | 0 |
| 1 refusal, no stop, 1 process cancel | — | — | 41 | 41 |
| 2 refusals, 1 stop | 4 | 7 | 5 | 4 |
| 3 refusals, 1 stop | 1 | 0 | 2 | 3 |

In Phase 06, the laps with one refused cancel and no stop were the laps whose leases ended after the lap row, by an
early release. In Phase 06A each such lap records one PROCESS_CANCEL inside the lap: the executor lifts its keys and
ends its lease before `onLostControl()` runs. The other laps stopped inside the lap, as in Phase 06.

**L3 completion time: no time improvement, not slower.** Median paired lap-ticks ratio per leg, 6 pairs each:

| seed | leg | B/A | D/C | C/A (noise) |
|---|---|---|---|---|
| 86021 | slalom | 0.994 | 1.001 | 0.997 |
| 86021 | ridges | 0.998 | 1.009 | 0.991 |
| 86021 | bumps | 1.002 | 1.000 | 1.000 |
| 86021 | steps | 1.005 | 0.977 | 1.037 |
| 86022 | slalom | 0.997 | 1.001 | 0.997 |
| 86022 | ridges | 1.009 | 0.995 | 1.000 |
| 86022 | bumps | 1.002 | 0.998 | 1.002 |
| 86022 | steps | 0.976 | 1.010 | 0.977 |

No leg in any B/A or D/C pair is ≤ 0.97 or ≥ 1.03.

**L4 replans and recovery: no difference in any pair.** Paired on − off per lap, n = 24, bootstrap 95% CI:

| seed | pair | physics plans | drift replans | cancels + rewinds |
|---|---|---|---|---|
| 86021 | B/A | −0.125 (−1.292..+1.042), no difference | −0.125 (−0.417..+0.167), no difference | 0 (0..0) |
| 86021 | D/C | +0.000 (−1.208..+1.292), no difference | −0.250 (−0.667..+0.208), no difference | 0 (0..0) |
| 86022 | B/A | +0.417 (−0.917..+1.750), no difference | −0.042 (−0.292..+0.208), no difference | 0 (0..0) |
| 86022 | D/C | +0.000 (−1.042..+1.042), no difference | −0.333 (−0.708..+0.042), no difference | 0 (0..0) |

Physics plans are not higher anywhere. Strictly more reached: no (24/24 in every mode). Drift replans are lower in
every point estimate, but no CI excludes 0, so the Phase 06 Tier L improvement (drift replans lower in both
replications and both seeds) did not reproduce on these seeds. The off arms had fewer drift replans than in Phase 06
(A/C 14/17 and 11/18, against 20/19 and 19/20).

**L5 overhead: inconclusive.** Client tick time per lap, paired on − off, both seeds pooled, n = 96:

| statistic | median diff (95% CI) | median off | median on | result |
|---|---|---|---|---|
| p95 | +0.270 ms (−0.203..+0.914) | 17.82 ms | 17.74 ms | CI upper > 0.5, lower < 0.5 |
| p99 | −0.476 ms (−1.993..+0.695) | 85.61 ms | 79.40 ms | CI upper > 0.5, lower < 0.5 |
| p50 (diagnostic) | +0.010 ms (−0.039..+0.051) | 5.21 ms | 5.23 ms | |
| max (diagnostic) | −0.095 ms (−4.190..+3.032) | 125.24 ms | 124.65 ms | |
| alloc KB (diagnostic) | +7790.0 (−5582.5..+20609.0) | 1470381.5 | 1474563.0 | |

Not FAIL (no CI lower bound > 0.5 ms), and not acceptable (both CI upper bounds > 0.5 ms). As in Phase 06, 96 pairs
of client ticks cannot resolve a 0.5 ms bound.

**L6 cancel latency: not measured live**, as pre-registered (the course cancels only at lap end).

**Live retry diagnostic: the retry never fired.** CORRECTION_RETRIES and CORRECTION_RETRY_PLANS are 0 over the B/D
laps of both seeds, and 0 in both run totals. No replan after a correction came back empty on this course, so the S2
change had no live occasion to act. Its effect is evidenced at Tier S only.

**Tier L verdict: no improvement, no regression.** L1, L2 and L2′ hold in both seeds. L3 is not slower and physics
plans are not higher in any pair. L5 is inconclusive, which is not a FAIL.

**S1 (input ownership): resolved** on the path the live harness exercises (the lap-end `cancelEverything()` with a
refused segment cancel):
- the five ownership tests in `UnifiedExecutorTest` pass (32 of 32);
- H3 holds;
- L2 holds in both seeds;
- L2′ holds and is exercised in both seeds.

Not covered by S1 and still open (B6): the direct key writers (279 raw matches in 33 files, unchanged), the four
`onLostControl()` `clearAllKeys()` calls, and the direct `onLostControl()` callers outside
`PathingBehavior.cancelEverything()` (`SwarmBehavior`, `HybridMovementEngine`, `BombardLiveTest`). None of them ran
with the flag on in this evidence.

#### Phase 06A classification: PASS

Under the pre-registered gate rule:
- **S1 resolved** (Tier L above) and **S2 resolved** (Tier S above).
- Every PASS condition holds:
  - Tier S does not fail;
  - L1 holds in both seeds;
  - there is no H5 regression, no L3 slower and no L4 more plans;
  - H7 is acceptable and L5 inconclusive, so neither is FAIL;
  - the full suite shows no new failure beyond the pre-existing B5 `SecondoTest`.
- No FAIL condition is met: H3, L2 and L2′ hold; H2 and L1 hold; H1, H4, H6 and R1 hold; there is no regression and
  no overhead FAIL.

Scope of the class:
- It covers the two Phase 06 blockers. Phase 06 stays **FAIL** as recorded above.
- S2 is evidenced at Tier S only: the retry never fired live.
- S1 is established on the path the live harness exercises, the refused branch of the lap-end
  `PathingBehavior.cancelEverything()`. The other key writers and `onLostControl()` callers stay open (B6).

**Promotion (the Phase 06 PASS rule, applied to the Phase 06A data): not met.** Tier S does not fail, H3 and L2 hold
and H7 is acceptable, but L5 is inconclusive rather than acceptable, and the Tier L verdict is no improvement.

`unifiedExecutor` stays default **false**. No criterion was changed, and neither the batch nor either live seed was
re-run after its result was seen.

Full suite on the results code (`deec15be1`): 1604 tests, 1 failed (the pre-existing B5 `SecondoTest`), 23 skipped.


## Phase 07: long-distance routing (2026-11-09, branch `ostinato2/claude-review`, local only)

The task: improve long-distance navigation per unit of compute and complexity. A hierarchical global layer is one
hypothesis, not a requirement. Phase start HEAD `db28a1cc3` (clean tree). The planner, the executor and every default
setting are left as they are; the existing hierarchy (`pathingHierarchical`) stays default **false**.

### Audit: how a long trip is planned today (VERIFIED, read from `db28a1cc3`)

```
Goal (#goto x y z / x z, a process's goal)
  │
  ▼
PathingBehavior.findPathInNewThread ─ budgets: first search 500/2000 ms; plan-ahead 4000/5000 ms;
  │                                    a start searched again for the same goal doubles, up to failureTimeoutMaxMS 16000
  │                                    (mayEscalate while below it); holdAtDrops when the goal's chunk is loaded
  ▼
createPathfinder ─ GoalBlock in an unloaded chunk → GoalXZ (simplifyUnloadedYCoord, default true)
  │              ─ Favoring(previous path, 0.5 backtrack), dead set (A* branch), farSegmentWeight 2.0 at ≥ 128 blocks
  │              ─ pathingHierarchical (default false) → HierarchicalPathFinder; otherwise AStarPathFinder
  ▼
AbstractNodeCostSearch.calculate ─ RouteReuse.cut → calculate0 (A*: stops at 50 unloaded-chunk border hits)
  │                               → postProcess → cutoffAtLoadedChunks (off) → staticCutoff 0.9 (min 30)
  │                               → hold at the first irreversible drop (timed-out searches) → SUCCESS_TO_GOAL / SEGMENT
  ▼
PathExecutor ─ walks the segment; checks movements p+1..p+4 each tick (costVerificationLookahead 5), cancels on COST_INF
  │
  └─ plan-ahead: when the movements after the current one cost < planningTickLookahead (150 ticks), the next segment is
     searched from this one's end while the bot walks on; NEXT_CALC_FAILED retries next tick; an exhausted plan-ahead
     that does not reach the goal bans the pocket (markDead) and replans
```

There is no global route layer in the production path. A long trip is a chain of segments, each one an A* search toward
the final goal that ends where the search budget or the loaded terrain runs out, joined by the plan-ahead.

**The existing hierarchy (default off, `pathingHierarchical`).**
- What it is: `HierarchicalPathFinder` over `SectionGraph` (16³ sections, edges priced on demand), with
  `BidirectionalRoute`, `CostToGoMap` and an optional `CoarseGraph` level. The abstract route becomes a corridor of
  sections (`nodeFilter`) for an inner A*.
- When it engages: only when the goal has a position (`IGoalRenderPos`) and is ≥ 64 blocks away. It falls back to plain
  A* on no abstract route or a corridor miss.

**Gaps in the existing hierarchy (VERIFIED by reading; each one measured below where marked).**
1. **Inert for typical long-distance goals.** `#goto x z` is a GoalXZ, and a GoalBlock in an unloaded chunk is
   simplified to one. GoalXZ has no position, so the hierarchy falls back ("goal has no position"). It engages only for
   goals inside loaded terrain (render distance, a few hundred blocks). Measured: every HORIZON-mode search.
2. **Budget signals are not passed on.** `timedOut`/`exhausted` of the inner A* are not copied to the wrapper. A
   timed-out first search is not escalated, a timed-out search is not held at drops, and `markDead` never fires.
3. **A corridor timeout wastes the budget.** When the corridor A* runs out of time short of the goal, its partial path
   is thrown away (unless the goal is in unknown terrain) and plain A* gets only what time is left. Measured: MAZE,
   where the fallback gets about 1 ms and returns 64-node segments.
4. **No dead set:** the hierarchical branch returns before `avoidDead`.
5. **Unbounded cache:** `SectionGraph.edges` has no size limit.
6. **No invalidation on world or dimension change:** `onWorldEvent` does not clear `SectionGraph.INSTANCE`.
7. **Slow cancel:** the abstract search does not check `cancelRequested`. Measured on the dev corpus: a median of 142 ms
   (BREAK_PLACE) and 230 ms (MAZE) from `cancel()` to return, max 280 ms, against ≤ 0.12 ms for A*.
8. **Bench weight (fixed in this phase):** PathBench's hpa arm searched the corridor at weight 1, while production passes
   2.0 beyond 128 blocks. The new `fw` token passes production's weight.
9. **A doc artifact:** "hierarchy ~4% longer than wa2" in the earlier long-horizon notes compares different query sets.
   Recomputed on the queries both solved, hpa1bi/wa2 = 0.9658.

### Audit answers (VERIFIED by reading, and by the runs below where marked)

1. **Is there existing hierarchical or segmented routing?**
   - Production is segmented: a long trip is a chain of A* segments joined by the plan-ahead.
   - A hierarchy exists, default off: `HierarchicalPathFinder`, `SectionGraph`, `CoarseGraph`, `BidirectionalRoute` and
     `CostToGoMap`.
2. **Are chunk-level abstractions used?**
   - Only in that default-off hierarchy: 16³ sections, plus the optional coarse level.
   - Production A* reads blocks, falling back to the lossy world cache in unloaded chunks.
3. **Are long searches a bottleneck?**
   - Not for travel. Planning runs off the client thread and is mostly hidden behind the plan-ahead. Production's
     planning wait is 0.25% of trip ticks over the dev corpus, and 1.80% in its worst cell (below).
   - They are a CPU cost: a known-terrain BREAK_PLACE trip of 1000 blocks plans for 4–17 s, and a CAVES trip for up to
     27 s.
4. **Does search, world access or cost evaluation dominate?** Cost evaluation dominates, and within it a single
   block-state id lookup (profile below).
5. **Would a new layer duplicate existing functionality?** Yes: the section graph, the coarse graph and the
   bidirectional route already exist.
6. **Which search assumptions would a hierarchy invalidate?**
   - Every search heads for the final goal, which the plan-ahead, route reuse and Favoring rely on.
   - The search space is unrestricted. The corridor filter gives this up, and with it the guarantee of a weighted-A*
     path.
   - GoalXZ goals beyond loaded terrain.
   - The dead set and the hold at drops, both driven by the inner search's timeout and exhaustion signals.
   - Cancel within a millisecond.

### Baseline: PathBench (VERIFIED, Tier S, `raw/phase07/pathbench/`)

60 fixed queries per arm: 5 terrains × 3 lengths × 4 queries, 3 repetitions, a 10 s budget. LONG medians, in ms:

| arm | FLAT | HILLS | CAVES | WATER | BREAK_PLACE | fails | all-length median / p95 ms | MB alloc | mean travel |
|---|---|---|---|---|---|---|---|---|---|
| `astar` (weight 1) | 8.4 | 2763.3 | 230.4 | 1156.7 | 10008.6 | 4/60 | 126.6 / 10007.9 | 4904 | 1202.1 |
| `wa:2` (production's far weight) | 3.6 | 17.8 | 91.9 | 4.9 | 4031.8 | 1/60 | 7.0 / 4031.8 | 997 | 1383.5 |
| hierarchy, weight 1 | 92.1 | 1790.4 | 265.5 | 757.8 | 5117.1 | 0/60 | 150.0 / 5012.8 | 3135 | 1385.5 |
| hierarchy, production weight (`fw`) | 87.3 | 129.8 | 158.9 | 225.2 | 2615.6 | 0/60 | 89.3 / 2615.6 | 798 | 1428.5 |
| same, warm section graph | 3.9 | 21.0 | 70.4 | 5.8 | 2499.8 | 0/60 | 6.7 / 2499.8 | 733 | 1428.5 |

- Command: `scripts`-style loop over `OSTINATO_BENCH_FINDER` = `astar`, `wa:2`, `hpa:0:1:1:2:1:bi` and
  `hpa:0:1:1:2:1:bi:fw` (the last also with `OSTINATO_BENCH_WARM=1`), each with `OSTINATO_BENCH_REPS=3`, running
  `gradlew --offline --no-daemon --no-build-cache --max-workers=2 cleanTest test -x nativeHost -Postinato_bench
  --tests baritone.pathing.calc.PathBenchTest.benchmark`.
- The earlier long-horizon notes say the hierarchy is "~4% longer than wa2". That compares different query sets. On the
  queries both solved, hpa1bi/wa2 travel = 0.9658 (gap 9).


### Long-distance corpus (new harness, Tier S)

**`LongRouteSim`** (`src/test/java/baritone/pathing/calc/LongRouteSim.java`, `9591d363a`).
- A whole trip runs headless. Each segment is searched by `createPathfinder`'s logic, with
  `findPathInNewThread`'s budgets, and then walked with no physics: travel is the segment's planned cost.
- What it reproduces from production:
  - first search 500/2000 ms;
  - plan-ahead 4000/5000 ms, started when the movements after the current one cost < 150 ticks;
  - a start searched again for the same goal doubles its budget, up to 16000 ms;
  - GoalBlock in an unloaded chunk → GoalXZ;
  - far-segment weight 2.0 at ≥ 128 blocks and Favoring;
  - the whole `calculate()` wrapper: route reuse, cutoffs, and the hold at an irreversible drop on a timed-out
    search.
- Not modelled:
  - the executor (a segment always walks in its cost unless a world change stops it);
  - the dead set: an exhausted plan-ahead that misses the goal ends the trip as "sealed", where production would
    ban the pocket and replan;
  - mobs, and the soft-cancel timing of a goal change.
- **Travel** is in planner ticks, which are NOT_EQUIVALENT to live ticks.
- **Wait** is the ticks the bot stands still for a plan:
  - the whole first search;
  - a plan-ahead's overrun past the segment's end;
  - a replan after a stop.
- **Total** = travel + wait.
- The searches run on the test thread, timed with `System.nanoTime` and per-thread allocation.
- **Horizon mode:** only chunks within 160 blocks of the bot's x are loaded, and more are revealed as it moves, so the
  goal starts in unloaded terrain.
- **Events:**
  - Barrier: a full wall dropped across the route mid-trip; the executor's cost check stops the segment where the wall
    is first seen.
  - GoalSwitch: a new goal after a fixed travel time.
  - cancel: `cancel()` 150 ms into a search; the latency is measured to return.
- **Guards:** 400 searches, 240 s of planning, 100 ticks of travel per straight-line block, or 25 segments without
  progress. A guard fails the trip.
- `LongRouteSim.bindItemComponents()` binds empty item components in the test JVM. Headless MC 26.3 does not bind
  them, and `MovementFall` builds an `ItemStack` in its static initializer.

**Terrain** (`bench/LongTerrain`, seeded, all synthetic; no real-world map is covered).
- OPEN, CAVES, WATER, MIXED (hills) and BREAK_PLACE reuse PathBench's generators at 2400 × 128.
- New:
  - FOREST: gentle ground, stone boulders and trees at 5%;
  - MOUNTAINS: broad ridges up to ~34 blocks with rough 1–3-block detail;
  - MAZE: corridors with alternatives, i.e. full-height walls every 48–72 blocks, each with 1–3 gaps 1–3 wide.

**Coverage against the task's 12 families.**

| task family | covered by | tier |
|---|---|---|
| open | OPEN | S |
| forest | FOREST | S |
| mountains | MOUNTAINS | S |
| caves | CAVES (underground start and goal) | S |
| water | WATER | S |
| mixed | MIXED | S |
| corridors with alternatives | MAZE | S |
| mining | BREAK_PLACE (break) | S |
| placement | BREAK_PLACE (place) | S |
| unloaded or partially known terrain | horizon mode (160-block load radius), every family | S |
| world change | change mode: a wall across the route (OPEN, FOREST, MIXED) | S |
| goal change or cancel | goal mode: switch (OPEN, MIXED); cancel latency (MAZE, BREAK_PLACE) | S |

Not covered: real worlds, mobs, the lossy world cache, chunk loading latency, and executor dynamics (falls, slips,
corrections). No Tier-L run was made (nothing in production changed).

**Modes per family and seed.**
- known: routes of 500 and 1000 blocks, all terrain loaded;
- horizon: 2000 blocks, loaded 160 blocks ahead;
- change: OPEN, FOREST and MIXED, 500 blocks;
- goal: OPEN and MIXED, 1000 blocks;
- cancel: MAZE and BREAK_PLACE, 1000 blocks, 3 repetitions.

Each route type is run `TRIPS` times, and both arms run each route back to back, in alternating order.

### Dev corpus, world seed 2000 (VERIFIED, Tier S, `raw/phase07/longroute/p07-dev-*`)

PROD against HPA, the existing hierarchy at its defaults with production's weight; 2 trips per route type, 54 pairs.
Goal-mode rows are excluded: the switch point then depended on each arm's first segment, which was fixed afterwards
(`fa7165a32`).

| family | PROD known: plan ms per trip | PROD wait share, known | HPA/PROD total ticks, known (CI) | HPA/PROD plan ms, known (CI) |
|---|---|---|---|---|
| OPEN | 9–25 | 0.01% | 1.0014 (0.9988–1.0049) | 20.1 (5.5–50.7) |
| FOREST | 8–17 | 0.01% | 1.0023 (0.9785–1.0355) | 31.0 (20.5–47.3) |
| MOUNTAINS | 12–422 | 0.08% | 1.0031 (0.9994–1.0186) | 7.4 (2.0–16.1) |
| CAVES | 71–26748 | 1.80% | 1.0183 (1.0012–1.2822) | 4.9 (0.26–5.0) |
| WATER | 7–30 | 0.01% | 1.0143 (0.9865–1.0644) | 39.9 (23.7–60.9) |
| MIXED | 18–107 | 0.04% | 1.0281 (1.0182–1.1043) | 9.1 (3.1–25.8) |
| MAZE | 467–4329 | 0.29% | 1.0143 (1.0080–1.1059) | 2.2 (1.2–14.8) |
| BREAK_PLACE | 4154–17264 | 1.01% | 1.0153 (1.0000–1.0328) | 2.6 (0.77–6.8) |

- Every trip reached its goal on both arms; no guard fired.
- PROD's wait share is 0.25% over all trips.
- **Horizon mode (2000 blocks, loaded 160 ahead):** total-tick ratios 1.0000–1.0002. All 297 HPA searches had a
  simplified goal and ended "fallback:goal has no position", so the hierarchy is inert there (gap 1).
- **Change mode (a wall across the route):** both arms recovered every time. HPA costs 4.6–28.8× more planning.
- **Cancel latency:** PROD median 0.01–0.04 ms; HPA median 141.8 ms on BREAK_PLACE and 230.0 ms on MAZE, max 279.9
  (gap 7).
- The one wait above 5% of a trip is a CAVES 500 PROD trip: 311 ticks, 6.9%. Two plan-ahead searches timed out on a
  path that began with an irreversible drop. The hold at drops refused them, and a fresh 16 s search followed. That is a
  safety rule doing its job, not slow search.

### Profile: where production planning time goes (VERIFIED, Tier S, `raw/phase07/profile/`)

- **Run:** JFR CPU samples every 5 ms over the 20 LONG PathBench queries with `wa:2`, 1 repetition.
- **Command:** `OSTINATO_BENCH=p07-prof-wa2 OSTINATO_BENCH_REPS=1 OSTINATO_BENCH_FINDER=wa:2 OSTINATO_BENCH_ONLY=LONG
  OSTINATO_BENCH_PROFILE=1`, the same gradle line, then `jfr print --json --stack-depth 64 --events
  jdk.ExecutionSample` and `python docs/ostinato2/tools/jfr_planner_split.py`.
- **Samples:** 8630 with an A* frame on the stack.
- By bucket:

  | bucket | self % | inclusive % |
  |---|---|---|
  | jdk / fastutil collections | 41.6 | — |
  | movement cost (`baritone.pathing.movement`) | 23.1 | 76.5 |
  | node map / open set | 12.9 | 12.9 |
  | A* loop (`calculate0`) | 10.2 | 100 |
  | block properties | 9.9 | 50.8 |
  | world access | 1.3 | 1.3 |

- **39.8% of all samples are one frame:** `Reference2IntOpenHashMap.getInt`, called only from `IdMapper.getId`, i.e.
  `Block.BLOCK_STATE_REGISTRY.getId(state)`. The callers:
  - `PrecomputedData.canWalkThrough`: 1971 samples;
  - `canWalkOn`: 582;
  - `fullyPassable`: 345;
  - `MovementHelper.isBlockNormalCube`: 535.

  Each looks up the state's id to index a per-state byte table. The registry holds every block state (about 30k), so
  each lookup is an identity-hash probe into tables of about 0.5 MB, while a search touches a few dozen states.
- The open set's `siftUp` is the next frame, at 9.8%.
- **Caveat:** world access is a synthetic array here. Live, it reads chunk palettes and costs more, so the id lookup's
  live share is likely lower than 40%. JFR without `DebugNonSafepoints` can also shift samples between inlined frames.
  The size of the effect is therefore taken from an A/B timing, not from this profile.

### Decision gate (recorded before any implementation, 2026-11-09)

**Choice: Option A.** Improve the existing planner at the verified bottleneck: a small cache in front of the
block-state id lookup in cost evaluation, behind a new default-false flag.

**Evidence.**
- The profile: the id lookup is 40% of planner CPU samples, and cost evaluation is 76.5% inclusive.
- The dev corpus: planning wait is not a travel bottleneck (0.25% of ticks), but planning CPU is large on hard terrain,
  up to 27 s per trip.
- Lower CPU per search serves the task's objective directly: performance per unit of compute. Where a budget binds, it
  also means more nodes in the same time.

**Rejected.**
- **Option B, a new hierarchy.**
  - The existing one is never faster in total ticks on the dev corpus (median 1.0018 overall) and costs 2–40× more
    planning.
  - It is inert beyond loaded terrain, and its cancel takes 140–280 ms.
  - A new layer would duplicate it, and would aim at a cost (planning wait) that is 0.25% of trip time.
- **Fixing the existing hierarchy's gaps 1–7.** Most of the work, and the best case is still no faster in total ticks.
  They are listed for handoff instead.
- **Option C.** It would leave a measured 40% hotspot in every search.
- **A mixin field on `BlockState`** holding the id or the flags. Faster still, but it patches a vanilla class, and
  mixins do not run headless, so it cannot be measured or tested there.
- **Relaxing the hold at drops** (the one CAVES wait). It trades safety for at most ~2% of one cell's ticks.
- **The open set.** `siftUp` is 9.8%, and the heap is already the tuned binary heap.

**Expected improvement.** Planning ms per expanded node down 20–40% in every family, depending on how much of the 40%
the cache removes. Paths are unchanged wherever no budget binds, since the same ids give the same costs. Where a budget
binds, the search expands more nodes and the path may differ.

**Complexity.**
- One new class of about 30 lines and one setting.
- The four lookup sites take the cache when the flag is on.
- No change to the search, the costs, the budgets or the executor.

**Risks.**
- The gain may be smaller than the profile suggests: the attribution caveat above.
- A collision in the cache costs a registry lookup. It is never a wrong id, since the state is compared by identity.
- Thread safety: the entries are immutable, so a racing reader sees a whole entry or none.
- The main-thread cost checks of the executor take the same path when the flag is on, where the cache should only lower
  their cost. That is not measured live in this phase.

**Smallest slice.**
- `StateIdCache`: a static direct-mapped cache of 1024 immutable (state, id) entries.
  - On a miss or a collision it falls back to the registry.
  - Bounded: at most 1024 entries.
  - No invalidation is needed: a state's id is fixed for the life of the JVM once the registry is built.
- Used by `PrecomputedData` (three sites) and `MovementHelper.isBlockNormalCube` when the new setting
  `pathingStateIdCache` is true (default **false**).
- Off, every site runs the existing registry lookup.


### Dev A/B of the slice (VERIFIED, Tier S, dev data, `raw/phase07/ids/p07-ids-dev-*`)

- **Run:** PathBench `wa:2`, all 60 queries (5 terrains × SHORT/MEDIUM/LONG), 3 repetitions per run, four runs in the
  order off, on, on, off at `57364373c`. These are the same fixed worlds and queries as the baseline, so they are dev
  data.
- **Command:** `docs/ostinato2/tools/phase07/p07-ids-bench.ps1 -prefix p07-ids-dev -order off,on,on,off` (the gradle
  line above, with `OSTINATO_BENCH_FINDER=wa:2`, and `OSTINATO_BENCH_STATEIDS=1` for "on"), then
  `python docs/ostinato2/tools/ids_bench_ab.py docs/ostinato2/raw/phase07/ids p07-ids-dev-off-1,p07-ids-dev-off-4
  p07-ids-dev-on-2,p07-ids-dev-on-3`.
- **Equivalence:** 59 of 60 queries ran the identical search, with the same node count in every row of both arms. The
  one that differs, BREAK_PLACE/LONG/0, is bound by the 10 s budget and expanded 8% more nodes with the cache on.
  Failed rows were 6 off and 6 on, and the mean travel cost over the 59 queries both arms solved is 1383.49 in both.
- **Speed (on/off ms ratio, same-search queries, median and bootstrap 95% CI):**

  | group | queries | median ratio | 95% CI | summed ms ratio |
  |---|---|---|---|---|
  | ALL | 59 | 0.954 | 0.951–0.968 | 0.957 |
  | SHORT | 20 | 0.953 | 0.911–0.976 | 0.948 |
  | MEDIUM | 20 | 0.951 | 0.919–0.972 | 0.950 |
  | LONG | 19 | 0.968 | 0.951–0.979 | 0.965 |

- **Result:** about 4.5% less planner time. That is real (the CI excludes 1), but far below the 20–40% the decision gate
  expected, and below the task's 30% target.

### Diagnosis: the 40% hotspot was a profiling artefact (VERIFIED, Tier S, `raw/phase07/profile/p07-prof2-*`)

- **Question:** why does removing a "40%" lookup save only 4.5%?
- **Run:** the same profile as above, repeated with `-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints` (passed
  through `JAVA_TOOL_OPTIONS`), once with the cache off and once with it on. Script:
  `docs/ostinato2/tools/phase07/p07-profile2.ps1`.
  Read with `jfr print --events jdk.ExecutionSample --stack-depth 1`, counting top frames.
- **The callers first.** In the first profile, 3433 of the 3434 `getInt` samples come through the four sites the cache
  now covers. So the cache does reach every call of the lookup.
- **Top self frames, with accurate attribution:**

  | frame | off (8715 samples) | on (8192 samples) |
  |---|---|---|
  | `AStarPathFinder.calculate0` | 16.8% | 15.2% |
  | `SyntheticWorld.real` (bench world only) | 10.6% | 7.8% |
  | `Reference2IntOpenHashMap.getInt` (the id lookup) | 5.9% | — |
  | `StateIdCache.id` | — | 6.5% |
  | `FluidState.getType` | 5.4% | 5.3% |
  | `BlockStateBase.getBlock` | 4.5% | 4.5% |

- **Conclusion.**
  - The id lookup is about 6% of planner samples, not 40%. Without `DebugNonSafepoints`, samples from the surrounding
    inlined cost code were attributed to it.
  - The cache's own frame costs about what the lookup did. The time saved (6% fewer samples) shows up as cheaper
    neighbouring memory reads (`SyntheticWorld.real` 10.6% → 7.8%), consistent with less cache pollution from the 0.5 MB
    registry tables.
  - Planner CPU is spread flat, and no single site exceeds 17%. No change at one site can reach the 30% target. A
    larger gain would need a broad restructuring of cost evaluation, which is outside this phase's smallest-slice rule
    and is not attempted.
- **What this does to the decision.** The slice stays as decided: it is behaviour-identical and a verified small gain.
  The expected improvement recorded at the gate (20–40%) is falsified by dev data. It is not revised to fit; the
  confirmatory run below is judged against the criteria as written.

### Pre-registration: confirmatory A/B of `pathingStateIdCache` (committed before any held-out or live run)

Everything below was fixed before any held-out data was produced. The criteria are the task's provisional target and
guards. The classification rules were drafted before the dev A/B and are not changed after it. From the dev data, the
primary target is expected to be **missed** (median about 0.95, not ≤ 0.70), so the expected class is PARTIAL if every
guard holds.

**Arms.**
- OFF: production defaults (`pathingStateIdCache` false).
- ON: identical, except `pathingStateIdCache` true.
- Nothing else differs between the arms.

**Instruments and held-out data.** None of these worlds, queries or seeds has been run before.
- **H, long routes (Tier S):**
  - LongRouteSim, held-out world seeds **3001, 3002**, 2 trips per family and mode.
  - All 8 synthetic families, and all five modes (known, horizon, change, goal, cancel).
  - Arms PROD (OFF) and IDS (ON).
  - Command: `docs/ostinato2/tools/phase07/p07-longroute.ps1 -label p07-ho -set heldout -seeds 3001,3002 -trips 2
    -arms PROD,IDS`.
  - Read with: `python docs/ostinato2/tools/longroute_summary.py --arms=PROD,IDS docs/ostinato2/raw/phase07/longroute
    p07-ho`.
- **P, short/medium/long queries (Tier S):**
  - PathBench `wa:2`, held-out world and query seed **3101** (`OSTINATO_BENCH_SEED=3101`, new this phase; unset, the
    bench is unchanged).
  - 3 repetitions per run, four runs in the order off, on, on, off.
  - Command: `docs/ostinato2/tools/phase07/p07-ids-bench.ps1 -prefix p07-ids-ho -order off,on,on,off
    -seed 3101`.
  - Read with `ids_bench_ab.py` as above.
- **L, live client (Tier L):**
  - `LegatoLiveTest`, `-Dostinato.legatotest.arms=stateids` (new this phase), on SimEdgeCourse.
  - A and C run with the flag off, B and D with it on. The travel stack and every other setting stay at their
    defaults.
  - `reps=6`, so 96 laps per seed, in latin order.
  - Fresh seeds **87071** and **87072**.
  - The client runs on the second monitor, muted, with no mouse grab, and nothing else running.
  - Command, per seed: `docs/ostinato2/tools/phase07/p07-live.ps1 -seed <87071|87072>`. It sets
    `JAVA_TOOL_OPTIONS` as in Phase 06A, with `-Dostinato.legatotest.arms=stateids`, no stack pin,
    `-Dostinato.legatotest.reps=6` and `-Dostinato.seed=<seed>`, then runs `:fabric:runClient -PclientXmx=1536M`. It
    keeps `ab-<seed>-lines.txt`, `ab-<seed>-result.txt` and `ab-<seed>-ticks.csv.gz` in `raw/phase07/live/`.
  - Read with `python scripts/stateids-ab.py docs/ostinato2/raw/phase07/live/ab-87071-lines.txt
    docs/ostinato2/raw/phase07/live/ab-87072-lines.txt`.

**Statistics.**
- Paired by route (H), by query (P), or by seed, leg and repetition (L).
- Median of the per-pair ratio or difference.
- Bootstrap 95% CI: 10,000 resamples, `random.Random(20261108)`, percentile interval.

**Primary.**
- **P1, planning cost.** H: the median per-trip ON/OFF ratio of planning ms per expanded node is ≤ 0.70, and its CI
  upper bound is < 1.0.
- Ms per node is used, not ms, because a budget-bound search expands more nodes in the same time.

**Guards.** All must hold.
- **G1, reliability.**
  - H: net losses ≤ 0. A loss is a route OFF reached and ON did not; a gain is the reverse.
  - P: no more failed rows ON than OFF.
- **G2, travel.** H, over pairs both reached: median total-ticks ratio (travel plus planning wait) ≤ 1.01, and CI
  upper ≤ 1.02.
- **G3, equivalence.** H: in every pair where neither trip had a timed-out search, ok, travel, nodes, segments,
  searches and end distance are identical. Any mismatch is a FAIL: it would mean the cache changed a cost.
- **G4, short distance.**
  - P: the SHORT same-search ms ratio has a median ≤ 1.02, and its CI lower bound is ≤ 1.0 (no demonstrated
    slowdown).
  - Live laps are short trips too, which LT covers.
- **G5, tick overhead.** L: Phase 06A's L5 rule, on per-lap client tick p95 and p99, paired on − off, both seeds
  pooled.
  - **FAIL:** a CI lower bound > 0.5 ms.
  - **Acceptable:** both CI upper bounds ≤ 0.5 ms.
  - **Otherwise:** inconclusive.
- **LR, live reliability.** In each seed:
  - B ≥ A − 1 and D ≥ C − 1 laps reached;
  - no stuck key, no death, and no fault code that appears only in B or D;
  - every lap row's flag matches its arm.
- **LT, live lap time.** Over reached pairs, both seeds pooled: the ON/OFF lap-ticks ratio has CI upper ≤ 1.03.

**Live direction.**
- **LP.** Pooled ON/OFF ratio of per-lap A* time (`planUs`), with CI upper < 1.0.
- Not a guard: it only decides whether the headless gain is confirmed live.

**Classification.**
- **PASS:** P1, every guard (G1–G5 acceptable, LR, LT) and LP hold.
- **PARTIAL:** every guard holds (G5 acceptable or inconclusive), and the H ms-per-node CI upper is < 1.0, but P1's
  median is > 0.70, LP is not confirmed, or G5 is inconclusive.
- **FAIL:** any guard fails, or the H ms-per-node CI upper is ≥ 1.0 (no demonstrated reduction).
- A PASS is not a promotion. **No default changes in this phase**: `pathingStateIdCache` stays false whatever the
  class.

**Stop conditions.**
- No seeds, repetitions or trips are added after results are seen, and no run is repeated to change an outcome.
- A run that fails to complete (gradle error, client crash) is rerun once with the same seed. If it fails again, it is
  recorded as a blocker and its tier is not claimed.
- A death or stuck key in the first live seed is LR FAIL. The second seed is then not run, and the class is FAIL.

### Phase 07 results: confirmatory A/B of `pathingStateIdCache` (VERIFIED, Tier S; Tier L not run)

All held-out runs were made at `92961cddf`, the pre-registration commit, with a clean tree apart from their own
outputs. The criteria are the ones above, unchanged.

**H, long routes** (`raw/phase07/longroute/p07-ho-*`):
- Seeds 3001 and 3002, 2 trips per route type: 116 trips per arm, 113 pairs that both arms reached, plus 12 cancel
  repetitions per arm and family on MAZE and BREAK_PLACE.
- Command: `docs/ostinato2/tools/phase07/p07-longroute.ps1 -label p07-ho -set heldout -seeds 3001,3002 -trips 2 -arms
  PROD,IDS`, 818 s, exit 0.
- Read with `python docs/ostinato2/tools/longroute_summary.py --arms=PROD,IDS docs/ostinato2/raw/phase07/longroute
  p07-ho`, saved as `p07-ho-summary.txt`.

**P, PathBench** (`raw/phase07/ids/p07-ids-ho-*`):
- `wa:2` with seed 3101, 3 repetitions, in the order off, on, on, off: 416–435 s per run, all exit 0.
- Command: `docs/ostinato2/tools/phase07/p07-ids-bench.ps1 -prefix p07-ids-ho -order off,on,on,off -seed 3101`.
- Read with `python docs/ostinato2/tools/ids_bench_ab.py docs/ostinato2/raw/phase07/ids
  p07-ids-ho-off-1,p07-ids-ho-off-4 p07-ids-ho-on-2,p07-ids-ho-on-3`, saved as `p07-ids-ho-summary.txt`.

**Results against the pre-registration:**

| criterion | rule | result | verdict |
|---|---|---|---|
| P1, planning cost | H ms-per-node ON/OFF median ≤ 0.70, CI upper < 1.0 | **0.9624** (0.9565–0.9686), 113 pairs | **missed**: the reduction is demonstrated (CI upper < 1), but it is 3.8%, not 30% |
| G1, reliability (H) | net losses ≤ 0 | lost 0, gained 0. The 3 trips not reached (BREAK_PLACE known ×1, CAVES horizon ×2) failed in both arms | holds |
| G1, reliability (P) | failed rows ON ≤ OFF | 30 and 30 (the same 5 budget-bound LONG queries) | holds |
| G2, travel (H) | total-ticks ratio median ≤ 1.01, CI upper ≤ 1.02 | 1.0000 (1.0000–1.0000) | holds |
| G3, equivalence (H) | pairs with no timed-out search are identical in ok, travel, nodes, segments, searches and end distance; any mismatch is a FAIL | 88 such pairs, **8 differ**. All 8 are goal mode (OPEN and MIXED, 1000 blocks, both seeds); the other 80 are identical | **FAIL as written** (cause below) |
| G4, short distance (P) | SHORT same-search ms ratio median ≤ 1.02, CI lower ≤ 1.0 | 0.9429 (0.9383–0.9699), 20 queries | holds |
| G5, LR, LT, LP (live) | per the pre-registration | **not run** (below) | — |

**Other H numbers:**

| quantity | value |
|---|---|
| planning ms, ON/OFF | median 0.969 (0.960–0.981) |
| PROD planning wait, share of trip ticks | 0.26% over 116 trips; worst cell CAVES known, 2.00% |
| cancel latency, both arms | median 0.01 ms; max 0.04 (PROD BREAK_PLACE), 0.09 (PROD MAZE), 0.03 (IDS) |

Per family, the ms-per-node point estimate is below 1 in 17 of 21 cells. The 4 above 1 are cells where planning is
under 0.5 s per trip and the CI spans 1.

**Other P numbers:**

| group | queries | ON/OFF ms median (CI) |
|---|---|---|
| ALL | 55 | 0.9595 (0.9433–0.9661) |
| MEDIUM | 20 | 0.9558 (0.9399–0.9782) |
| LONG | 15 | 0.9607 (0.9446–0.9810) |

- The 5 differing queries are the budget-bound ones (10 s). ON expands 2.3–6.6% more nodes in the same time, with a
  ms-per-node ratio of 0.94–0.98.
- Mean travel cost over the 55 queries both solved is 1332.40 in both arms.

**Bounded diagnosis of G3: the goal-mode pairs are not pairs.**
- In `LongRouteSimTest.corpus`, the goal-mode event is built inside the per-arm loop (`for (LongRouteSim.Arm arm :
  order)`, line 223). Its new goal's z comes from `qr.nextInt(SIZE_Z - 16)` (line 237), on a `Random` shared across
  the arms. The second arm therefore switches to a different goal from the first.
- The data agree. In all 8 differing pairs:
  - search 0, before the switch, is identical in both arms (the same node count: 1000, 937, 954, 970, 7942, 8761,
    37411 and 40675);
  - search 1, the replan to the switched goal, differs;
  - the end distances differ by up to 4.9 blocks, which a single cost change would not explain.
- No trip in the other modes differs. Those modes draw nothing inside the arm loop, and they cover 80 pairs over all
  8 families.
- The defect was introduced with the harness in this phase (`9591d363a`). It also affects the dev goal-mode rows, and
  the HPA comparison in the dev corpus, which excluded goal mode for a different reason.
- The fix is to draw the switch goal once per route, before the arm loop. It is **not** applied: the rule says any
  mismatch is a FAIL, and a rerun to change an outcome is not allowed.
- So, on the evidence, the cache changed no cost on any valid pair. But G3 was written without an exception, and it is
  scored as written.

**Live A/B (Tier L): not run.**
- The class is already FAIL on a guard, so the live runs cannot change it.
- The task's failure rule is to preserve the evidence, diagnose within bounds, keep legacy active and stop. So the two
  live seeds were not run.
- The live arm (`-Dostinato.legatotest.arms=stateids`), `docs/ostinato2/tools/phase07/p07-live.ps1` and
  `scripts/stateids-ab.py` are committed and ready.
- G5, LR, LT and LP are **not evaluated**, and nothing is claimed at Tier L. In particular, the cache's effect on
  client-thread cost checks is unmeasured.

#### Phase 07 classification: FAIL

- **G3 fails as written:** 8 of 88 equivalence pairs differ.
- The verified cause is a harness pairing defect in goal mode, not the cache. Every pair the harness built correctly
  is identical.
- Independently of G3, **P1 is missed**: 3.8% less planning time per node, against a 30% target.
- Had G3 been scored on valid pairs only, the headless evidence would at best have supported PARTIAL, with the live
  guards still open. That is recorded as context for the owner, not as the class.
- `pathingStateIdCache` stays default **false**. The existing A* planner, the executor, the hierarchy (default
  false) and every default setting are unchanged.


## Phase 08: capability integration, navigate → interact → verify (2026-11-09, branch `ostinato2/claude-review`, local only)

The task: make one end-to-end workflow run reliably, with explicit outcomes, ownership, cancellation and recovery, and
with success resting only on a server-confirmed postcondition. Phase start HEAD `b9282552b` (tree clean apart from an
untracked `scripts/__pycache__/`). Phase 06/07 features stay off (`unifiedExecutor`, `pathingStateIdCache`, ...). No
default changes.

### Audit: capability map (read from `b9282552b`; live where marked)

Labels: VERIFIED (read in code, or measured live where marked), REPORTED (stated in docs, not re-checked), UNKNOWN,
FAILED (the capability does not do what its caller assumes).

| Capability | Entry point | Resource ownership | Completion evidence | Cancellation | Recovery |
|---|---|---|---|---|---|
| Navigation | `CustomGoalProcess.setGoalAndPath` → `PathingControlManager` → `PathingBehavior` | movement keys via the physics driver / input arbiter; processes call `clearAllKeys()` directly (B6, open). VERIFIED | **FAILED** at the process: `state = NONE` alike on arrival, `calcFailed` and `onLostControl`; `isActive()` is the only signal. The task runner's GOTO step re-checks `goal.isInGoal(feet)`. VERIFIED | `cancelEverything()` → every process's `onLostControl`. VERIFIED | replan; blacklist; Cadenza give-up. VERIFIED |
| Mining | `MineProcess.mine` | `CLICK_LEFT` + look via helpers. VERIFIED | inventory count, checked by the runner's MINE step when a count is given; a count-less mine is "done" when inactive. VERIFIED | `onLostControl` → `mine(0, null)`. VERIFIED | blacklist of failed spots. VERIFIED |
| Placement | `BuilderProcess.build` | `CLICK_RIGHT`/`CLICK_LEFT` + look. VERIFIED | "Done building" → inactive; "Unable to do it. Pausing" stays **active** (a hang, not a false success). The runner's BUILD step returns success on inactive, so a **preempted** build is a false success. VERIFIED | `onLostControl`. VERIFIED | pause, then resume by command. VERIFIED |
| Interaction | `GetToBlockProcess.getToBlock` (`goto <block>`), right click on arrival for six container blocks | `CLICK_RIGHT` + look; `onLostControl` → `clearAllKeys()` (B6). VERIFIED | **FAILED**: `rightClick()` returns "done" for *any* non-inventory menu (no type check, a menu left open counts), for a 20-tick timeout, and for "no reachable face"; all three end exactly like success, and the runner's GET_TO_BLOCK verify returns success. Live baseline below: VERIFIED false successes. | `onLostControl`; a click already sent can still open the container afterwards (live: `lateOpen=true`). VERIFIED | blacklist closest on calc failure; with `exploreForBlocks` (default on) an empty target list **explores forever** (live: HANG). VERIFIED |

Other findings (VERIFIED by reading):
- No process exposes an outcome; callers see only `isActive()`. The task builder (`TaskRunner` + `BaritoneTaskAdapter`)
  is the one higher-level consumer, and it treats inactive as success for every step type except GOTO and counted MINE.
- `TunnelProcess` is a safe boundary: it never trusts builder inactivity (re-checks `stillSolid()`, at most 3 tries,
  stall watchdog).
- `GetToBlockProcess.rescan` runs on `Baritone.getExecutor()` every 5 ticks and is not tied to the request that
  scheduled it: after the request ends it throws an NPE (live: logged in every baseline run), and it can write
  `knownLocations` into a newer request.

### Workflow selection: C, navigate → interact → verify

- **C (chosen).** Confirmed false success at two layers (process and runner); small surface (one 315-line process,
  one adapter method); a server-confirmed postcondition exists (the server opens a container menu for the player and
  the client sees it only after the OpenScreen packet); the ownership surface is one key and the look target; an
  existing live fixture pattern (`CadenzaLiveTest`) to build on.
- A (mine) was rejected: counted mining is already postcondition-verified; the remaining gap (count-less mine) has no
  crisp postcondition.
- B (place/build) was rejected: `BuilderProcess` is 2006 lines; its false success needs a preemption; its failure mode
  is a hang that would need builder changes.
- D (recover → resume) was rejected: it depends on the disabled `unifiedExecutor` lease path.

### Operation semantics (the slice)

- One request = one `getToBlock` call; it gets a new request number. States: ACCEPTED on the call (REQUESTED is not
  separately observable here), RUNNING on the first tick, then exactly one terminal state, which sticks.
- **SUCCEEDED** only when the server opened a container of the matching type (`ChestMenu` for chest / trapped chest /
  ender chest, `CraftingMenu`, `FurnaceMenu`, `BlastFurnaceMenu`) *after this request saw no container open while
  clicking*; for non-container blocks, arrival at the goal.
- **FAILED** with a reason: `TIMEOUT` (click not answered in 20 ticks), `UNREACHABLE` (no reachable face, every
  instance blacklisted, or no path), `WORLD_CHANGED` (every known instance vanished), `INVALIDATED_GOAL` (no instance was
  ever known). With the flag on, GET_TO_BLOCK never falls back to exploring: exploring is its own command (and its
  own task step), and an open-ended explore cannot have a bounded outcome.
- **CANCELLED** / `OWNERSHIP_LOST` when control is lost before any click; **UNKNOWN** / `OWNERSHIP_LOST` when control
  is lost after the click was sent, because the server may still open the container.
- The task runner's GET_TO_BLOCK step (flag on) passes only if the outcome belongs to the request the step started,
  is SUCCEEDED, and the player's container menu now is of the matching type and is not the one open at step start.

### Slice (behind `verifiedGetToBlock`, default false)

- `ProcessOutcome` (api, new record): request number, state, reason. The first terminal state sticks; a new request
  gets a new number and starts clean. `IGetToBlockProcess.outcome()` exposes it (default `NONE`).
- `GetToBlockProcess`: every request records its outcome, flag on or off. Only the flag changes behaviour:
  - the right click counts only if the menu that opened matches the block's type, and no container was open when the
    click began (one left open is closed first);
  - a 20-tick unanswered click is FAILED/TIMEOUT, no reachable face FAILED/UNREACHABLE (legacy: both "done");
  - with no known target it ends FAILED (INVALIDATED_GOAL, WORLD_CHANGED or UNREACHABLE) instead of exploring;
  - the async rescan is tied to the request that scheduled it and dropped after it (legacy: NPE, or a stale list
    written into a newer request);
  - control lost before the click is CANCELLED/OWNERSHIP_LOST, after it UNKNOWN/OWNERSHIP_LOST.
- `BaritoneTaskAdapter` GET_TO_BLOCK step (flag on): passes only if the step started a new request, that request
  SUCCEEDED, and the player's menu now is of the matching type and is not the one open at step start.
- Ownership: the process still releases keys with `clearAllKeys()` in `onLostControl()` (B6, unchanged; the slice adds
  no key writer). Live, no key is held one tick after any stop in either arm.

### Baseline (legacy, Tier L, seed 88001, reps 3, 54 laps; `raw/phase08/p08-base-88001-lines.txt`)

Run before any product change (both arms legacy; the flag did not exist yet). Per arm (A and B identical):

| scenario | result | what happened |
|---|---|---|
| OPEN | TS 3 | median 37 ticks, 1 use packet |
| REPLACE | TS 3 | |
| CANCEL_MOVE rerun | TS 3 | |
| REPEAT step 1 / step 2 | TS 3 / **FS 3** | the chest menu still open counts as the crafting table |
| CAT | **FS 3** | the server refuses the open; the 20-tick timeout is reported as done (5 use packets) |
| PREEMPT | **FS 3** | the goal process takes control; the runner reads inactive as done |
| CANCEL_ACT rerun | **FS 3** | the rerun inherits the chest that opened late after the stop (`lateOpen=true` 3/3), 0 use packets |
| VANISH | **HANG 3** | explores forever (`GoalRunAwayFrom`) |
| SEALED | **HANG 3** | explores forever |

- Completion on possible rows 12/18 per arm; 12 false successes; 0/12 honest failures on impossible rows; 6 HANG.
- No key held or process active after any stop or lap end.
- 9 `NullPointerException at BlockOptionalMetaLookup.<init>` from the stale async rescan (logged without lap
  attribution in this run).
- Defect: false success at both layers (process and runner) on every failure mode the fixture has, and no bounded end
  when the target does not exist.

### Dev runs (seed 88001 only; dev data, not evidence)

- `p08-dev1-88001-lines.txt` (partial, flag on in B): CAT, PREEMPT and REPEAT fixed in B; VANISH and SEALED still
  HANG. Found a **harness oracle defect**: an open was detected on a change of menu kind, so a close and reopen in
  one server tick (the stale-menu close) was missed and the CANCEL_ACT rerun read FS in B. Fixed: a new open is a new
  server menu object.
- `p08-dev2-88001-lines.txt` (VANISH, SEALED, CANCEL_ACT, reps 1): CANCEL_ACT B rerun TS with the fixed oracle (A
  still FS). Found two **fixture isolation defects**:
  - VANISH B pursued natural chests Baritone had cached far away (e.g. (227,-24,225)), outside loaded chunks, so
    "the target vanished" was false. Removing chests in loaded chunks (5–57 per lap) cannot fix the cache.
  - SEALED: Baritone drops a target with bedrock above and below (`MineProcess.plausibleToBreak`), so it is never
    known, and B fell back to exploring.
  - Fixes (before pre-registration): VANISH and SEALED use an ender chest, which the overworld never generates; and
    with the flag on, GET_TO_BLOCK fails with INVALIDATED_GOAL instead of exploring when no target was ever known.
- `p08-dev3-88001-lines.txt` (VANISH, SEALED, CAT, OPEN, reps 1): B gives VANISH TF (FAILED/WORLD_CHANGED, 31
  ticks), SEALED TF (FAILED/INVALIDATED_GOAL, 30 ticks), CAT TF (FAILED/TIMEOUT, 5 uses as A), OPEN TS (38 ticks
  against A's 37). A unchanged (HANG, HANG, FS, TS). Both stale-rescan NPEs fall in A laps.

### Pre-registration: confirmatory live A/B of `verifiedGetToBlock` (committed before any held-out run)

Everything below was fixed after the legacy baseline and three dev runs on seed 88001 (dev data only), and before
any held-out data.
It is not changed after the held-out runs.

**Arms.**
- A: production defaults (`verifiedGetToBlock` false).
- B: identical, except `verifiedGetToBlock` true.
- Each (rep, scenario) runs both arms back to back, and the order alternates with `(rep + scenario) % 2`. The harness sets the flag at lap start and clears it
  between laps. `cadenza`, `exploreForBlocks`, `rightClickContainerOnArrival` and every other setting stay at their
  defaults (the start line records them).

**Instrument (Tier L).**
- `InteractLiveTest`: a fresh world per run, Peaceful, server-built arena at y 200, a chest at (12,200,10), a
  crafting table at (12,200,15), start (2,200,10).
- At each lap's setup every other chest, trapped chest and ender chest in loaded chunks is removed (the count is
  logged on the `begin` line).
- VANISH and SEALED use an **ender chest** at the chest's position: the overworld never generates one, so the
  fixture's block is the only instance Baritone can know of (it remembers natural chests far outside loaded chunks).
- It drives the production `TaskRunner` + `BaritoneTaskAdapter` with GET_TO_BLOCK steps.
- **The oracle is the integrated server's `ServerPlayer.containerMenu`:** a `ChestMenu` whose container is the
  block entity at the chest's position (or the player's ender chest inventory whose active chest is the ender chest
  at that position), or a `CraftingMenu` with the player within 8 blocks of the table. It is read every tick; a new
  open is a new server menu object of the target kind, and the harness records the tick it appeared.
- The process's own outcome is logged next to the oracle, and is never used to classify.
- Scenarios:
  - **OPEN:** the open succeeds.
  - **CAT:** a sitting cat on the chest; the server refuses the open.
  - **VANISH:** the ender chest becomes stone when the player is within 3.5 blocks.
  - **SEALED:** the ender chest is enclosed in bedrock (Baritone drops a target it cannot break, so the request
    has no known target at all).
  - **CANCEL_MOVE:** `runner.stop()` at tick 15; checks after 1 and 40 ticks, then a rerun.
  - **CANCEL_ACT:** stop on the tick the right click is pressed, then a rerun.
  - **PREEMPT:** a `customGoalProcess` goal at tick 20.
  - **REPLACE:** a new run (crafting table) at tick 15.
  - **REPEAT:** chest then crafting table in one list.
- Classes: TS, FS, TF, FF, HANG (no verdict in 600 ticks), DIED.

**Held-out seeds and counts.**
- Seeds **88011** and **88012** (never run), with `reps=4`. That is 72 laps per seed: 4 per scenario and arm.
- Command, per seed (PowerShell, client stopped, second monitor, muted, no mouse grab):
  `$env:JAVA_TOOL_OPTIONS='-Dostinato.noMouseGrab=true -Dostinato.windowPos=2600,40 -Dostinato.testHarness=true
  -Dostinato.interacttest=true -Dostinato.interacttest.exit=true -Dostinato.interacttest.reps=4
  -Dostinato.seed=<seed>'; .\gradlew.bat --offline --no-daemon "-Dorg.gradle.jvmargs=-Xmx1536M" :fabric:runClient
  -PclientXmx=1536M`.
- Extract: `python docs/ostinato2/tools/phase08/interact_ab.py --extract <client log>
  docs/ostinato2/raw/phase08/p08-ho-<seed>-lines.txt`.
- Read: `python docs/ostinato2/tools/phase08/interact_ab.py
  --baseline=docs/ostinato2/raw/phase08/p08-base-88001-lines.txt docs/ostinato2/raw/phase08/p08-ho-88011-lines.txt
  docs/ostinato2/raw/phase08/p08-ho-88012-lines.txt`.

**Gates.** Both seeds are pooled, in arm B unless stated. The script computes them exactly as written here.
- **G1, no false success.** B FS = 0.
- **G2, completion.**
  - Rows where the open is possible: OPEN, REPLACE, both REPEAT steps, and the CANCEL_MOVE and CANCEL_ACT reruns.
  - B TS / possible ≥ 0.95.
  - B TS ≥ A TS on OPEN, REPLACE and the CANCEL_MOVE rerun, where legacy is right.
- **G3, false failures.** B TF + FF on possible rows ≤ 1.
- **G4, liveness.** B HANG = 0.
- **G5, safety.**
  - No key held or process active one tick after a stop (`keys1`, `active1`), or 40 ticks later.
  - No key held or process active at lap end.
  - No "keys still held" line after a B lap, and no death.
- **G6, no new exception.** Every exception (type plus first frame) logged during a B lap also appears in an A lap or
  in the baseline run.
- **G7, honest cancel.**
  - Every B stop row's outcome is CANCELLED or UNKNOWN.
  - Every CANCEL_ACT stop with `lateOpen=true` reports UNKNOWN.
- **G8, self-report agrees with the oracle.** In B rows whose outcome is terminal, SUCCEEDED ⟺ TS.
- **P1, cost.**
  - OPEN true-success ticks: median B − median A ≤ 5.
  - Median per-lap runner µs/tick: B ≤ A + 25.
- **P2, no extra actions on a refused open.** CAT use-packets: sum B ≤ sum A.

**Classification.**
- **FAIL:** any of G1, G4, G5 or G6 fails.
- **PASS:** every gate (G1–G8, P1, P2) holds.
- **PARTIAL:** otherwise, i.e. G1, G4, G5 and G6 hold but another gate misses.
- **NO CHANGE JUSTIFIED** would have applied if the baseline had shown no defect. It did not.
- A PASS is not a promotion. **No default changes in this phase**: `verifiedGetToBlock` stays false whatever the
  class.

**Stop conditions.**
- No seeds or repetitions are added after results are seen, and no run is repeated to change an outcome.
- A run that does not complete (gradle error, client crash, harness setup failure) is rerun once with the same seed.
  If it fails again, it is a blocker and Tier L is not claimed.
- A death or held key in a B lap of the first seed is a G5 FAIL. The second seed is then not run.

### Phase 08 results: confirmatory held-out live A/B of `verifiedGetToBlock` (VERIFIED, Tier L)

Run after the pre-registration commit `a6c4006ee`, exactly as registered: seeds 88011 then 88012, `reps=4`, 72 laps
each, both runs completed first time (no rerun). Raw: `raw/phase08/p08-ho-88011-lines.txt`,
`raw/phase08/p08-ho-88012-lines.txt`. Command:
`python docs/ostinato2/tools/phase08/interact_ab.py --baseline=docs/ostinato2/raw/phase08/p08-base-88001-lines.txt
docs/ostinato2/raw/phase08/p08-ho-88011-lines.txt docs/ostinato2/raw/phase08/p08-ho-88012-lines.txt`.

Per-row classes (oracle: the server's open menu), pooled over both seeds:

| scenario | A (legacy) | B (`verifiedGetToBlock`) | B's own outcome |
|---|---|---|---|
| OPEN | TS 8 | TS 8 | SUCCEEDED |
| REPLACE | TS 8 | TS 8 | SUCCEEDED |
| REPEAT (2 steps) | TS 8, **FS 8** | TS 16 | SUCCEEDED |
| CANCEL_MOVE rerun | TS 8 | TS 8 | SUCCEEDED |
| CANCEL_ACT rerun | **FS 8** | TS 8 | SUCCEEDED |
| CAT (open refused) | **FS 8** | TF 8 | FAILED/TIMEOUT |
| PREEMPT | **FS 8** | TF 8 | CANCELLED/OWNERSHIP_LOST |
| VANISH | **FS 4, HANG 4** | TF 8 | FAILED/WORLD_CHANGED |
| SEALED | **FS 4, HANG 4** | TF 8 | FAILED/INVALIDATED_GOAL |

| measure | A | B |
|---|---|---|
| completion on possible rows | 32/48 (0.667) | **48/48 (1.000)** |
| false successes | 40 | **0** |
| false failures (TF/FF where possible) | 0 | 0 |
| honest failure on impossible rows | 0/32 | **32/32** |
| HANG (no verdict in 600 ticks) | 8 | 0 |
| deaths | 0 | 0 |
| key held / process active 1 tick after stop, 40 ticks after, at lap end, after lap | 0 | 0 |
| stop outcomes: CANCEL_MOVE / CANCEL_ACT (`lateOpen=true` 8/8 in each arm) | CANCELLED 8 / UNKNOWN 8 | CANCELLED 8 / UNKNOWN 8 |
| self-report vs oracle (terminal rows: SUCCEEDED ⟺ TS) | not gated | 80/80 agree |
| stale-rescan NPE (`BlockOptionalMetaLookup.<init>`) | 16 | 0 |
| OPEN ticks to verified open, median | 37 | 37 |
| TF ticks, median: CAT / PREEMPT / VANISH / SEALED | n/a | 55 / 20 / 31 / 30 |
| CAT use packets (sum) | 40 | 40 |
| runner µs per tick, median of laps | 14.41 | 17.31 |

Gates (pooled): G1 PASS (B FS 0), G2 PASS (1.000; OPEN/REPLACE/CANCEL_MOVE B 24 = A 24), G3 PASS (0), G4 PASS
(0 HANG), G5 PASS, G6 PASS (B logged no exception), G7 PASS, G8 PASS, P1 PASS (OPEN +0 ticks, runner +2.9 µs),
P2 PASS (40 = 40). Each seed alone also passes every gate. **Class: PASS.**

Notes (not gates):
- Arm A's result on an impossible target depends on the world. On 88011 legacy's explore path could not be computed
  (`calcFailed`), so it took "No known locations … canceling" and the runner read the inactive process as done
  (FS); on 88012 and on the 88001 baseline it explored until the timeout (HANG). Arm B ended both ways as an honest
  failure in about 30 ticks.
- The CANCEL_ACT click is already sent when the stop lands; the server opens the chest afterwards in 8/8 laps of
  both arms, and both arms report UNKNOWN, not CANCELLED. Legacy's rerun then counts that stale menu (FS, 0 use
  packets); B's rerun closes it and opens the chest again with its own click (TS in a median 3 ticks, 1 use packet).
  A new request is a new click, not an automatic retry: nothing is retried inside a request.
- The outcome record runs in both arms (it changes no behaviour), which is why A's rows carry outcomes too: A's CAT
  and impossible-target requests report FAILED internally while the runner, which ignores outcomes with the flag off,
  reports done.
- Tiers: U (`ProcessOutcomeTest`, 7) and L (this table). There is no headless (S) model of container opening, so
  none is claimed; the system under test is never the oracle (the server's menu is).

## Phase 09: capability modularity, the interaction capability's container observation behind a version adapter (2026-11-09, branch `ostinato2/claude-review`, local only)

The task: the smallest production-relevant modularity improvement that demonstrably reduces version-specific
coupling, with existing behaviour, lifecycle semantics and performance preserved. One slice; no framework, registry,
plugin system, protocol or port. Phase start HEAD `6b4f3d049` (tree clean apart from an untracked
`scripts/__pycache__/`). Every Phase 04–08 flag stays at its default (`verifiedGetToBlock` false).

### Audit (written before any code change)

Labels: VERIFIED (read in code, `javap` on the cached jars, or `git show` of the version branches), REPORTED (earlier
phase docs, not re-checked), UNKNOWN.

**Supported versions.** This tree builds Minecraft 26.3 only (`gradle.properties`, Java 25, Mojang names, loader
`fabric`); `versions/` holds only the 26.3 overlay. Other versions are separate branches, not overlays
(`versions/README.md`): `origin/1.21.11`, `origin/1.21.4` (Mojang names) and `origin/1.16.1` (MCP names over 1.16.5,
Java 8, its `build.gradle` line 72). None of them has Phase 08. VERIFIED.

**Architecture map: consumer → capability interface → implementation → version-specific Minecraft API.**

| Capability | Consumer | Interface | Implementation | Version-specific API it touches | Existing isolation |
|---|---|---|---|---|---|
| Navigation | `#goto`, task runner GOTO | `ICustomGoalProcess`, `Goal`, `IPathingBehavior` | `CustomGoalProcess`, `PathingBehavior`, `PathExecutor`, drivers | `LocalPlayer`, client level, key state | Planner: `BlockStateInterface`/`CalculationContext` (headless `SyntheticWorld` exists, BASELINE). Executor: none, client-bound (D2). VERIFIED/REPORTED |
| Physics | kinematic, physics and sim drivers, SimEdge | `PlayerSim.World`, `PlayerState`, `PredictionTolerance` | `PlayerSim`, `ClientWorld` | client world reads; 26.3 constants | `PlayerSim.World` is the adapter; `ClientWorld` the live side (D11, D12). REPORTED |
| Mining | `#mine`, runner MINE | `IMineProcess` (MC types in the signature) | `MineProcess` (722 lines, 8 MC imports) | blocks; the runner counts `ItemStack`s itself | none. VERIFIED |
| Building | `#build`, runner BUILD | `IBuilderProcess` (4 MC imports) | `BuilderProcess` (2006 lines, 17 MC imports) | many | none. VERIFIED |
| Input | every controller | `InputOverrideHandler`, `InputArbiter` (off) | — | key mappings | partial; B6 open. REPORTED |
| **Interaction** | `goto <block>`, runner GET_TO_BLOCK (`BaritoneTaskAdapter`) | `IGetToBlockProcess` + `ProcessOutcome` (Phase 08) | `GetToBlockProcess` (445 lines) | **container menus:** six menu classes, `player.containerMenu`, `closeContainer()` | **none.** VERIFIED |

General adapters that exist: `IPlayerContext` and `IPlayerController` are per-branch copies that wrap *access* but
return the branch's Minecraft types (`LocalPlayer` on 26.3, `ClientPlayerEntity` on 1.16.1; VERIFIED by `git show`).
No adapter for open containers exists. VERIFIED.

**Interaction coupling (VERIFIED; the slice's target):**
1. The consumer reaches into the implementation. `BaritoneTaskAdapter` (the task runner's game side) imports
   `net.minecraft.world.inventory.AbstractContainerMenu`, holds a menu object across ticks, and calls the static
   `GetToBlockProcess.reached(...)` on the implementation class, not the `IGetToBlockProcess` contract it already uses
   for `outcome()`.
2. Phase 08's verified-open rule (a container counts only if this request saw none open while clicking and the menu
   is the block's type; a stale or wrong one is closed) is written inline in `GetToBlockProcess.rightClick()` against
   six menu classes (`AbstractContainerMenu`, `ChestMenu`, `CraftingMenu`, `FurnaceMenu`, `BlastFurnaceMenu`,
   `InventoryMenu`), `player.containerMenu` and `closeContainer()`, spread over `rightClick`, `opens` and `reached`.
   So the rule has no Tier U or S test (Phase 08: "there is no headless model of a container opening"); its only
   evidence is Tier L.
3. These names genuinely vary by version. The legacy "nothing open" check is `containerMenu instanceof InventoryMenu`
   on 26.3, 1.21.11 and 1.21.4, and `openContainer instanceof PlayerContainer` on 1.16.1 (`git show` of each branch's
   `GetToBlockProcess`, line 220). On the cached 1.16.5 MCP jar the Phase 08 menus are `ChestContainer`,
   `WorkbenchContainer`, `FurnaceContainer`, `BlastFurnaceContainer`, and the close is `closeScreen()` (`javap`).
   In 26.3 the five menu classes are disjoint (`ChestMenu` extends `AbstractContainerMenu`; `CraftingMenu` and
   `InventoryMenu` extend `AbstractCraftingMenu`; `FurnaceMenu` and `BlastFurnaceMenu` extend `AbstractFurnaceMenu`),
   so a kind mapping cannot reorder an `instanceof` outcome.

Other container observations exist (`BastionProcess` 5 sites, `InventoryBehavior` 1, `FreecamInventoryHud` 2,
harnesses 7). They are not part of this slice and are not migrated.

**Lifecycle (VERIFIED in `BaritoneTaskAdapter.verify`):** only GET_TO_BLOCK (flag on) is judged by a request outcome;
GOTO re-checks the goal, counted MINE the inventory; BUILD, count-less MINE, FOLLOW, FARM and EXPLORE read inactive as
done. Known limits, out of scope.

### Slice selection

**Chosen: A, the verified interaction capability's container observation.**
- A verified coupling (above); a production consumer (`BaritoneTaskAdapter`) and implementation
  (`GetToBlockProcess`); a crisp postcondition; the Phase 08 live harness and gates re-usable unchanged; and a known
  second-version shape (the 1.16.5 MCP names) to check the boundary against.
- Not a hot path: the click rule runs only on arrival ticks of a container goto, the step check once per step end.

Rejected:
- **B, navigation.** Wide surface (`PathingBehavior`, `PathExecutor`, drivers), B6 input ownership still open, the
  executor is client-bound (D2), and per-tick hot paths. No verified version-coupling defect that a narrow boundary
  would fix.
- **C, a world observation adapter.** The planner's world reads already go through `BlockStateInterface` /
  `CalculationContext`. A broader world port has no single consumer with a verified defect and would sit on hot paths.
- **D, repair an existing adapter.** No container adapter exists to repair, and no verified defect was found in
  `IPlayerContext`/`IPlayerController`.

### Contract (written before implementation)

- `baritone.process.interact.ContainerView` (no Minecraft import, Java 8 syntax): what the player has open.
  `kind()` is `NONE` (no container: the player's own inventory, or no player), `CHEST`, `CRAFTING`, `FURNACE`,
  `BLAST_FURNACE` or `OTHER`; `menu()` is an opaque identity (a new open is a new object; compared by identity only;
  null with no player); `close()` closes it.
- `baritone.process.interact.OpenVerifier` (no Minecraft import, Java 8 syntax): Phase 08's rule, unchanged in
  meaning. Per request: `reset()`; `click(view, want, verified)` returns `WAIT` (nothing open; or, verified, a stale
  or wrong container, which it closes), `OPENED` (a `want` container, opened after this request saw none open while
  clicking) or `OPENED_UNVERIFIED` (flag off: any other container, the legacy "done"). `reached(view, want, before)`:
  a `want` container is open and is not `before`.
- `baritone.process.interact.ClientContainers` (the 26.3 adapter; the only class in the slice that names a menu
  class): implements `ContainerView` over a player supplier; `openedBy(Block)` maps the six container blocks to their
  kind. A 1.16 adapter would replace this file only.
- `IGetToBlockProcess` (the capability contract) gains `openMenu()` (default null) and `reached(target, before)`
  (default **false**: an implementation that cannot observe the postcondition never reports it).
- The runner's GET_TO_BLOCK check reads only `IGetToBlockProcess` and `ProcessOutcome`: the request it started, the
  outcome, then `reached`. Its messages are unchanged.
- Confidence is unchanged: success still rests on the server-opened menu (the client's open menu changes only when
  the server's open-screen packet arrives). No client inference is added.
- No new flag: behaviour, flag off and on, is meant to be identical. The live A/B checks that.

### Implementation (commit `41c008f12`)

- New, in `src/main/java/baritone/process/interact/`: `ContainerView` (contract), `OpenVerifier` (Phase 08's rule),
  `ClientContainers` (the 26.3 adapter: the only class in the slice that names a menu class, `containerMenu` or
  `closeContainer()`).
- `GetToBlockProcess`: six menu imports, the inline rule, the `sawClosed` field, the static `opens` and the static
  `reached` removed; `rightClick` calls `OpenVerifier.click`; `isContainer` is `openedBy(block) != NONE`; it
  implements the two new contract methods.
- `IGetToBlockProcess`: `openMenu()` (default null), `reached(target, before)` (default false).
- `BaritoneTaskAdapter`: no Minecraft menu import and no `baritone.process` import; the GET_TO_BLOCK verdict is a
  static function of the request id, the outcome and a postcondition supplier, with Phase 08's messages.
- Behaviour-preserving choices: the same branch order (a verified open is checked before the flag), a null client
  menu still matches nothing (`OTHER`), the same six container blocks, the same `println`.
- One intended difference: with no player when a verified step starts, the runner's remembered menu is now null
  (Phase 08 kept whatever an earlier step left there). Not reachable in the harness, which always has a player.

### Tier U and S evidence (VERIFIED, `41c008f12`)

- `OpenVerifierTest` (11), `ClientContainersTest` (3, Minecraft bootstrapped), `InteractBoundaryTest` (3, source
  scan), `GetToBlockVerdictTest` (5); with `ProcessOutcomeTest` (7) and `TaskRunnerTest` (10): 39/39 pass.
  - Covered: our open; a stale container left open before the request, closed then reopened; a wrong kind never
    counts; legacy (flag off) takes any container; a refused open waits and never closes; `reset` per request;
    an adapter that throws propagates out of the rule (the process's existing tick catch is unchanged); the
    postcondition with a new, the same, a wrong or no menu; no player; a stale request id; FAILED, CANCELLED, UNKNOWN
    and RUNNING with their reasons; a throwing postcondition check; an implementation using the contract defaults
    never passes.
  - The 26.3 adapter: exactly six container blocks in the whole block registry; each menu class to its kind
    (`SmokerMenu` and `BeaconMenu` are `OTHER`; null is `OTHER`).
  - Allocation: `OpenVerifier.click` 11.4 ns/call, 0.000 bytes/call (`ThreadMXBean` allocated bytes, 10^6 calls,
    after warm-up; Tier U, a microbenchmark only).
- Full suite at `41c008f12`: 1642 tests, 1 failed (the pre-existing B5 `SecondoTest`), 24 skipped
  (Phase 08: 1620; the 22 new tests are the difference).
- Second version, compile only (Tier U): `tools/phase09/compile-1161.sh`, output `raw/phase09/compile-1161.txt`.
  The unchanged `ContainerView` and `OpenVerifier` plus a 1.16 adapter (`tools/phase09/v1161/ClientContainers.java`,
  MCP names) compile with `javac --release 8` against the cached 1.16.5 MCP jar; the 26.3 adapter against that jar
  fails (control). Also found: Phase 08's `ProcessOutcome` is a record and does not compile at Java 8, so a 1.16
  back-port of the outcome needs a class form (recorded, not done).

### Dev smoke (seed 88001, reps 1; dev data, not evidence)

- P09 build, `raw/phase09/p09-dev-88001-lines.txt`, 18 laps. All Phase 08 gates pass on it. Arm B: possible
  6/6 TS, impossible 4/4 TF, FS 0, HANG 0, no leak, 10/10 self-reports agree; TF medians CAT 55, PREEMPT 20,
  SEALED 30, VANISH 31 ticks (Phase 08 held-out: the same); OPEN 37 ticks. Arm A as legacy: FS 4, HANG 2.
- Exceptions: offline auth and Realms (every run), and one arm-A `BlockOptionalMetaLookup` NPE (the legacy stale
  rescan Phase 08 recorded; flag off only). Nothing new.
- Comparator calibration (`tools/phase09/parity.py`, on Phase 08's own held-out runs, so no Phase 09 held-out
  data): seed 88011 against itself gives 80 rows, 0 differences; seed 88011 against 88012 (different worlds, same
  build) also gives 0 class or outcome differences, with arm A's VANISH and SEALED going FS on one seed and HANG on
  the other (hence the C2 allowance below), and every limit L1–L5 within bounds. Two keying bugs were fixed
  during this calibration (REPEAT's two steps collided; a HANG row has no step).

### Pre-registration: confirmatory live A/B parity of the Phase 09 refactor (committed before any held-out run)

Fixed after the unit evidence and one dev smoke on seed 88001, before any held-out data. Not changed after it.

**Question.** Does moving container observation behind the adapter preserve behaviour, lifecycle semantics and
performance, in both flag states, on the real consumer (the production `TaskRunner` + `BaritoneTaskAdapter`)?

**Builds.**
- Baseline: the Phase 08 build, `6b4f3d049` (checked out whole; the harness is identical in both builds).
- Experimental: the Phase 09 build, `41c008f12`.
- Within each build the Phase 08 harness runs both of its arms: A (`verifiedGetToBlock` false, production default)
  and B (true, set only inside the harness lap). No other setting differs.

**Instrument (Tier L).** Phase 08's `InteractLiveTest`, unchanged: the oracle is the integrated server's
`ServerPlayer.containerMenu`; the nine scenarios, the classes and the 600-tick timeout are as in Phase 08.

**Held-out seeds and order.** Seeds **89011** and **89012** (never run), `reps=4`, 72 laps per run. Order:
89011 baseline, 89011 P09, 89012 baseline, 89012 P09. Command as Phase 08's (client stopped between runs, second
monitor, muted, no mouse grab), with `-Dostinato.seed=<seed>`. Extract each with `interact_ab.py --extract` into
`raw/phase09/p09-{base,exp}-<seed>-lines.txt`. Read with `python docs/ostinato2/tools/phase09/parity.py
<base-89011>,<exp-89011> <base-89012>,<exp-89012>`, which computes the criteria exactly as written here.

**Correctness criteria (both seeds pooled).**
- **C1.** The P09 build passes Phase 08's gates G1–G8, P1 and P2, computed by `interact_ab.py` unchanged (its
  class: FAIL if G1, G4, G5 or G6 fails, PASS if all pass, else PARTIAL). The baseline build's class is printed
  next to it for reference.
- **C2, parity.** For every (scenario, arm, rep, part) row, the oracle class is the same in both builds. The one
  allowance: arm A on VANISH and SEALED may be FS in one build and HANG in the other (legacy never notices that the
  target cannot open; Phase 08 saw both). In arm B the self-reported outcome (state and reason, not the request
  number) is the same. No row is missing from either build. Stop rows' outcomes (CANCELLED or UNKNOWN by when the
  click lands) are reported, not compared; C1's G7 covers their honesty.
- **S, safety.** In both arms of the P09 build: no key held or process active after a stop or a lap, no death.

**Performance limits (both seeds pooled; P09 minus baseline).**
- L1: OPEN TS median ticks, |Δ| ≤ 2, in each arm.
- L2: arm B TF median ticks on CAT, PREEMPT, SEALED and VANISH (failure latency), Δ ≤ +3 each.
- L3: arm B CANCEL_ACT rerun TS median ticks (recovery after a cancel), Δ ≤ +2.
- L4: arm B `runnerUs` median (task-runner time per lap, the consumer's dispatch overhead), Δ ≤ +5 µs.
- L5: arm B CAT use packets (clicks on a refused open) ≤ baseline.
- Memory: two small objects per bot (the adapter and the rule), no per-tick allocation in the rule (Tier U above).
  Reported, not gated.

**Modularity evidence (judged from the commit, not the runs).** The menu API is named in one file of the slice
(`ClientContainers`) where it was in two (`GetToBlockProcess`, `BaritoneTaskAdapter`); the contract and rule compile
unchanged against a second version's names; the boundary test keeps it that way.

**Classification.**
- FAIL: C1 hard gates (G1, G4, G5, G6) fail, C2 fails, or S fails. The refactor is then reverted, not tuned.
- PASS: C1 all pass, C2 and S pass, L1–L5 all pass, and the modularity evidence holds.
- PARTIAL: correctness holds but a soft gate (G2, G3, G7, G8, P1, P2) or a performance limit fails.
- NO CHANGE JUSTIFIED: not applicable once the code is committed; reserved for an aborted slice.

**Stop conditions.**
- No seeds, repetitions or builds are added after results are seen; no run is repeated to change an outcome.
- A run that does not complete (gradle error, client crash, harness setup failure) is rerun once with the same seed
  and build. A second failure is a blocker and Tier L is not claimed for that pair.
- A death or held key in the first P09 run stops the confirmatory runs (S FAIL).
- Diagnosis of any difference is bounded to reading the raw rows; no redesign and rerun.

### Phase 09 results: confirmatory held-out live parity A/B (VERIFIED, Tier L)

Run after the pre-registration commit `65846561c`, exactly as registered: seeds 89011 then 89012, `reps=4`, 72 laps
per run, in the order base, P09, base, P09. Every run completed on its first attempt (no rerun). Raw:
`raw/phase09/p09-{base,exp}-{89011,89012}-lines.txt`; the comparator's full output: `raw/phase09/p09-parity.txt`.

**Correctness.**
- **C1: PASS.** The P09 build passes every Phase 08 gate pooled (G1–G8, P1, P2). Arm B: possible rows 48/48 TS,
  impossible rows 32/32 TF, FS 0, HANG 0, false failures 0, 80/80 terminal self-reports agree with the oracle, no
  exception. The baseline build's reference class is PASS with the same counts.
- **C2: PASS.** 160/160 rows have the same oracle class in both builds (80 per seed), with no missing row; the
  arm-A FS/HANG allowance was not needed (0 swaps). Every arm-B outcome (state and reason) is identical. The 32 stop
  rows' outcomes are also identical (reported only).
- **S: PASS.** In both arms of the P09 build: no key held or process active one tick or 40 ticks after a stop, or at
  any lap end; no death.
- Legacy behaviour (arm A, flag off) is preserved exactly, false successes included: FS 40 and HANG 8 in both builds.
- Exceptions: arm A's legacy stale-rescan NPE in both builds (base 18, P09 10; the count follows rescan timing and was
  9 per run in Phase 08); none in arm B in either build.

**Performance (P09 minus baseline, pooled).**

| limit | baseline | P09 | Δ | bound | result |
|---|---|---|---|---|---|
| L1 OPEN TS median, arm A (ticks) | 38.0 | 37.0 | −1.0 | ±2 | PASS |
| L1 OPEN TS median, arm B (ticks) | 37.0 | 37.0 | 0.0 | ±2 | PASS |
| L2 B TF median CAT / PREEMPT / SEALED / VANISH (ticks) | 55 / 20 / 30 / 31 | 55 / 20 / 30 / 30.5 | 0 / 0 / 0 / −0.5 | ≤ +3 | PASS |
| L3 B CANCEL_ACT rerun TS median (ticks) | 3.0 | 3.0 | 0.0 | ≤ +2 | PASS |
| L4 B `runnerUs` median (µs) | 16.52 | 18.62 | +2.09 | ≤ +5 | PASS |
| L5 B CAT use packets | 40 | 40 | 0 | ≤ base | PASS |

- `runnerUs` is, per lap, the task runner's mean wall time per tick (`InteractLiveTest` line 407); the table takes the
  median over laps. Per seed, arm B: 16.88 → 18.42 and 15.50 → 18.72 µs. Arm A, whose runner path the slice touches
  only on GET_TO_BLOCK step start: 10.45 → 11.97 and 10.63 → 10.55 µs. Phase 08's two held-out seeds on one build
  differed by 0.17 µs in B and 3.4 µs in A, so this is at the edge of run-to-run noise. Not attributed. Inside the
  bound either way.
- Cancel latency: every stop has no key and no active process one tick later, in both builds (G5, S).
- Allocation: the rule allocates nothing per call (Tier U); the runner's verdict allocates one lambda per step end
  (one per GET_TO_BLOCK step, not per tick). Memory: two small objects per bot. Not measured live.

**Modularity evidence (from the commit `41c008f12`; VERIFIED).**
- The menu API (`net.minecraft.world.inventory`, `containerMenu`, `closeContainer(`) appears in one file of the slice,
  `ClientContainers`, where it was in two (`GetToBlockProcess`: 6 imports and 3 sites; `BaritoneTaskAdapter`: 1
  import and 1 site). `InteractBoundaryTest` fails if that regresses.
- The runner imports no `baritone.process` class; it judges the step through `IGetToBlockProcess` and
  `ProcessOutcome` only.
- The rule (`OpenVerifier`) and contract (`ContainerView`) compile unchanged against the 1.16.5 MCP names at Java 8,
  with a 1.16 adapter (compile only; no 1.16 client was run). Found: `ProcessOutcome` (a record) is not Java 8 source.

**Classification: PASS** (C1, C2 and S pass; L1–L5 pass; the modularity evidence holds). Tiers: U (the unit tests
and the second-version compile check) and L (this A/B); no Tier S, as there is still no headless container model. No default changed; `verifiedGetToBlock` stays false.

## Phase 10: final validation, feature disposition, production readiness (2026-11-09, branch `ostinato2/claude-review`, local only)

Phase start HEAD `bda049360` (tree clean apart from an untracked `scripts/__pycache__/`). No production default is
changed in this phase; no experimental flag is enabled.

### Production baseline at phase start (VERIFIED)

- Full unit suite at `bda049360`: 1642 tests, **1 failed** (`SecondoTest.plannedApisAreAbsentOnThisBranchAndInvokeIsANoop`,
  B5), 24 skipped (`.\gradlew.bat --offline --no-daemon --max-workers=2 cleanTest test -x nativeHost`, 4 min 41 s).
- Defaults read from `Settings.java` at `bda049360`: every Ostinato 2.0 experiment is off (`verifiedGetToBlock`,
  `unifiedExecutor`, `pathingStateIdCache`, `simEdgeSearch`, `predictionTolerance`, `inputArbitration`,
  `latticeExperiment`, `pathingHierarchical`, `pathDecisionMatrix`); `travelStack` `auto`, `kinematicTravel` and
  `simTravel` true, `swimInWater` true, `exploreForBlocks` true, `rightClickContainerOnArrival` true.

### Dev runs (exploratory, seed 90001; not evidence for any gate)

New harness `InteractTerrainLiveTest` (`-Dostinato.interactterrain=true`): the Phase 08 navigate → interact → verify
check on the generated terrain around the spawn of a fresh Peaceful world. A box of ±26 blocks (surface −8 .. +14) is
copied at setup with every container in it removed, and restored before each lap; each lap places two chests, two
furnaces, a blast furnace, an ender chest and a crafting table on the surface at fixed offsets from the start. Every
container block entity in the loaded chunks outside the box is removed each lap. Arms: A = `verifiedGetToBlock` off,
B = on, order alternating by repetition and scenario. Oracle: the server-side player's menu (chests by block entity
position; furnace, blast furnace, table and ender chest by menu kind and the player within 8 blocks of the fixture;
the box holds no other instance of those). Scenarios: CHEST2, FURNACE2, BLAST, ENDER, TABLE, MENU_OPEN (a non-target
chest-type menu open at start; target furnace), VANISH_ONE (the first fixture chest the player comes within 3.5 blocks
of turns to stone), CAT_ONE (a sitting cat on the nearer chest), LAG_SHORT / LAG_LONG (the server thread sleeps 400 /
1600 ms when right click is first held), CANCEL_MOVE, CANCEL_ACT, PREEMPT, REPLACE (chest list replaced by a furnace
list 15 ticks in), MAGMA (no task: the idle player on magma 10 ticks, then moved off; keys read 20 ticks later).

- Dev run 1 (`raw/phase10/p10-terrain-dev1-90001-lines.txt`, 30 laps): harness defects found and fixed before
  pre-registration: the ender-chest oracle branch (never exercised in Phase 08, where the ender chest was always
  vanished or sealed) read every ender open as "otherchest"; natural chests 100+ blocks away were targets (now cleared
  each lap); `opened=` was not reset between laps; HANG rows had no trail. Findings kept: MAGMA STUCK 2/2 (below),
  LAG_LONG late open after a B TIMEOUT, CAT_ONE no retry on the second chest, REPLACE both arms stalled on one ledge.
- Dev run 2 (`raw/phase10/p10-terrain-dev2-90001-lines.txt`, 10 laps, after the fixes and the AirProcess change):
  ender oracle works (B TS); MAGMA CLEAR 2/2. VANISH_ONE: neither arm knew the second chest. Cause, read in code:
  `GetToBlockProcess.rescan` uses `MineProcess.searchWorld`, whose `prune` keeps only blocks that are
  `plausibleToBreak`, and `MovementHelper.avoidBreaking` rejects a block with a liquid beside it or a falling block on
  top. An openable chest beside water is therefore never a target of `goto chest`, in either arm (pre-existing,
  upstream; not changed). The harness now logs, per lap, what that search returns (`known=`) and, at setup, which
  fixtures touch a fluid (`wet=`).

### Pre-registration (committed before any held-out run; thresholds are not changed after seeing data)

**Run V: `verifiedGetToBlock` on natural terrain.** Seeds **90011** and **90012**, 3 reps, all 15 scenarios, both
arms: 90 laps per seed. Build: the commit that adds this section. Command (client stopped before, second monitor,
muted, no mouse grab):

```
$env:JAVA_TOOL_OPTIONS='-Dostinato.noMouseGrab=true -Dostinato.windowPos=2600,40 -Dostinato.testHarness=true -Dostinato.interactterrain=true -Dostinato.interactterrain.exit=true -Dostinato.interactterrain.reps=3 -Dostinato.seed=<90011|90012>'
.\gradlew.bat --offline --no-daemon "-Dorg.gradle.jvmargs=-Xmx1536M" :fabric:runClient -PclientXmx=1536M *> p10-terrain-<seed>.log
```

Analysis: `python docs/ostinato2/tools/phase10/terrain_summary.py <lines 90011> <lines 90012>` (gates over both
seeds together; per-seed output reported too).

- Positive set: main rows of CHEST2, FURNACE2, BLAST, ENDER, TABLE, MENU_OPEN, VANISH_ONE, LAG_SHORT and rerun rows of
  CANCEL_MOVE, CANCEL_ACT. A pair (seed, rep, scenario) is **eligible** when the lap-start `known=` lists at least one
  fixture of the target inside the box in both arms (two chests for VANISH_ONE).
- **T1 (hard): arm B has 0 false successes (FS) over every verdict row of both seeds.**
- **T2:** on eligible positive pairs, B TS ≥ A TS − 2 per seed (−4 over both), and at least 12 eligible pairs per
  seed; fewer eligible pairs is a T2 FAIL (insufficient evidence), not a pass.
- **T3:** every stop row has `keys1=-`, `activeEnd=false`, `keysEnd=-`; every B stop outcome is CANCELLED or UNKNOWN
  with OWNERSHIP_LOST; every B PREEMPT row ends CANCELLED/OWNERSHIP_LOST.
- **T4:** no B HANG in a (seed, rep, scenario, part) where A did not hang.
- **M1 (the B6 fix):** every MAGMA row is CLEAR (6 rows per seed). Before the fix: STUCK 2/2 (dev run 1).
- Diagnostics, no gate: CAT_ONE (expected B TF, no retry on the other chest), LAG_LONG (expected B TF/TIMEOUT; the
  late server open is counted), REPLACE, per-scenario medians, `runnerUs` median (laps 0–1 excluded), A's FS count.
- **Classification of `verifiedGetToBlock`:** PROMOTION CANDIDATE if T1–T4 pass and no Critical safety finding is
  attributable to the flag; otherwise DEFER. Either way it stays default false; the owner decides.
- **Stop rules:** each seed runs once. A crash or failed world creation allows one fresh re-run of that seed, reported
  with the crash. A DIED row is excluded from T2 and reported. No other re-run, no threshold change.

**Run P: production baseline lap course.** `LegatoLiveTest` with the new `-Dostinato.legatotest.arms=production`: the
`SimEdgeCourse` (slalom, ridges, bumps, steps), all four arms on production defaults (no experiment switched, travel
stack left at its default), so B, C and D against A are A/A noise controls. Seed **90021**, 6 reps, 96 laps.

```
$env:JAVA_TOOL_OPTIONS='-Dostinato.noMouseGrab=true -Dostinato.windowPos=2600,40 -Dostinato.testHarness=true -Dostinato.tickClock=true -Dostinato.legatotest=true -Dostinato.legatotest.exit=true -Dostinato.legatotest.arms=production -Dostinato.legatotest.reps=6 -Dostinato.seed=90021'
```

- Reported (no gate on the production numbers themselves): laps reached per arm, median and mean lap ticks, the
  harness's paired-leg ratios, deaths, stuck-key lines, faults.
- **P1 (harness sanity):** every A/A paired-leg median ratio within 0.95–1.05. A miss says the course cannot resolve a
  5% change on this machine; it is reported, not a production verdict.
- Stop rules as Run V.

**Headless (Tier U/S):** the full unit suite after this phase's changes (it includes `PathBenchTest`'s golden check),
0 failures required for "suite green".

### Run V results (held out, Tier L; build `d807a5240`; raw `raw/phase10/p10-terrain-9001{1,2}-lines.txt`, summaries `p10-terrain-*-summary.txt`)

Each seed ran once, with no crash and no re-run. Seed 90011 spawned on low ground (origin y 64, start 21:51, 16 min); seed
90012 in hills (origin y 101, 24 min). 0 deaths. `wet=[]` on both seeds. Every lap-start `known=` listed the
target in the box apart from 3 CANCEL_ACT pairs on 90012 (excluded from T2 as pre-registered).

| gate | 90011 | 90012 | both |
|---|---|---|---|
| T1 B false successes = 0 | **PASS** (0; A 15) | **PASS** (0; A 9) | **PASS** (0; A 24) |
| T2 B TS ≥ A TS − 2 per seed, ≥ 12 eligible pairs | **PASS** (28 vs 22, 30 pairs) | **FAIL** (16 vs 19, 27 pairs) | 44 vs 41 (57 pairs) |
| T3 bounded release on stop and preempt | **PASS** | **FAIL** (6 stop rows `keys1=MOVE_FORWARD,SPRINT`) | FAIL |
| T4 no B-only HANG | **PASS** | **FAIL** (12 B-only hangs) | FAIL |
| M1 MAGMA all CLEAR | **PASS** (6/6) | **PASS** (6/6) | **PASS** (12/12) |

**Pre-registered classification of `verifiedGetToBlock`: DEFER** (T2, T3 and T4 fail on seed 90012). It stays default false.

Diagnosis of the failures (read from the raw rows, after the classification; it changes no gate):
- **T4 and T2 (90012), a navigation stall in both arms.** 16 of the seed's 23 HANG rows (A 11, B 12) stand still
  with no key held and the outcome RUNNING at the same block, -18,96,7, three blocks from the chest at -21,96,8 (goal
  `GoalGetToBlock{-21,96,8}`): A in CAT_ONE ×3, LAG_SHORT, LAG_LONG ×2, CANCEL_ACT ×2; B in CHEST2 ×2, CAT_ONE,
  LAG_SHORT ×2, LAG_LONG, CANCEL_MOVE ×2, CANCEL_ACT. Three more stand still in the same way next to the
  ender chest (B), the blast furnace (B) and the REPLACE furnace (A and B, at -18,101,-15). Whether a lap goes to
  that chest depends on the arm's (unchanged) nearest-target choice and where the last lap ended, so the B-only
  rows are pairs where A happened to choose the other chest, or stalled in another part. Both arms stall on the same
  three blocks. Cause UNKNOWN: no path message was logged and the world was not kept. The server logged "standing on
  air - force-sending blocks below" 64 times on 90012 and 48 on 90011. The flag does not bound a navigation stall
  (Phase 08 limitation, confirmed).
- **T3 (90012), a one-tick release lag in both arms.** All 6 CANCEL_MOVE stop rows (3 A, 3 B) still had
  `MOVE_FORWARD,SPRINT` one tick after the stop, and none at the end (`keysEnd=-`, `activeEnd=false`). That is
  `cancelSegmentIfSafe()` waiting for a safe point mid-movement on sloped ground; it is arm-independent and not
  a leak. On 90011 (flat) every stop row was clean. Every B stop outcome was CANCELLED or UNKNOWN with
  OWNERSHIP_LOST, and every B PREEMPT ended CANCELLED/OWNERSHIP_LOST (6/6).
- **VANISH_ONE.** 90011: both arms 1 TS, 2 HANG. With the fixture gone, both walk toward natural chests remembered
  from Baritone's cache far outside the box (371,-18,226; 407,-48,65). 90012: B 3/3 FAILED/WORLD_CHANGED; A 3/3 HANG
  running away (`GoalRunAwayFrom`, explore).
- **B's honest failures (16):** CAT_ONE 3 FAILED/TIMEOUT (no retry on the other chest), LAG_LONG 4 FAILED/TIMEOUT
  (the server then opened the chest after the verdict in all 4, as A's 4 did: a TIMEOUT here is really UNKNOWN),
  PREEMPT 6 CANCELLED/OWNERSHIP_LOST, VANISH_ONE 3 FAILED/WORLD_CHANGED.
- **A's false successes (24):** MENU_OPEN 6, PREEMPT 6, LAG_LONG 4, CAT_ONE 3, CANCEL_ACT rerun 4 (each counted the
  chest that the cancelled click opened), CHEST2 1.
- Cost: runner median 7.85 µs per tick in A, 8.73 in B (both seeds, laps 0–1 excluded).
- No verdict row kept a key forced apart from HANG rows (where the request was still running).

### Run P results (held out, Tier L; build `d807a5240`; raw `raw/phase10/p10-legato-90021-lines.txt`)

One run, no crash, 29 min. `SimEdgeCourse`, seed 90021, 6 reps, four arms all on production defaults
(`travelStack=auto`, kinematic on).

| arm | laps | reached | median ticks | mean ticks | tick p50 (µs, median of laps) | tick p95 (µs) | faults | cancels |
|---|---|---|---|---|---|---|---|---|
| A | 24 | 24 | 298 | 302.3 | 5527 | 7641 | 0 | 0 |
| B | 24 | 24 | 298 | 300.9 | 5491 | 7651 | 0 | 0 |
| C | 24 | 24 | 296 | 300.3 | 5517 | 7706 | 0 | 0 |
| D | 24 | 24 | 296 | 300.4 | 5462 | 7632 | 0 | 0 |

Per leg (arm A median): slalom 340, ridges 271, bumps 298, steps 293 ticks. `physPlans=70`, `driftReplans=43`,
no SimEdge or Legato search (both off), **keys stuck after 0 laps**, no "keys held" line, no death, no course rebuild.

**P1 (harness sanity): PASS.** All 16 A/A paired-leg median ratios lie in 0.996–1.004 (B, C, D against A per leg:
1.003, 1.000, 1.003; 0.996, 1.004, 0.996; 1.000 ×3; 1.000, 0.997, 1.000; D against C: 1.000, 1.000, 1.000, 1.003).
Single pairs spread 0.89–1.06, so one lap is noisy, but the per-leg medians of six can resolve a 5% change on this machine.

### SURFACE diagnostic (after Runs V and P; not pre-registered, no gate; raw `raw/phase10/p10-surface-90031-lines.txt`)

Question: does `AirProcess` leave JUMP forced on an idle player after surfacing? Read in code, it holds JUMP at the
surface under REQUEST_PAUSE, and `secretInternalSegmentCancel()` clears keys only while a path is current.
New non-default scenario `SURFACE` (`-Dostinato.interactterrain.only=SURFACE`, excluded from the default list so
Run V's scenario set is unchanged): the idle player at the bottom of a 3x3 pool 7 deep, open to the sky, 90 air; keys
read 40 ticks after the air bar is full, and again 20 ticks after a move onto dry ground. Seed 90031, 6 laps, the
build of this section (harness change only).

Result: **CLEAR 6/6.** In every lap AirProcess took control and JUMP was forced while surfacing
(`surfacing=true jumpSeen=true`); the bar was full at lap tick 120–169, and 40 ticks later no key was
forced and `KeyboardInput` was back, in the water and on land. The surfacing path stays current through the pause,
so the release on `isActive()` → false goes through `secretInternalSegmentCancel()`. **No change was made**: the
defect was not demonstrated. The finding moves to Deferred (no reproduction; a pocket with no path current is the
only remaining way, not staged).
