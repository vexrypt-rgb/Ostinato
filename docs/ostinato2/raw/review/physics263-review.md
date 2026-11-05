## physics263-review (astar)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 0.4 | 1.0 | 42 | 277.0 | 0.2 |
| HILLS | SHORT | 4 | 0 | 2.2 | 17.8 | 338 | 228.9 | 0.5 |
| CAVES | SHORT | 4 | 0 | 1.6 | 6.8 | 244 | 262.4 | 0.3 |
| WATER | SHORT | 4 | 0 | 0.5 | 9.9 | 36 | 212.2 | 0.3 |
| BREAK_PLACE | SHORT | 4 | 0 | 78.2 | 89.4 | 13624 | 321.8 | 2.2 |

Perf: 173030 nodes/sec, total 1681 ms, 71 MB allocated, max peak heap 226 MB, mean path length 40.0, heap max 3072 MB, cpus 8.

All: median 1.9 ms, p95 87.4 ms, failures 0 of 20 queries, 5 reps.

Score (travel-first): failure rate 0.0%, mean travel 260.5 ticks, mean time-to-arrive 260.8 ticks (travel + plan).

