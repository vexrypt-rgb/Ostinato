# Hyperopt baseline (Phase 1)

Measured on this machine. Not a re-run of the historical `docs/pathbench/` numbers.

## Environment

| Item | Value |
|---|---|
| Machine | 11th Gen Intel i5-1145G7 @ 2.60 GHz, 4 cores / 8 threads, 15.8 GB RAM, Windows |
| JVM | Temurin 25.0.4.1+1 (Eclipse Adoptium), `JAVA_HOME` set to that JDK for Gradle |
| Tree | `perf/hyperopt` at `67e12b96e`, Minecraft 26.3, Fabric. Gradle reported `1.26.0-1-g67e12b96e` before local edits |
| Workload | PathBench: 5 terrains × SHORT/MEDIUM/LONG × 4 queries, seed 1234, 10 s timeout |
| Finder | `OSTINATO_BENCH_FINDER=wa:2` (shipped far-segment weight). Bench default `astar` was not used |
| Warm-up | One untimed pass, then 5 timed reps |
| Flags | `fastMode` and `piCheapPathing` left at defaults (off). Hierarchy, tick-accurate costs, and momentum costs left off |
| Command | `gradlew --no-daemon --max-workers=2 --no-build-cache -x nativeHost cleanTest test --tests baritone.pathing.calc.PathBenchTest.benchmark` |

`OSTINATO_BENCH*` is not a Gradle task input. A second `test` run can restore `:test FROM-CACHE` and skip the work. Every timed run used `cleanTest` and `--no-build-cache`.

## Clean wall-clock baseline

Label `hyperopt-base`. Production Java as of `67e12b96e`. `PathBench.run` called `calculate0` directly and did **not** claim the mining-duration memo. Production `calculate()` does claim it, so this baseline over-charges mining cost relative to the game. It is still the clean wall-clock record of that harness.

| Terrain | Short ms | Medium ms | Long ms |
|---|---|---|---|
| FLAT | 0.3 | 1.6 | 3.2 |
| HILLS | 2.9 | 19.7 | 21.9 |
| CAVES | 1.3 | 117.0 | 99.1 |
| WATER | 0.3 | 1.9 | 4.9 |
| BREAK_PLACE | 72.3 | 2429.7 | 4275.7 (1 fail) |

Nodes (short / medium / long): FLAT 42 / 222 / 473, HILLS 338 / 3692 / 3646, CAVES 244 / 25206 / 19420, WATER 36 / 308 / 811, BREAK_PLACE 13624 / 413837 / 711851.

Aggregate: 179156 nodes/s, total 187336 ms, 1359 MB allocated, peak heap 323 MB, mean path length 255.1. All queries: median 7.1 ms, p95 4275.7 ms, 1 of 60 failed, 5 reps. Score: failure 1.7%, mean travel 1383.5 ticks, mean time-to-arrive 1392.8 ticks.

Per node on this run is about 5.6 µs (`1e6 / 179156`). BREAK_PLACE medium allocated 13.6 MB for 413837 nodes, about 33 bytes/node. Allocation is not the hot problem on `wa:2`. CPU is movement costing.

Artifact: `build/pathbench/hyperopt-base.md` (plus `-raw.csv` and `-fingerprint.txt`). Gitignored.

## Profile, mining memo not claimed

Label `hyperopt-profile`. Same reverted source, MEDIUM only, `wa:2`, 2 reps, JFR `ExecutionSample` at 5 ms. Not comparable to the 60-query totals.

Top samples: `getMiningDurationTicks0` 12.95%, `StrictMath.hypot` 12.90%, `AStarPathFinder.calculate0` 11.74%, `BinaryHeapOpenSet.siftUp` 7.28%, `MovementHelper.avoidAdjacentBreaking` 6.07%, `MovementDiagonal.cost` 4.70%. The hypot stack was entirely `LineWalk.ticks` for the eight 2-by-1 offsets (`hypot(2, 1)`). JFR over-attributes safepoint-polling native calls. Removing that call did not change wall time (see the queue).

## Profile after the harness claims the mining memo

`PathBench.run` now calls `claimSearchCaches` / `releaseSearchCaches` around `calculate0`, matching production `calculate()`. Label `hyperopt-claimed`: MEDIUM only, `wa:2`, 2 reps. Golden costs still matched. This is a measurement alignment, not a game speedup.

Aggregate for that slice only: 194579 nodes/s, total 26825 ms, 162 MB, 0 of 20 failed, median 8.3 ms, p95 2974.0 ms. BREAK_PLACE medium median 2210.1 ms, 413837 nodes, cost 1925.6, 13.6 MB. Do not compare these totals to `hyperopt-base`.

Top samples with the claim installed: `canWalkOnPosition` 16.75%, `getMiningDurationTicks` 13.49%, `calculate0` 11.19%, `siftUp` 8.14%, `MovementAscend.cost` 6.71%, `MovementSwim.cost` 4.02%, `MovementDiagonal.cost` 2.90%, `getCollisionShape` 1.05%. `hypot` left the top 25.

Later line counts on the same dump: all 963 top-frame `getMiningDurationTicks` samples are line 787, the call into `getMiningDurationTicks0` (the miss). None are the hit return. All 581 `siftUp` samples are the while back-edge. `calculate0` piles on line 229 (530, the dynamic world-border check) and line 210 (240, `moves.apply`). `MovementAscend.cost` piles on line 120 (412 of 479, the water climb-out test). A single line is not wall time until an A/B says so. `canWalkOnPosition` and `siftUp` already failed that test.

## Profile with E7 kept

Label `hyperopt-e7profile`: the kept move filter, mining claim on, MEDIUM only, `wa:2`, 2 reps. `BUILD SUCCESSFUL` in 1m 37s. 3448 execution samples, 3326 of those stacks include `calculate0`. Not a speed number. The pre-E7 ranking above still includes the families E7 skips.

Top methods: `StrictMath.hypot` 12.33% (425, all line 2202 inside `Math.hypot`), `siftUp` 11.75% (405, all line 158; 352 from `insert`, 53 from `update`), `getMiningDurationTicks0` 8.99% (310, of which 291 are line 802, the call to `breakCostMultiplierAt`), `calculate0` 7.63% (263, of which 188 are line 227, `moves.apply`, and 51 are line 246, `entirelyContains`), `PrecomputedData.canWalkOn` 5.97% (206, all line 81, the MAYBE call into `canWalkOnPosition`).

`MovementAscend.cost` left the top cluster. The world-border check fell from 530 samples to 51. `factorCellY` (46 samples) is `MovementAscend` calling `MovementParkour.factorBlock` for the honey check, not a parkour move that escaped the filter. `shouldAvoidBreaking` is not in the top 25.

## What this baseline is not

- No live Minecraft client. Main-thread tick cost, navigation success in a world, combat decision latency, and PPE error were not measured.
- PPE, Grace Note, Bombard, and Scherzo are not on this branch.
- The earlier `hyperopt-off` run (163787 nodes/s) still had a volatile block-cache check on the miss path. It is not the pure baseline.
- Historical 5.5× versus stock Baritone, and the Soprano per-node gap, are reported in `docs/pathbench/THREE_WAY.md`. They were not re-run.
