## p07-ids-dev-on-2 (wa:2)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 0.5 | 2.3 | 42 | 277.0 | 0.2 |
| FLAT | MEDIUM | 4 | 0 | 1.6 | 3.4 | 222 | 910.2 | 0.6 |
| FLAT | LONG | 4 | 0 | 3.1 | 12.3 | 473 | 1812.6 | 1.2 |
| HILLS | SHORT | 4 | 0 | 3.3 | 29.0 | 338 | 228.9 | 0.6 |
| HILLS | MEDIUM | 4 | 0 | 25.0 | 425.9 | 3692 | 1331.2 | 2.0 |
| HILLS | LONG | 4 | 0 | 20.9 | 44.7 | 3646 | 2447.4 | 2.1 |
| CAVES | SHORT | 4 | 0 | 1.3 | 6.6 | 244 | 262.4 | 0.3 |
| CAVES | MEDIUM | 4 | 0 | 121.1 | 842.7 | 25206 | 1852.8 | 4.4 |
| CAVES | LONG | 4 | 0 | 106.0 | 349.1 | 19420 | 2670.6 | 2.9 |
| WATER | SHORT | 4 | 0 | 0.4 | 9.8 | 36 | 212.2 | 0.3 |
| WATER | MEDIUM | 4 | 0 | 2.1 | 5.0 | 308 | 1073.9 | 0.7 |
| WATER | LONG | 4 | 0 | 4.8 | 8.2 | 811 | 2168.6 | 1.4 |
| BREAK_PLACE | SHORT | 4 | 0 | 71.9 | 81.9 | 13624 | 321.8 | 2.3 |
| BREAK_PLACE | MEDIUM | 4 | 0 | 2341.3 | 5783.9 | 413837 | 1925.6 | 16.7 |
| BREAK_PLACE | LONG | 4 | 1 | 4460.1 | 10003.5 | 711851 | 3881.9 | 46.4 |

Perf: 171688 nodes/sec, total 116154 ms, 986 MB allocated, max peak heap 424 MB, mean path length 255.1, heap max 3072 MB, cpus 8.

All: median 6.7 ms, p95 4460.1 ms, failures 1 of 60 queries, 3 reps.

Score (travel-first): failure rate 1.7%, mean travel 1383.5 ticks, mean time-to-arrive 1393.2 ticks (travel + plan).

