## e7-off (wa:2)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 0.8 | 1.7 | 42 | 277.0 | 0.2 |
| FLAT | MEDIUM | 4 | 0 | 2.6 | 3.5 | 222 | 910.2 | 0.6 |
| FLAT | LONG | 4 | 0 | 4.2 | 12.2 | 473 | 1812.6 | 1.2 |
| HILLS | SHORT | 4 | 0 | 2.8 | 20.9 | 338 | 228.9 | 0.6 |
| HILLS | MEDIUM | 4 | 0 | 23.7 | 382.4 | 3692 | 1331.2 | 2.0 |
| HILLS | LONG | 4 | 0 | 24.1 | 57.5 | 3646 | 2447.4 | 2.1 |
| CAVES | SHORT | 4 | 0 | 1.4 | 6.0 | 244 | 262.4 | 0.3 |
| CAVES | MEDIUM | 4 | 0 | 141.8 | 848.7 | 25206 | 1852.8 | 4.4 |
| CAVES | LONG | 4 | 0 | 106.4 | 340.9 | 19420 | 2670.6 | 2.9 |
| WATER | SHORT | 4 | 0 | 0.7 | 11.4 | 36 | 212.2 | 0.3 |
| WATER | MEDIUM | 4 | 0 | 2.2 | 3.7 | 308 | 1073.9 | 0.7 |
| WATER | LONG | 4 | 0 | 5.4 | 8.7 | 811 | 2168.6 | 1.4 |
| BREAK_PLACE | SHORT | 4 | 0 | 81.7 | 106.8 | 13624 | 321.8 | 2.3 |
| BREAK_PLACE | MEDIUM | 4 | 0 | 2628.8 | 6514.5 | 413837 | 1925.6 | 16.7 |
| BREAK_PLACE | LONG | 4 | 1 | 4764.4 | 10018.7 | 711851 | 3881.9 | 45.6 |

Perf: 161539 nodes/sec, total 80983 ms, 651 MB allocated, max peak heap 425 MB, mean path length 255.1, heap max 3072 MB, cpus 8.

All: median 7.4 ms, p95 4764.4 ms, failures 1 of 60 queries, 2 reps.

Score (travel-first): failure rate 1.7%, mean travel 1383.5 ticks, mean time-to-arrive 1393.8 ticks (travel + plan).

