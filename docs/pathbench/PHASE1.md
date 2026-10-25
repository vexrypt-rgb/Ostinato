# Pathfinding Phase 1: cheap waste cuts (Ostinato)

Run: `scripts/pathbench-phase1.sh <label> normal|pi` (pi = `taskset -c 0`, 512 MB heap). 60 queries x 2 reps, 10 s timeout.
Columns over non-timeout queries (the 4 BREAK_PLACE LONG queries time out in every config).

```
base-normal      nonTO   82.4s  156687 n/s alloc   8085MB peak 629MB median 109.1ms len 237.1 travel 1202.1 fail 6.7%
after-normal     nonTO   77.3s  167074 n/s alloc   1802MB peak 585MB median 105.7ms len 237.1 travel 1202.1 fail 6.7%
base-pi          nonTO   74.0s  165151 n/s alloc   7599MB peak 519MB median 107.0ms len 237.0 travel 1195.6 fail 6.7%
after-pi         nonTO   80.9s  157611 n/s alloc   1781MB peak 519MB median 98.4ms len 235.1 travel 1193.4 fail 6.7%
predict-normal   nonTO   86.3s  150733 n/s alloc   1817MB peak 808MB median 123.6ms len 240.2 travel 1204.1 fail 6.7%
predict-pi       nonTO   80.0s  162557 n/s alloc   1817MB peak 461MB median 111.9ms len 240.2 travel 1204.1 fail 6.7%
```

- Per-query fingerprints (exact cost and node count) are identical before and after on both profiles, so paths are unchanged.
- Allocation is down 78% (8.1 GB -> 1.8 GB). Throughput change is within run-to-run noise (+7% normal, -5% pi).
- Cuts: primitive key in LineWalk.corridor (boxed Long was 23% of bytes), pooled MutableMoveResult in the steered-fall recursion (~40%), no ArrayList in MovementBubbleLift when no bubble column is near, O(1) set lookups for block-list settings, pathing worker threads named, daemon, below client priority (lowest on 1-2 cores).
- Rejected: MovePredictor (per-search reject lookup table; branch pf/move-predictor-proto). Paths got longer (+3.1 blocks mean, +2 travel ticks), median plan time rose, peak heap rose on normal. Allocation was no better than the cuts above.

# Phase 2, batch A: tie-break toward goal (opt-in, `pathTieBreak`, default 0)

| config | non-timeout s | nodes expanded (56 shared) | median ms | mean cost change | worst cost change | failures |
|---|---|---|---|---|---|---|
| stock order | 79.9 | 6.46M | 108.4 | 0 | 0 | 6.7% |
| pathTieBreak 0.001 | 82.7 | 6.08M (-6%) | 105.7 | +0.035% | +0.85% | 6.7% |
| pathTieBreak 0.01 | 76.4 | 6.25M (-3%) | 91.8 | +0.048% | +0.85% | 6.7% |

Small node savings, wall time within noise, slightly costlier paths: kept off by default (trade-off, not a win).
