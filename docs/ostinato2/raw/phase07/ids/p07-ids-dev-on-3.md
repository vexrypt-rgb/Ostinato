## p07-ids-dev-on-3 (wa:2)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 0.5 | 1.3 | 42 | 277.0 | 0.2 |
| FLAT | MEDIUM | 4 | 0 | 1.6 | 19.8 | 222 | 910.2 | 0.6 |
| FLAT | LONG | 4 | 0 | 3.3 | 5.2 | 473 | 1812.6 | 1.2 |
| HILLS | SHORT | 4 | 0 | 4.4 | 22.7 | 338 | 228.9 | 0.6 |
| HILLS | MEDIUM | 4 | 0 | 18.4 | 369.7 | 3692 | 1331.2 | 2.0 |
| HILLS | LONG | 4 | 0 | 17.7 | 65.6 | 3646 | 2447.4 | 2.1 |
| CAVES | SHORT | 4 | 0 | 1.5 | 5.6 | 244 | 262.4 | 0.3 |
| CAVES | MEDIUM | 4 | 0 | 118.2 | 798.6 | 25206 | 1852.8 | 4.4 |
| CAVES | LONG | 4 | 0 | 92.9 | 350.0 | 19420 | 2670.6 | 2.9 |
| WATER | SHORT | 4 | 0 | 0.5 | 13.0 | 36 | 212.2 | 0.3 |
| WATER | MEDIUM | 4 | 0 | 2.0 | 3.3 | 308 | 1073.9 | 0.7 |
| WATER | LONG | 4 | 0 | 4.6 | 8.8 | 811 | 2168.6 | 1.4 |
| BREAK_PLACE | SHORT | 4 | 0 | 67.5 | 82.6 | 13624 | 321.8 | 2.3 |
| BREAK_PLACE | MEDIUM | 4 | 0 | 2244.2 | 5597.1 | 413837 | 1925.6 | 16.7 |
| BREAK_PLACE | LONG | 4 | 1 | 4246.0 | 10008.6 | 711851 | 3881.9 | 47.3 |

Perf: 182494 nodes/sec, total 111253 ms, 996 MB allocated, max peak heap 369 MB, mean path length 255.1, heap max 3072 MB, cpus 8.

All: median 6.7 ms, p95 4246.0 ms, failures 1 of 60 queries, 3 reps.

Score (travel-first): failure rate 1.7%, mean travel 1383.5 ticks, mean time-to-arrive 1392.7 ticks (travel + plan).

