# Three-way pathfinding bench: stock Baritone vs Soprano vs Ostinato

This runs the same 60 path queries, over the same generated terrain, through the A* of three Baritone lineages:

| fork | source | commit |
|---|---|---|
| **baritone** | stock `cabaletta/baritone`, 26.3 branch | `25111da` plus [`three-way/stock/headless.patch`](three-way/stock/headless.patch) (headless constructors only) |
| **soprano** | `AverWasTaken/soprano`, 26.3 branch | `fbee69e`, unmodified |
| **ostinato-astar** | Ostinato `main` | `f048ece3`, the bare 7-argument A* (the same call the other two make) |
| **ostinato-shipped** | Ostinato `main` | `f048ece3`, the A* plus the far-segment weight `PathingBehavior` passes by default |

`ostinato-astar` is the like-for-like engine comparison. `ostinato-shipped` is what a player actually gets.

## Results

All numbers come from one machine with JDK 25.0.4: 60 queries, 3 timed reps after 1 warm-up pass, and a 10 s timeout. Times are per-query medians over the reps. Moves and blocks are summed over the 56 queries that every config solved.

| config | solved | median ms | p95 ms | total ms | median nodes | median MB alloc | moves | blocks walked |
|---|---|---|---|---|---|---|---|---|
| baritone | 56/60 | 83.2 | 10000 | 77642 | 22004 | 12.5 | 14487 | 15922 |
| soprano | 57/60 | 67.9 | 8359 | 62228 | 21457 | 4.5 | 14461 | 15903 |
| ostinato-astar | 56/60 | 128.9 | 10011 | 82474 | 21503 | 7.9 | 13224 | 15633 |
| **ostinato-shipped** | **59/60** | **6.6** | **4580** | **39476** | **1233** | **1.3** | 13458 | 16008 |

Paired against stock on the 56 common queries (geometric mean of per-query speedups):

| config | speedup vs baritone | faster on | moves | blocks |
|---|---|---|---|---|
| soprano | 1.30x | 54 / 56 | -0.2% | -0.1% |
| ostinato-astar | 1.23x | 16 / 56 | -8.7% | -1.8% |
| ostinato-shipped | **5.49x** | 38 / 56 | -7.1% | +0.5% |

Per terrain (sum of per-query median ms, and failures):

| terrain | baritone | soprano | ostinato-astar | ostinato-shipped |
|---|---|---|---|---|
| FLAT | 292 ms | 220 ms | 42 ms | 21 ms |
| HILLS | 15709 ms | 9797 ms | 17520 ms | 498 ms |
| CAVES | 1711 ms | 1554 ms | 2226 ms | 2365 ms |
| WATER | 5066 ms | 3273 ms | 5905 ms | 42 ms |
| BREAK_PLACE | 54863 ms, 4 fail | 47384 ms, 3 fail | 56781 ms, 4 fail | 36550 ms, 1 fail |

Per terrain and length (median ms):

| terrain/length | baritone | soprano | ostinato-astar | ostinato-shipped |
|---|---|---|---|---|
| FLAT SHORT | 3 | 3 | 0 | 0 |
| FLAT MEDIUM | 15 | 12 | 2 | 2 |
| FLAT LONG | 53 | 40 | 7 | 3 |
| HILLS SHORT | 4 | 3 | 5 | 5 |
| HILLS MEDIUM | 791 | 542 | 961 | 33 |
| HILLS LONG | 2938 | 1788 | 3152 | 22 |
| CAVES SHORT | 1 | 1 | 1 | 2 |
| CAVES MEDIUM | 219 | 208 | 284 | 410 |
| CAVES LONG | 164 | 145 | 238 | 106 |
| WATER SHORT | 2 | 2 | 3 | 3 |
| WATER MEDIUM | 109 | 77 | 163 | 2 |
| WATER LONG | 1025 | 653 | 1120 | 5 |
| BREAK_PLACE SHORT | 50 | 42 | 76 | 77 |
| BREAK_PLACE MEDIUM | 3686 | 2231 | 4129 | 2899 |
| BREAK_PLACE LONG | 10000 (4 fail) | 9999 (3 fail) | 10011 (4 fail) | 4592 (1 fail) |

Per node, over every rep:

| config | µs / node expanded | bytes allocated / node |
|---|---|---|
| baritone | 5.93 | 652 |
| soprano | **3.58** | **84** |
| ostinato-astar | 6.70 | 260 |
| ostinato-shipped | 6.01 | 203 |

