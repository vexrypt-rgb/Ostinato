## p07-hpa1bi (hpa:0:1:1:2:1:bi)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 11.9 | 14.7 | 42 | 277.0 | 0.3 |
| FLAT | MEDIUM | 4 | 0 | 43.9 | 47.8 | 241 | 907.7 | 1.0 |
| FLAT | LONG | 4 | 0 | 92.1 | 95.1 | 620 | 1806.9 | 2.1 |
| HILLS | SHORT | 4 | 0 | 2.6 | 17.0 | 338 | 228.9 | 0.6 |
| HILLS | MEDIUM | 4 | 0 | 674.6 | 2537.9 | 99461 | 1233.6 | 26.9 |
| HILLS | LONG | 4 | 0 | 1790.4 | 2585.9 | 248010 | 2218.8 | 49.6 |
| CAVES | SHORT | 4 | 0 | 1.3 | 6.1 | 244 | 262.4 | 0.4 |
| CAVES | MEDIUM | 4 | 0 | 150.0 | 435.4 | 18128 | 1868.9 | 8.4 |
| CAVES | LONG | 4 | 0 | 265.5 | 415.0 | 31965 | 2584.7 | 11.6 |
| WATER | SHORT | 4 | 0 | 0.4 | 52.7 | 36 | 212.2 | 0.4 |
| WATER | MEDIUM | 4 | 0 | 283.1 | 478.9 | 23560 | 1040.1 | 7.5 |
| WATER | LONG | 4 | 0 | 757.8 | 1139.0 | 94435 | 2042.7 | 22.7 |
| BREAK_PLACE | SHORT | 4 | 0 | 67.5 | 98.7 | 13624 | 321.8 | 2.4 |
| BREAK_PLACE | MEDIUM | 4 | 0 | 2069.2 | 2457.5 | 303938 | 1925.6 | 39.0 |
| BREAK_PLACE | LONG | 4 | 0 | 5117.1 | 6818.0 | 648493 | 3851.0 | 88.4 |

Perf: 132457 nodes/sec, total 154149 ms, 3135 MB allocated, max peak heap 526 MB, mean path length 253.9, heap max 3072 MB, cpus 8.

All: median 150.0 ms, p95 5012.8 ms, failures 0 of 60 queries, 3 reps.

Score (travel-first): failure rate 0.0%, mean travel 1385.5 ticks, mean time-to-arrive 1402.6 ticks (travel + plan).

