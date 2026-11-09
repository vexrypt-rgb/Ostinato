## p07-hpa1bi-fw-warm (hpa:0:1:1:2:1:bi:fw)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 0.5 | 1.1 | 42 | 277.0 | 0.3 |
| FLAT | MEDIUM | 4 | 0 | 1.9 | 2.9 | 222 | 910.6 | 0.7 |
| FLAT | LONG | 4 | 0 | 3.9 | 6.4 | 471 | 1810.3 | 1.4 |
| HILLS | SHORT | 4 | 0 | 1.7 | 15.0 | 338 | 228.9 | 0.6 |
| HILLS | MEDIUM | 4 | 0 | 21.7 | 475.2 | 3692 | 1363.3 | 2.3 |
| HILLS | LONG | 4 | 0 | 21.0 | 52.2 | 3602 | 2445.9 | 2.3 |
| CAVES | SHORT | 4 | 0 | 1.3 | 5.7 | 244 | 262.4 | 0.4 |
| CAVES | MEDIUM | 4 | 0 | 113.3 | 812.9 | 24320 | 1901.2 | 4.3 |
| CAVES | LONG | 4 | 0 | 70.4 | 343.2 | 15458 | 2670.6 | 2.9 |
| WATER | SHORT | 4 | 0 | 0.3 | 9.6 | 36 | 212.2 | 0.3 |
| WATER | MEDIUM | 4 | 0 | 2.4 | 4.1 | 308 | 1079.6 | 0.9 |
| WATER | LONG | 4 | 0 | 5.8 | 10.8 | 811 | 2166.5 | 1.8 |
| BREAK_PLACE | SHORT | 4 | 0 | 69.6 | 114.4 | 13624 | 321.8 | 2.4 |
| BREAK_PLACE | MEDIUM | 4 | 0 | 1728.6 | 3211.4 | 319127 | 1925.6 | 11.5 |
| BREAK_PLACE | LONG | 4 | 0 | 2499.8 | 6254.0 | 438719 | 3851.0 | 29.1 |

Perf: 185739 nodes/sec, total 72953 ms, 733 MB allocated, max peak heap 277 MB, mean path length 257.4, heap max 3072 MB, cpus 8.

All: median 6.7 ms, p95 2499.8 ms, failures 0 of 60 queries, 3 reps.

Score (travel-first): failure rate 0.0%, mean travel 1428.5 ticks, mean time-to-arrive 1436.6 ticks (travel + plan).