## What it says

### Ostinato as shipped
- It is the fastest and solves the most: 59 of 60 queries, a 6.6 ms median against 83 ms for stock, and 5.5x stock on the paired geomean.
- The win comes from expanding far fewer nodes (median 1233 against about 22000), not from cheaper nodes. The far-segment weight makes the search greedy beyond the first segment, which only has to be optimal near the player. That is where hills (32x) and water (120x) collapse.
- It is slower than stock in one place: CAVES MEDIUM (410 ms against 219). A greedy weight inside a maze-like cave walks into dead ends before backing out.

### Bare Ostinato A*
- On flat ground it is 7x faster than stock, because of the bigger moves (parkour/sprint chains): 8.7% fewer moves for the same distance.
- Everywhere else it is about 10–30% slower than stock. It expands about the same number of nodes as stock, at 6.70 µs per node against 5.93. The extra movement types Ostinato considers per node cost time, and the bench gives them nothing to win on outside flat ground.

### Soprano
- Soprano's engine is the cheapest per node: 3.58 µs and 84 bytes, against 5.93 µs and 652 bytes for stock and 6.70 µs and 260 bytes for bare Ostinato.
- That makes it 1.3x stock on 54 of the 56 shared queries, with identical paths. That points to allocation and per-movement overhead work worth porting into Ostinato's A*.
- It would stack with the far-segment weight: Ostinato's node count times Soprano's node cost.

### Path quality
- Costs are not comparable across forks, because each fork prices moves with its own model.
- Moves and blocks walked are comparable. All four configs walk within 2% of the same distance. Ostinato gets there in about 7–9% fewer, longer moves.

### Failures
- Every failure is BREAK_PLACE LONG: 500-block routes through terrain that has to be mined and bridged. Each failure is the 10 s timeout, not "no path".

## Method

The harness lives in `src/test/java/baritone/pathing/calc/X*.java`.

### Shared files
These are copied unchanged into each fork's `src/test/java/baritone/pathing/calc/`:
- **XTerrain/XVoxels:** the PathBench terrain generator, seed `0x057A7170`. It builds a 1000 × 96 box over heights 0..128 with five terrains: FLAT, HILLS, CAVES, WATER, and BREAK_PLACE (walls you have to dig through or bridge over).
- **XBench:**
  - 60 queries drawn from `Random(1234)` in PathBench's order: 4 per terrain × length, with lengths SHORT 40, MEDIUM 250 and LONG 500.
  - It measures with ThreadMXBean CPU allocation and runs `calculate0` directly.
- **XToolSet:** empty-hand break speeds, so mining costs match in all forks.
- **XBorder:** the shared world border.

### Per-fork files
**XAdapter** and **XWorld** (plus **XLevel** for stock) map that world onto each fork's `BlockStateInterface` / `CalculationContext` and boot the game registries. Reference copies of the stock and Soprano adapters are in [`three-way/`](three-way/).

### Settings and search
- Every fork uses its default `Settings`, with two exceptions: `considerPotionEffects=false` and `chatDebug=false`.
- Every fork gets `Favoring(null, ctx)`, i.e. no previous path.
- Every search runs in pure Java. Ostinato's Rust library only drives the kinematic/TAS planners, not the grid A*.

### Fairness check
Each fork writes its query list. All three are byte-identical (md5 `f54b7900…`, see [`three-way/queries.txt`](three-way/queries.txt)).

### Stock Baritone patches
Stock needed small headless-only patches to run outside a client at all, and none of them change pathing:
- a `BlockStateInterface(BetterWorldBorder)` constructor;
- a `CalculationContext(Level, BSI, ToolSet, boolean)` constructor;
- Ostinato's guards that skip reading `settings.txt` and defer the provider when there is no `Minecraft`.

Soprano needed no patch: it already ships a headless `CalculationContext` for its own tests.

### Reproduce
```
XBENCH=1 XBENCH_REPS=3 ./gradlew test -x nativeHost --tests baritone.pathing.calc.XBenchTest
```
- Run it from each checkout. Drop `-x nativeHost` for stock and Soprano, which don't have that task.
- Give the test JVM a 4 GB heap.
- Output goes to `build/xbench/<config>.md|.csv`.
- Run the forks one after another, never in parallel, and not while a Minecraft client is running.

The raw per-rep CSVs are in [`three-way/`](three-way/).
