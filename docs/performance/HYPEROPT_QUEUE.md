# Hyperopt queue (Phase 2)

Ranked by measured cost on the `wa:2` PathBench, then correctness risk and complexity. One experiment at a time. Level 4–5 stays closed until a Java change clears the bar: at least 5% end-to-end or 10% on the hot queries, fingerprints unchanged.

Compare shape-memo runs to each other. Both use the harness that claims the mining memo. Do not compare them to `hyperopt-base`, which did not claim it.

## Closed

### E1 — direct-mapped block cache on `get0`. Level 1. Classification B/C. Reverted.

Soprano-style 4096-slot cache, `OSTINATO_BLOCK_CACHE=0` disabled it. Fingerprints identical (60 lines). Golden passed with the cache on. Sum of per-query medians 40038.8 ms off vs 40492.2 ms on (ratio 0.989). Geomean on queries over 1 ms: 0.990× (n=53). Nodes/s 163787 off vs 161172 on. The off run still paid a volatile owner check, so it was already slower than the later pure baseline (179156 nodes/s).

Why it lost: PathBench worlds override `get0` with an array. The hash plus the owner check cost more than that read. Hypothesis, unverified: a real chunk `get0` might still benefit. Do not re-ship without that measurement. Caching only `CalculationContext.get` would also miss direct `bsi.get0` inside walkability checks.

### E2 — precomputed `hypot(2, 1)` in `LineWalk.ticks`. Level 1. Classification B. Reverted.

Every hypot sample was `LineWalk.ticks` for a 2-by-1 knight offset. The constant fold is bit-identical (golden passed, fingerprints identical to `hyperopt-base`). Wall clock was neutral: 179369 vs 179156 nodes/s, sum of per-query medians 37371.1 vs 37318.2 (ratio 0.9986), geomean on queries over 5 ms 1.007× (n=33). JFR safepoint bias, not a CPU win. Do not retry unless a different measurement shows time inside `ticks`.

## Closed (continued)

### E3 — memoize `collisionTop` / `collisionBottom` / `collisionWidth` by block-state id. Level 1. Classification B. Reverted.

Both sides used the harness that claims the mining memo, `wa:2`, 5 reps, all 60 queries, same JVM order (golden, then bench). `OSTINATO_SHAPE_MEMO=0` label `hyperopt-shape-off` versus `=1` label `hyperopt-shape-on`.

| | Off | On |
|---|---|---|
| Nodes/s | 188705 | 193264 |
| Total ms | 180565 | 176306 |
| Allocated | 1367 MB | 1367 MB |
| Median / p95 | 6.8 / 4150.0 ms | 6.5 / 4101.6 ms |
| Failures | 0 of 60 | 0 of 60 |
| Mean travel | 1423.1 ticks | 1423.1 ticks |

On/off is 1.024× nodes/s (2.4% less total time). BREAK_PLACE medium 2175.9 → 2117.9 ms (2.7%). BREAK_PLACE long 4150.0 → 4101.6 ms (1.2%). Caves medium 107.8 → 106.4 ms. Fingerprints identical, 60 lines. Golden passed on both. With the memo on, `ShapeMemoTest` checked every registered state against a direct shape query (exact doubles) and passed. Under the 5% end-to-end / 10% hot-query bar. The VarHandle memo is not worth 2.4%. Reverted, including the test.

`hyperopt-shape-off` fingerprints also matched `hyperopt-base`. The harness claim does not change costs on queries that finish. BREAK_PLACE long sits on the 10 s cutoff (p95 9907 ms, fingerprinted as timeout). Its ok/fail bit flips with noise. Do not read the 0-fail reports as a reliability win.

## Measured, not implemented

Mining-memo hit rate, MEDIUM only, `wa:2`, one timed rep after the warmup (counters reset after warmup). The probe was removed afterward.

