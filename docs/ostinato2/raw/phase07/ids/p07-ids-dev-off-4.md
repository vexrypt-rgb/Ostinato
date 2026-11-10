## p07-ids-dev-off-4 (wa:2)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 0.5 | 1.5 | 42 | 277.0 | 0.2 |
| FLAT | MEDIUM | 4 | 0 | 1.6 | 8.0 | 222 | 910.2 | 0.6 |
| FLAT | LONG | 4 | 0 | 3.2 | 6.2 | 473 | 1812.6 | 1.2 |
| HILLS | SHORT | 4 | 0 | 3.5 | 30.1 | 338 | 228.9 | 0.6 |
| HILLS | MEDIUM | 4 | 0 | 19.0 | 367.5 | 3692 | 1331.2 | 2.0 |
| HILLS | LONG | 4 | 0 | 18.6 | 44.3 | 3646 | 2447.4 | 2.1 |
| CAVES | SHORT | 4 | 0 | 1.5 | 6.0 | 244 | 262.4 | 0.3 |
| CAVES | MEDIUM | 4 | 0 | 122.3 | 833.8 | 25206 | 1852.8 | 4.4 |
| CAVES | LONG | 4 | 0 | 95.8 | 336.4 | 19420 | 2670.6 | 2.9 |
| WATER | SHORT | 4 | 0 | 0.5 | 9.0 | 36 | 212.2 | 0.3 |
| WATER | MEDIUM | 4 | 0 | 1.9 | 5.0 | 308 | 1073.9 | 0.7 |
| WATER | LONG | 4 | 0 | 4.8 | 9.5 | 811 | 2168.6 | 1.4 |
| BREAK_PLACE | SHORT | 4 | 0 | 69.8 | 78.7 | 13624 | 321.8 | 2.3 |
| BREAK_PLACE | MEDIUM | 4 | 0 | 2311.0 | 5618.5 | 413837 | 1925.6 | 16.7 |
| BREAK_PLACE | LONG | 4 | 1 | 4267.0 | 10004.0 | 711851 | 3881.9 | 46.9 |

Perf: 179848 nodes/sec, total 112056 ms, 992 MB allocated, max peak heap 424 MB, mean path length 255.1, heap max 3072 MB, cpus 8.

All: median 7.3 ms, p95 4267.0 ms, failures 1 of 60 queries, 3 reps.

Score (travel-first): failure rate 1.7%, mean travel 1383.5 ticks, mean time-to-arrive 1392.8 ticks (travel + plan).

