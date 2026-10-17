## lh-hpa1 (hpa:0:1:1:2:1)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 30.1 | 51.0 | 42 | 277.0 | 0.6 |
| FLAT | MEDIUM | 4 | 0 | 110.1 | 161.2 | 275 | 907.7 | 2.3 |
| FLAT | LONG | 4 | 0 | 219.2 | 313.8 | 1012 | 1806.9 | 4.5 |
| HILLS | SHORT | 4 | 0 | 2.2 | 23.1 | 338 | 228.9 | 0.7 |
| HILLS | MEDIUM | 4 | 0 | 866.5 | 2725.1 | 99461 | 1233.6 | 38.3 |
| HILLS | LONG | 4 | 0 | 2115.7 | 3111.3 | 254513 | 2213.7 | 69.6 |
| CAVES | SHORT | 4 | 0 | 1.7 | 7.0 | 244 | 262.4 | 0.4 |
| CAVES | MEDIUM | 4 | 0 | 249.4 | 667.8 | 18128 | 1868.9 | 13.1 |
| CAVES | LONG | 4 | 0 | 372.1 | 601.8 | 30238 | 2584.7 | 16.1 |
| WATER | SHORT | 4 | 0 | 0.6 | 113.1 | 36 | 212.2 | 0.7 |
| WATER | MEDIUM | 4 | 0 | 379.1 | 742.2 | 22723 | 1040.1 | 13.6 |
| WATER | LONG | 4 | 0 | 1181.3 | 1780.6 | 94435 | 2042.7 | 39.1 |
| BREAK_PLACE | SHORT | 4 | 0 | 82.5 | 128.7 | 13624 | 321.8 | 3.7 |
| BREAK_PLACE | MEDIUM | 4 | 0 | 2162.4 | 3294.4 | 313933 | 1925.6 | 82.4 |
| BREAK_PLACE | LONG | 4 | 0 | 4505.8 | 7896.2 | 648493 | 3851.0 | 179.8 |

All: median 253.7 ms, p95 3837.9 ms, failures 0 of 60 queries, 3 reps.

Score (travel-first): failure rate 0.0%, mean travel 1385.1 ticks, mean time-to-arrive 1403.2 ticks (travel + plan).

