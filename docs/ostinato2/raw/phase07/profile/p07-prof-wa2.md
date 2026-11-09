## p07-prof-wa2 (wa:2)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | LONG | 4 | 0 | 3.6 | 5.2 | 473 | 1812.6 | 1.2 |
| HILLS | LONG | 4 | 0 | 23.6 | 46.4 | 3646 | 2447.4 | 2.1 |
| CAVES | LONG | 4 | 0 | 95.4 | 356.0 | 19420 | 2670.6 | 2.9 |
| WATER | LONG | 4 | 0 | 5.3 | 7.0 | 811 | 2168.6 | 1.4 |
| BREAK_PLACE | LONG | 4 | 1 | 4780.3 | 10003.2 | 711851 | 3881.9 | 45.7 |

Perf: 157243 nodes/sec, total 24689 ms, 213 MB allocated, max peak heap 348 MB, mean path length 484.9, heap max 3072 MB, cpus 8.

All: median 23.6 ms, p95 4901.0 ms, failures 1 of 20 queries, 1 reps.

Score (travel-first): failure rate 5.0%, mean travel 2528.5 ticks, mean time-to-arrive 2544.0 ticks (travel + plan).

