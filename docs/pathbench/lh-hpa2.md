## lh-hpa2 (hpa:0:1:2:2:1)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 157.9 | 219.8 | 42 | 277.0 | 7.8 |
| FLAT | MEDIUM | 4 | 0 | 269.7 | 324.9 | 275 | 907.7 | 17.9 |
| FLAT | LONG | 4 | 0 | 417.1 | 518.0 | 1012 | 1806.9 | 27.9 |
| HILLS | SHORT | 4 | 0 | 2.0 | 16.5 | 338 | 228.9 | 0.7 |
| HILLS | MEDIUM | 4 | 0 | 1116.9 | 2743.1 | 99461 | 1233.6 | 53.8 |
| HILLS | LONG | 4 | 0 | 2213.9 | 2634.5 | 254513 | 2213.7 | 93.9 |
| CAVES | SHORT | 4 | 0 | 1.5 | 6.3 | 244 | 262.4 | 0.4 |
| CAVES | MEDIUM | 4 | 0 | 525.4 | 763.9 | 18128 | 1868.9 | 29.4 |
| CAVES | LONG | 4 | 0 | 648.0 | 881.0 | 30238 | 2584.7 | 39.5 |
| WATER | SHORT | 4 | 0 | 0.4 | 277.8 | 36 | 212.2 | 3.6 |
| WATER | MEDIUM | 4 | 0 | 533.6 | 945.8 | 22723 | 1040.1 | 29.3 |
| WATER | LONG | 4 | 0 | 1337.2 | 1762.2 | 94435 | 2042.7 | 61.8 |
| BREAK_PLACE | SHORT | 4 | 0 | 77.2 | 117.2 | 13624 | 321.8 | 3.7 |
| BREAK_PLACE | MEDIUM | 4 | 0 | 2268.2 | 3047.4 | 313933 | 1925.6 | 97.4 |
| BREAK_PLACE | LONG | 4 | 0 | 4267.3 | 6322.4 | 648493 | 3851.0 | 203.8 |

All: median 477.2 ms, p95 3849.2 ms, failures 0 of 60 queries, 3 reps.

Score (travel-first): failure rate 0.0%, mean travel 1385.1 ticks, mean time-to-arrive 1404.9 ticks (travel + plan).