`hits=94156363 misses=2458750 bypass=0 repeats=53` (hit rate 0.9746, consecutive-key repeat rate about 0). `bypass=0` means the harness claim was visible on every call. A larger `MINING_BITS` would only touch the 2.5% misses. A one-slot lookaside would almost never hit. Do not do either. Production `calculate()` already installs this cache.

The 13.49% wrapper share is not the hit path. All 963 top-frame samples are line 787, the call to `getMiningDurationTicks0`. The hit return (line 785) has none. A removed probe on the same MEDIUM `wa:2` one-rep slice, warmup excluded, counted the same 2458750 body entries: `air=1455080` (return 0), `real=1002373` (a duration), `fluid=823`, `avoidBreak=474`, and `avoidWalk=mult=noTool=falling=0`. `getStrVsBlock` is already `HashMap.computeIfAbsent` per `Block`. Do not add a second strength cache unless a bench shows that lookup is the wall time.

## Measured, not implemented (continued)

`canWalkOnPosition` line samples in `hyperopt-claimed.jfr`: 259 on the water check (line 603), 926 on `isTallTop` (line 620), 11 on the falling-block arm. Callers are `PrecomputedData.canWalkOn`'s MAYBE branch. A call count on the same MEDIUM `wa:2` workload, one timed rep, was 8037 calls, every one `minecraft:water`. That is not 16% of a ~27 s run. Same safepoint bias as `LineWalk.ticks`. Do not spend an optimization on this method for PathBench. The probe was removed.

### E4 — mining memo at 2^12 slots instead of 2^16. Level 5 (cache fit). Classification B. Reverted.

Same harness, `wa:2`, 5 reps, all 60 queries, golden in the same JVM. `OSTINATO_MINING_BITS=16` versus `=12`. Fingerprints identical (60 lines). Golden passed both. Allocated 1367 MB both. Mean travel 1423.1 ticks both.

| | 16 bits | 12 bits |
|---|---|---|
| Nodes/s | 193545 | 195672 |
| Total ms | 176050 | 174136 |
| BREAK_PLACE medium | 2134.7 | 2080.1 |
| BREAK_PLACE long | 4111.2 | 4002.4 |

1.011× nodes/s. The hot queries moved about 2.6%. Under the 5% bar. The env hook was removed. Default stays 16. A still smaller table is not justified by a 1% step.

### E5 — 4-ary open set behind `OSTINATO_HEAP4=1`. Level 2. Classification B. Reverted.

`BinaryHeapOpenSet.siftUp` was 581 of 581 samples on the while-loop back-edge (line 158 of the claimed profile), about 8% of that profile. The binary `siftUp` and `removeLowest` stayed in source. The 4-ary copies used the same strict `>` and the same earlier-child-on-tie rule. Parent of a 1-based index is `((index - 2) >>> 2) + 1`. Children of `i` are `4*(i-1)+2` through `4*i+1`. `OpenSetsTest` passed with the flag unset and with `OSTINATO_HEAP4=1` before the bench.

Same harness, `wa:2`, 5 reps, all 60 queries, golden in the same JVM. Fingerprints identical (60 lines). Golden passed both (`BUILD SUCCESSFUL`, 4m 26s off, 4m 32s on).

| | Binary | 4-ary |
|---|---|---|
| Nodes/s | 166597 | 163479 |
| Total ms | 198459 | 200465 |
| Allocated | 1347 MB | 1341 MB |
| Median / p95 | 7.6 / 4826.3 ms | 7.2 / 4814.8 ms |
| Failures | 1 of 60 | 1 of 60 |
| Mean travel | 1383.5 ticks | 1383.5 ticks |
| BREAK_PLACE short / medium / long | 75.9 / 2538.5 / 4826.3 ms | 79.2 / 2646.5 / 4814.8 ms |

0.981× nodes/s. BREAK_PLACE medium was 4.3% slower. The flag and both 4-ary methods were removed. Fewer parent moves did not show up in wall time. Do not retry a wider heap on this bench.

### E6 — return 0 before the mining hash when the cell is walk-through. Level 1. Classification B. Reverted.

