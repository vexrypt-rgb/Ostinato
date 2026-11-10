## e7-on (wa:2)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 0.6 | 1.4 | 42 | 277.0 | 0.2 |
| FLAT | MEDIUM | 4 | 0 | 1.7 | 2.5 | 222 | 910.2 | 0.6 |
| FLAT | LONG | 4 | 0 | 3.7 | 5.6 | 473 | 1812.6 | 1.2 |
| HILLS | SHORT | 4 | 0 | 2.1 | 18.9 | 338 | 228.9 | 0.6 |
| HILLS | MEDIUM | 4 | 0 | 17.4 | 294.4 | 3692 | 1331.2 | 2.0 |
| HILLS | LONG | 4 | 0 | 17.9 | 41.2 | 3646 | 2447.4 | 2.1 |
| CAVES | SHORT | 4 | 0 | 1.3 | 5.9 | 244 | 262.4 | 0.3 |
| CAVES | MEDIUM | 4 | 0 | 107.7 | 745.0 | 25206 | 1852.8 | 4.4 |
| CAVES | LONG | 4 | 0 | 80.3 | 304.6 | 19420 | 2670.6 | 2.9 |
| WATER | SHORT | 4 | 0 | 0.4 | 7.8 | 36 | 212.2 | 0.3 |
| WATER | MEDIUM | 4 | 0 | 2.0 | 3.0 | 308 | 1073.9 | 0.7 |
| WATER | LONG | 4 | 0 | 5.1 | 5.8 | 811 | 2168.6 | 1.4 |
| BREAK_PLACE | SHORT | 4 | 0 | 69.1 | 93.6 | 13624 | 321.8 | 2.3 |
| BREAK_PLACE | MEDIUM | 4 | 0 | 2161.6 | 5333.2 | 413837 | 1925.6 | 16.7 |
| BREAK_PLACE | LONG | 4 | 0 | 4351.1 | 10003.5 | 711851 | 3864.3 | 47.2 |

Perf: 186188 nodes/sec, total 72766 ms, 664 MB allocated, max peak heap 363 MB, mean path length 256.9, heap max 3072 MB, cpus 8.

All: median 5.9 ms, p95 4351.1 ms, failures 0 of 60 queries, 2 reps.

Score (travel-first): failure rate 0.0%, mean travel 1403.4 ticks, mean time-to-arrive 1414.0 ticks (travel + plan).

