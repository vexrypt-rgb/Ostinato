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
