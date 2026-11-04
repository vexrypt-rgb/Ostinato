## phase00-short (astar)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 0.5 | 0.9 | 42 | 277.0 | 0.2 |
| HILLS | SHORT | 4 | 0 | 1.7 | 16.6 | 338 | 228.9 | 0.5 |
| CAVES | SHORT | 4 | 0 | 1.2 | 6.1 | 244 | 262.4 | 0.3 |
| WATER | SHORT | 4 | 0 | 0.3 | 7.9 | 36 | 212.2 | 0.3 |
| BREAK_PLACE | SHORT | 4 | 0 | 74.7 | 92.9 | 13624 | 321.8 | 1.9 |

Perf: 173982 nodes/sec, total 334 ms, 13 MB allocated, max peak heap 217 MB, mean path length 40.0, heap max 3072 MB, cpus 8.

All: median 1.6 ms, p95 88.4 ms, failures 0 of 20 queries, 1 reps.

Score (travel-first): failure rate 0.0%, mean travel 260.5 ticks, mean time-to-arrive 260.8 ticks (travel + plan).

