# Hyperopt handoff

## State

- Branch `perf/hyperopt` in `Documents/MinecraftDev/Ostinato-hyperopt`. HEAD `67e12b96e`. No local commit. Not pushed.
- Phase: 5. E1–E6 reverted. E7 kept, uncommitted.
- Other worktrees were not touched. `Ostinato-o2base` stays a clean detached `origin/main`. PPE stays on `Ostinato-ppe`.

## Experiments

| Id | Change | Class | Disposition |
|---|---|---|---|
| E1 | 4096-slot block cache on `get0` | B/C, ~1% slower, fingerprints identical | Reverted |
| E2 | `LineWalk.ticks` constant for 2-by-1 | B, ~0.1% nodes/s, fingerprints identical | Reverted |
| E3 | State-id memo of collision top/bottom/width | B, 1.024× nodes/s, fingerprints identical, golden passed | Reverted |
| E4 | Mining memo 2^12 slots instead of 2^16 | B, 1.011× nodes/s, fingerprints identical, golden passed | Reverted |
| E5 | 4-ary open set (`OSTINATO_HEAP4=1`) | B, 0.981× nodes/s, fingerprints identical, golden passed | Reverted |
| E6 | Return 0 before the mining hash when walk-through (`OSTINATO_MINE_AIR=1`) | B, 1.017× nodes/s, fingerprints identical, golden passed | Reverted |
| E7 | Skip jump/parkour/slime/climb/chain/TAS when their flags are off | A, 1.073× nodes/s, fingerprints identical, golden passed | Kept |

E1–E4 numbers are in `HYPEROPT_QUEUE.md`. E5 and E6 are there too. The pure-source wall clock, without the mining claim, is `HYPEROPT_BASELINE.md` label `hyperopt-base`.

E5 detail: 581 of 581 `siftUp` samples in the claimed profile were the while-loop back-edge. `OpenSetsTest` passed on the binary heap and on the 4-ary heap. Paired bench, binary 166597 nodes/s vs 4-ary 163479. BREAK_PLACE medium 2538.5 ms vs 2646.5 ms. Failures 1 of 60 both. Travel 1383.5 both.

E6 detail: a removed probe on MEDIUM `wa:2`, one timed rep, counted `calls=96615113 walk=74515520` (frac 0.7713). That run is not a speed number. Paired bench, off 163740 nodes/s vs on 166581. BREAK_PLACE medium 2582.2 ms vs 2499.1 ms (3.2%), long 4870.4 vs 4729.8 (2.9%). Failures 1 of 60 both. Travel 1383.5 both.

## Files changed (uncommitted)

- `src/main/java/baritone/pathing/movement/Moves.java` — `counts(CalculationContext)`. False for parkour, slime, jump, climb, chain, and TAS when that family's flags are off.
- `src/main/java/baritone/pathing/calc/AStarPathFinder.java` — the neighbor loop uses only moves `counts` accepts. Same order. Ordinals still index the cost scale.
- `src/test/java/baritone/pathing/calc/PathBench.java` — `claimSearchCaches` / `releaseSearchCaches` around `calculate0`. Measurement alignment with production `calculate()`. Keep, and do not describe it as a game speedup.
- `docs/performance/HYPEROPT_*.md` — this handoff, the audit pointer, the baseline, the queue.

No env hook remains. `BinaryHeapOpenSet.java` and `MovementHelper.java` match HEAD.

## Correctness so far

