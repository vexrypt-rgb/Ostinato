## phase04-review (astar)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 0.3 | 0.8 | 42 | 277.0 | 0.2 |
| HILLS | SHORT | 4 | 0 | 2.0 | 15.6 | 338 | 228.9 | 0.5 |
| CAVES | SHORT | 4 | 0 | 1.2 | 5.5 | 244 | 262.4 | 0.3 |
| WATER | SHORT | 4 | 0 | 0.5 | 7.4 | 36 | 212.2 | 0.3 |
| BREAK_PLACE | SHORT | 4 | 0 | 67.8 | 75.9 | 13624 | 321.8 | 2.2 |

Perf: 202840 nodes/sec, total 1434 ms, 71 MB allocated, max peak heap 237 MB, mean path length 40.0, heap max 3072 MB, cpus 8.

All: median 1.6 ms, p95 69.1 ms, failures 0 of 20 queries, 5 reps.

Score (travel-first): failure rate 0.0%, mean travel 260.5 ticks, mean time-to-arrive 260.7 ticks (travel + plan).

