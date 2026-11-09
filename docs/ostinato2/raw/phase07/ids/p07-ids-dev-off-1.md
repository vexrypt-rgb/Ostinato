## p07-ids-dev-off-1 (wa:2)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 0.5 | 2.0 | 42 | 277.0 | 0.2 |
| FLAT | MEDIUM | 4 | 0 | 2.9 | 4.3 | 222 | 910.2 | 0.6 |
| FLAT | LONG | 4 | 0 | 4.0 | 22.0 | 473 | 1812.6 | 1.2 |
| HILLS | SHORT | 4 | 0 | 3.7 | 29.0 | 338 | 228.9 | 0.6 |
| HILLS | MEDIUM | 4 | 0 | 22.6 | 433.5 | 3692 | 1331.2 | 2.0 |
| HILLS | LONG | 4 | 0 | 20.3 | 48.3 | 3646 | 2447.4 | 2.1 |
| CAVES | SHORT | 4 | 0 | 1.7 | 7.5 | 244 | 262.4 | 0.3 |
| CAVES | MEDIUM | 4 | 0 | 146.3 | 898.8 | 25206 | 1852.8 | 4.4 |
| CAVES | LONG | 4 | 0 | 108.1 | 390.5 | 19420 | 2670.6 | 2.9 |
| WATER | SHORT | 4 | 0 | 0.4 | 8.2 | 36 | 212.2 | 0.3 |
| WATER | MEDIUM | 4 | 0 | 2.0 | 3.2 | 308 | 1073.9 | 0.7 |
| WATER | LONG | 4 | 0 | 5.0 | 6.5 | 811 | 2168.6 | 1.4 |
| BREAK_PLACE | SHORT | 4 | 0 | 75.0 | 91.6 | 13624 | 321.8 | 2.3 |
| BREAK_PLACE | MEDIUM | 4 | 0 | 2861.9 | 6608.2 | 413837 | 1925.6 | 16.7 |
| BREAK_PLACE | LONG | 4 | 1 | 4789.2 | 10005.6 | 711851 | 3881.9 | 45.0 |

Perf: 157533 nodes/sec, total 122990 ms, 968 MB allocated, max peak heap 348 MB, mean path length 255.1, heap max 3072 MB, cpus 8.

All: median 7.5 ms, p95 4789.2 ms, failures 1 of 60 queries, 3 reps.

Score (travel-first): failure rate 1.7%, mean travel 1383.5 ticks, mean time-to-arrive 1394.0 ticks (travel + plan).