- E1 on: golden passed, fingerprints matched the instrumented off run.
- E2: golden passed, fingerprints matched `hyperopt-base`.
- Harness claim: golden passed. `hyperopt-shape-off` fingerprints matched `hyperopt-base` (costs of finished queries unchanged).
- E3 on: golden passed, fingerprints matched the off run. Reverted for lack of speed, not for incorrectness.
- E4: golden passed both sizes, fingerprints matched. Reverted for lack of speed.
- E5: `OpenSetsTest` passed both heaps. Golden passed both. Fingerprints matched (60 lines). Reverted for lack of speed. The 4-ary order did not change cost or node count on this bench.
- E6: golden passed both. Fingerprints matched (60 lines). Reverted for lack of speed. The early 0 is the same answer as the mining body for a walk-through cell.
- E7: golden passed both. Fingerprints matched (60 lines, including every finished BREAK_PLACE cost). Kept. The 0-vs-1 failure is BREAK_PLACE/LONG/0 on the 10 s cutoff (`timeout` in both fingerprints).

## Risks

- Benches that claim the mining memo are not comparable to `hyperopt-base` on wall clock. Costs of finished queries did match.
- JFR percents are a ranking hint. E2 was 13% of samples and 0% of wall time. E5 was 8% of samples on a real loop edge and the wider heap was slightly slower.
- E5/E6 absolute nodes/s (~164–167k) are lower than E4's pair (~194k) on the same binary algorithm. Compare a pair to itself. Do not read the gap as a regression in the reverted code.
- BREAK_PLACE long is a 10 s boundary. Its fail bit is noise. Both E5 and E6 were 1 of 60.

## Next exact action

Do not re-open E1–E6. Do not change `MINING_BITS`. Do not add a mining lookaside. Do not put `canWalkThrough` in front of the mining hash. Do not replace the binary heap with a wider one. Do not add a second tool-strength cache. Do not put jump, parkour, slime, climb, chain, or TAS back into the loop while their flags are off. `canWalkOnPosition` is not a bench bottleneck (8037 water calls).

Do not bench an idle `shouldAvoidBreaking` fast path. After the mining claim it left the top 25, and the body that calls it is the 2.5% miss path.

E7 is kept and uncommitted. Do not commit unless asked. The pair was 168646 vs 180891 nodes/s. The million-node query, BREAK_PLACE/MEDIUM/0, was 5926.2 ms vs 5331.7 ms.

Bubble columns were counted and are not the next patch. MEDIUM `wa:2`, one timed rep after warmup, probe removed: `calls=5219502 early=1608150 scans=3611352 columns=0 bubbleGets=23276262 totalGets=623236459 frac=0.03735`. That is 3.73% of block reads. A second-slot cache would save about half of it. Do not add the cache. Do not skip the family.

Post-E7 profile is recorded (`hyperopt-e7profile`, MEDIUM `wa:2`, 2 reps, 3448 samples, not a speed number). `hypot` 12.33% (E2, do not retry), `siftUp` 11.75% (E5, do not retry), mining body line 802 is the `breakCostMultiplierAt` call (291) and `shouldAvoidBreaking` is not in the top 25, `canWalkOn` line 81 is the MAYBE call (206), `entirelyContains` fell to 51 samples. No PathBench change follows from that profile.

The next measurement PathBench cannot make is a live client profile of pathfinding on real chunks. Do not open E8 from a hot line in `hyperopt-e7profile.jfr`. Do not commit unless asked.

## Re-measure on integrate/2026-11-10 (2026-11-10, merged)

Same PathBench (60 queries, seed 1234, `wa:2`, 10 s timeout), 2 reps, `cleanTest --no-build-cache`, on the
integration tree (Ostinato 2.0 Phases 00-10 + OAP + origin/main). E7 is applied to a loop-only `liveMoves` array, so
`moveScale` and Legato's `flatWalk` stay indexed by ordinal over the full `Moves.all()`.

| | off | on |
|---|---|---|
| nodes/s | 161539 | 186188 (1.153x) |
| total ms | 80983 | 72766 |
| failures | 1 of 60 (BREAK_PLACE/LONG 10 s boundary) | 0 of 60 |
| fingerprints | 60 lines | identical |

Raw: `raw/hyperopt-e7-*`. Committed, on by default (no flag: the skipped families cannot produce a finite cost).
