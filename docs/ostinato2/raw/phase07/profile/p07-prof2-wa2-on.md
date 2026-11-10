## p07-prof2-wa2-on (wa:2)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | LONG | 4 | 0 | 3.3 | 3.7 | 473 | 1812.6 | 1.2 |
| HILLS | LONG | 4 | 0 | 17.9 | 59.6 | 3646 | 2447.4 | 2.1 |
| CAVES | LONG | 4 | 0 | 93.4 | 339.6 | 19420 | 2670.6 | 2.9 |
| WATER | LONG | 4 | 0 | 8.0 | 9.6 | 811 | 2168.6 | 1.4 |
| BREAK_PLACE | LONG | 4 | 1 | 4332.3 | 10004.3 | 711851 | 3881.9 | 46.7 |

Perf: 172556 nodes/sec, total 23321 ms, 217 MB allocated, max peak heap 344 MB, mean path length 484.9, heap max 3072 MB, cpus 8.

All: median 17.9 ms, p95 4378.2 ms, failures 1 of 20 queries, 1 reps.

Score (travel-first): failure rate 5.0%, mean travel 2528.5 ticks, mean time-to-arrive 2542.6 ticks (travel + plan).