A count on MEDIUM `wa:2`, one timed rep after warmup, then removed: `calls=96615113 walk=74515520 frac=0.7713`. That run is not a speed number. `getMiningDurationTicks0` already returns 0 for a walk-through cell and does not charge falling above it, so the early return is the same answer.

`OSTINATO_MINE_AIR=1` versus unset. Same harness, `wa:2`, 5 reps, all 60 queries, golden in the same JVM. Fingerprints identical (60 lines). Golden passed both (`BUILD SUCCESSFUL`, 4m 54s off, 4m 26s on).

| | Off | On |
|---|---|---|
| Nodes/s | 163740 | 166581 |
| Total ms | 201142 | 198119 |
| Allocated | 1344 MB | 1346 MB |
| Median / p95 | 7.2 / 4870.4 ms | 7.3 / 4729.8 ms |
| Failures | 1 of 60 | 1 of 60 |
| Mean travel | 1383.5 ticks | 1383.5 ticks |
| BREAK_PLACE short / medium / long | 81.7 / 2582.2 / 4870.4 ms | 78.4 / 2499.1 / 4729.8 ms |

1.017× nodes/s (1.5% less total time). BREAK_PLACE medium 3.2%, long 2.9%. Under the 5% / 10% bar. The env hook was removed. Do not put `canWalkThrough` in front of the hash again. Skipping 77% of the calls still left the solid-cell hash, and `canWalkThrough` itself is not free.

E5 and E6 absolute nodes/s sit near 164–167k. E4's pair sat near 194k on the same binary algorithm. Use each pair against itself. Session-to-session wall clock moved. Costs did not: both new pairs fingerprinted equal inside the pair, and the 1-of-60 failure is the BREAK_PLACE long cutoff.

### E7 — skip moves that cannot fire. Level 2. Classification A. Kept.

The neighbor loop walked every `Moves` value, about 99, including 24 jump slots, 4 parkour, 4 slime, 6 climb, 4 chain, and 3 TAS. With `allowParkour` false (the bench, and the setting default) those methods return before writing a cost. `Moves.counts` drops them. Swims, steps, diagonals, lines, and bubble columns stay. Order and ordinals are unchanged, so the per-move cost scale still lines up.

`OSTINATO_MOVE_SKIP=1` versus unset. Same harness, `wa:2`, 5 reps, all 60 queries, golden in the same JVM. Fingerprints identical (60 lines, costs to 1e-6). Golden passed both (`BUILD SUCCESSFUL`, 4m 49s off, 4m 10s on). The env flag was removed after the pair. The kept code always applies `counts`.

| | All moves | Skipping no-ops |
|---|---|---|
| Nodes/s | 168646 | 180891 |
| Total ms | 196437 | 185947 |
| Allocated | 1349 MB | 1360 MB |
| Failures | 1 of 60 | 0 of 60 |
| Mean travel | 1383.5 ticks | 1391.5 ticks |

1.073× nodes/s (5.3% less total time). The grouped BREAK_PLACE medium median (2494 ms vs 2778 ms) mixes four queries and is the wrong summary. Per-query medians of the five reps:

| Query | Nodes | Off ms | On ms |
|---|---|---|---|
| BREAK_PLACE/MEDIUM/0 | 1049533 | 5926.2 | 5331.7 |
| BREAK_PLACE/MEDIUM/1 | 198412 | 1172.9 | 1211.8 |
| BREAK_PLACE/MEDIUM/2 | 413837 | 2375.6 | 2348.6 |
| BREAK_PLACE/MEDIUM/3 | 573916 | 3185.3 | 2918.3 |
| BREAK_PLACE/LONG/1 | 711851 | 4695.6 | 4455.0 |
| BREAK_PLACE/LONG/2 | 630620 | 4028.2 | 3818.8 |
| BREAK_PLACE/LONG/3 | 726866 | 4677.1 | 4353.9 |

