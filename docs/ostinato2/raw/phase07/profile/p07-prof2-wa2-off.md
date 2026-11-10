## p07-prof2-wa2-off (wa:2)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | LONG | 4 | 0 | 3.3 | 3.7 | 473 | 1812.6 | 1.2 |
| HILLS | LONG | 4 | 0 | 18.3 | 56.1 | 3646 | 2447.4 | 2.1 |
| CAVES | LONG | 4 | 0 | 100.1 | 353.4 | 19420 | 2670.6 | 2.9 |
| WATER | LONG | 4 | 0 | 5.9 | 7.9 | 811 | 2168.6 | 1.4 |
| BREAK_PLACE | LONG | 4 | 1 | 4598.1 | 10002.9 | 711851 | 3881.9 | 44.9 |

Perf: 150146 nodes/sec, total 25181 ms, 210 MB allocated, max peak heap 385 MB, mean path length 484.9, heap max 3072 MB, cpus 8.

All: median 18.3 ms, p95 5270.7 ms, failures 1 of 20 queries, 1 reps.

Score (travel-first): failure rate 5.0%, mean travel 2528.5 ticks, mean time-to-arrive 2544.5 ticks (travel + plan).

