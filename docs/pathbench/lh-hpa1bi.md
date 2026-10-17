## lh-hpa1bi (hpa:0:1:1:2:1:bi)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 13.4 | 16.3 | 42 | 277.0 | 0.5 |
| FLAT | MEDIUM | 4 | 0 | 49.8 | 64.4 | 241 | 907.7 | 1.6 |
| FLAT | LONG | 4 | 0 | 102.5 | 120.5 | 620 | 1806.9 | 3.3 |
| HILLS | SHORT | 4 | 0 | 2.2 | 16.5 | 338 | 228.9 | 0.7 |
| HILLS | MEDIUM | 4 | 0 | 704.3 | 1850.0 | 99461 | 1233.6 | 37.7 |
| HILLS | LONG | 4 | 0 | 1769.1 | 2200.9 | 248010 | 2218.8 | 70.5 |
| CAVES | SHORT | 4 | 0 | 1.3 | 5.8 | 244 | 262.4 | 0.4 |
| CAVES | MEDIUM | 4 | 0 | 153.3 | 472.2 | 18128 | 1868.9 | 12.3 |
| CAVES | LONG | 4 | 0 | 282.1 | 439.3 | 31965 | 2584.7 | 15.7 |
| WATER | SHORT | 4 | 0 | 0.4 | 51.5 | 36 | 212.2 | 0.6 |
| WATER | MEDIUM | 4 | 0 | 287.1 | 539.8 | 23560 | 1040.1 | 13.1 |
| WATER | LONG | 4 | 0 | 803.8 | 1148.0 | 94435 | 2042.7 | 38.2 |
| BREAK_PLACE | SHORT | 4 | 0 | 72.6 | 92.5 | 13624 | 321.8 | 3.7 |
| BREAK_PLACE | MEDIUM | 4 | 0 | 1746.0 | 1937.0 | 303938 | 1925.6 | 79.2 |
| BREAK_PLACE | LONG | 4 | 0 | 3694.4 | 5391.9 | 648493 | 3851.0 | 183.7 |

All: median 153.3 ms, p95 3602.5 ms, failures 0 of 60 queries, 3 reps.

Score (travel-first): failure rate 0.0%, mean travel 1385.5 ticks, mean time-to-arrive 1399.8 ticks (travel + plan).