The million-node query is 10.0% faster. LONG/0 is `timeout` on both fingerprints. The 0-vs-1 failure and the travel means (1383.5 vs 1391.5) are that cutoff, not a different path. Do not read the 0-fail report as a reliability win.

### Bubble-column scans — counted, not implemented.

`MovementBubbleLift.options` is uncached and `SLOTS` is 2, so both lift slots scan. A removed probe on MEDIUM `wa:2`, one timed rep after warmup, with E7's filter in place (`BUILD SUCCESSFUL` in 1m 23s, label `hyperopt-bubble-probe`, not a speed number):

`calls=5219502 early=1608150 scans=3611352 columns=0 bubbleGets=23276262 totalGets=623236459 frac=0.03735`

`early + scans` equals `calls`. No terrain in the bench places a bubble column, so `columns=0`. The scan is 3.73% of block reads. A position cache would drop the second slot and save about half of that, near 1.9% of reads, and reads are not the whole node. Under the 5% / 10% bar. Do not add the cache. Do not skip the bubble family: a real column still has to return a finite cost, and a fingerprint match on this bench would not prove a skip.

## Next, one at a time

E7 is the first keep. Do not re-open E1–E6. Do not put the skipped families back into the default loop. Do not cache or skip bubble columns on the strength of this count.

PathBench on the remaining hot lines still has no supported change. The mining hit path, the 4-ary heap, and the walk-through early-out each missed the bar. The miss body is 59% a zero return and 41% a duration, and the duration already uses the tool-strength map.

Do not open an experiment from a single hot line:

- `calculate0` line 229 (530 samples) is `worldBorder.entirelyContains` for dynamic moves, and line 210 (240) is `moves.apply`. Same shape as the `canWalkOnPosition` miss: one line, unproven wall time.
- `MovementAscend.cost` line 120 (412 of 479) is the water climb-out test (`isWater` plus a fluid check). Water was the whole `canWalkOnPosition` call count, and that method was not the bench.

`shouldAvoidBreaking(int,int,int)` is not the next experiment. It was 2.3% of samples only in the profile that did not claim the mining memo, and it left the top 25 once the memo was claimed. The body that calls it is the 2.5% miss path. An idle fast path there cannot clear 5% on this bench, and a missed flag on a mutator would change which blocks are protected.

A profile with the kept filter is in `HYPEROPT_BASELINE.md` (`hyperopt-e7profile`, MEDIUM `wa:2`, 2 reps, 3448 samples). It does not name a new patch:

- `hypot` is back at 12.33%, all inside `StrictMath.hypot`. E2 already folded that call and the wall clock did not move.
- `siftUp` is 11.75%, still the while back-edge. E5's wider heap was slower.
- `getMiningDurationTicks0` line 802 is 291 samples, the call to `breakCostMultiplierAt`. That calls `shouldAvoidBreaking`, which is not itself in the top 25. The idle fast path stays rejected.
- `canWalkOn` line 81 is 206 samples, the MAYBE call. The position check was 8037 water calls on this bench.
- `calculate0` line 227 is `moves.apply` (188). Line 246, `entirelyContains`, is 51 samples, down from 530 before E7.
- Ascend's water test is no longer a hot line. `factorCellY` (46) is the ascend honey check.

Do not add another PathBench flag from this profile. The measurement this bench cannot make is a live client profile on real chunk lookups.

## Rejected without a new measurement

- Native A*, and native block lookups. `docs/RUST_NATIVE.md` §1.1–1.2. Boundary traffic eats the kernel.
- Enabling `piCheapPathing`, hierarchy, tick-accurate costs, or momentum costs. Prior benches said no.
- PPE / Grace Note / Bombard / Scherzo. Not on this branch.
- Re-enabling Presto or Legato movement experiments. No new evidence.
- A wider open-set heap. E5, fingerprints identical, 0.981× nodes/s.
- A walk-through early-out in front of the mining hash. E6, fingerprints identical, 1.017× nodes/s.
- An idle fast path in `shouldAvoidBreaking`. Bounded by the mining miss rate. Not benched.
