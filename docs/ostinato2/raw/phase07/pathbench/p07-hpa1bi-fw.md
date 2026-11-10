## p07-hpa1bi-fw (hpa:0:1:1:2:1:bi:fw)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 11.9 | 14.2 | 42 | 277.0 | 0.3 |
| FLAT | MEDIUM | 4 | 0 | 43.4 | 48.1 | 222 | 910.6 | 1.0 |
| FLAT | LONG | 4 | 0 | 87.3 | 93.6 | 471 | 1810.3 | 2.0 |
| HILLS | SHORT | 4 | 0 | 2.6 | 25.5 | 338 | 228.9 | 0.6 |
| HILLS | MEDIUM | 4 | 0 | 82.4 | 553.9 | 3692 | 1363.3 | 2.7 |
| HILLS | LONG | 4 | 0 | 129.8 | 150.0 | 3602 | 2445.9 | 3.1 |
| CAVES | SHORT | 4 | 0 | 1.4 | 6.2 | 244 | 262.4 | 0.4 |
| CAVES | MEDIUM | 4 | 0 | 152.8 | 859.0 | 24320 | 1901.2 | 4.5 |
| CAVES | LONG | 4 | 0 | 158.9 | 430.1 | 15458 | 2670.6 | 3.5 |
| WATER | SHORT | 4 | 0 | 0.4 | 52.5 | 36 | 212.2 | 0.4 |
| WATER | MEDIUM | 4 | 0 | 133.3 | 154.5 | 308 | 1079.6 | 1.3 |
| WATER | LONG | 4 | 0 | 225.2 | 249.8 | 811 | 2166.5 | 2.6 |
| BREAK_PLACE | SHORT | 4 | 0 | 66.6 | 76.9 | 13624 | 321.8 | 2.4 |
| BREAK_PLACE | MEDIUM | 4 | 0 | 1748.8 | 3215.7 | 319127 | 1925.6 | 11.9 |
| BREAK_PLACE | LONG | 4 | 0 | 2615.6 | 6353.8 | 438719 | 3851.0 | 29.9 |

Perf: 161529 nodes/sec, total 83887 ms, 798 MB allocated, max peak heap 273 MB, mean path length 257.4, heap max 3072 MB, cpus 8.

All: median 89.3 ms, p95 2615.6 ms, failures 0 of 60 queries, 3 reps.

Score (travel-first): failure rate 0.0%, mean travel 1428.5 ticks, mean time-to-arrive 1437.8 ticks (travel + plan).

