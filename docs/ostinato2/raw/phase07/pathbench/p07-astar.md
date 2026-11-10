## p07-astar (astar)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 0.4 | 1.2 | 42 | 277.0 | 0.2 |
| FLAT | MEDIUM | 4 | 0 | 2.4 | 3.5 | 275 | 907.7 | 0.6 |
| FLAT | LONG | 4 | 0 | 8.4 | 18.4 | 1012 | 1806.9 | 1.3 |
| HILLS | SHORT | 4 | 0 | 3.7 | 26.9 | 338 | 228.9 | 0.6 |
| HILLS | MEDIUM | 4 | 0 | 801.1 | 3924.0 | 119060 | 1220.9 | 39.1 |
| HILLS | LONG | 4 | 0 | 2763.3 | 4174.8 | 383899 | 2196.2 | 81.1 |
| CAVES | SHORT | 4 | 0 | 1.5 | 6.3 | 244 | 262.4 | 0.3 |
| CAVES | MEDIUM | 4 | 0 | 133.5 | 451.9 | 21516 | 1814.5 | 11.0 |
| CAVES | LONG | 4 | 0 | 230.4 | 461.8 | 36529 | 2584.7 | 14.4 |
| WATER | SHORT | 4 | 0 | 0.4 | 7.8 | 36 | 212.2 | 0.3 |
| WATER | MEDIUM | 4 | 0 | 138.8 | 448.0 | 23627 | 1028.3 | 7.8 |
| WATER | LONG | 4 | 0 | 1156.7 | 2039.1 | 169475 | 2042.6 | 34.4 |
| BREAK_PLACE | SHORT | 4 | 0 | 72.8 | 105.3 | 13624 | 321.8 | 2.3 |
| BREAK_PLACE | MEDIUM | 4 | 0 | 5130.7 | 6719.4 | 607396 | 1925.6 | 80.4 |
| BREAK_PLACE | LONG | 4 | 4 | 10008.6 | 10017.6 | 1073728 | NaN | 134.8 |

Perf: 120841 nodes/sec, total 266745 ms, 4904 MB allocated, max peak heap 644 MB, mean path length 237.1, heap max 3072 MB, cpus 8.

All: median 126.6 ms, p95 10007.9 ms, failures 4 of 60 queries, 3 reps.

Score (travel-first): failure rate 6.7%, mean travel 1202.1 ticks, mean time-to-arrive 1219.6 ticks (travel + plan).

