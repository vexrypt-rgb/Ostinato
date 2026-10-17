## lh-astar (astar)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 0.8 | 4.0 | 42 | 277.0 | 0.2 |
| FLAT | MEDIUM | 4 | 0 | 5.3 | 23.3 | 275 | 907.7 | 0.5 |
| FLAT | LONG | 4 | 0 | 15.2 | 36.6 | 1012 | 1806.9 | 1.2 |
| HILLS | SHORT | 4 | 0 | 3.8 | 50.5 | 338 | 228.9 | 0.6 |
| HILLS | MEDIUM | 4 | 0 | 1225.6 | 5061.9 | 119060 | 1220.9 | 53.4 |
| HILLS | LONG | 4 | 0 | 4119.3 | 5490.5 | 383899 | 2196.2 | 118.4 |
| CAVES | SHORT | 4 | 0 | 1.7 | 16.8 | 244 | 262.4 | 0.4 |
| CAVES | MEDIUM | 4 | 0 | 185.2 | 741.7 | 21516 | 1814.5 | 14.8 |
| CAVES | LONG | 4 | 0 | 285.0 | 504.0 | 36529 | 2584.7 | 17.9 |
| WATER | SHORT | 4 | 0 | 0.5 | 16.8 | 36 | 212.2 | 0.3 |
| WATER | MEDIUM | 4 | 0 | 241.8 | 727.2 | 23627 | 1028.3 | 11.8 |
| WATER | LONG | 4 | 0 | 1561.1 | 2665.9 | 169475 | 2042.6 | 54.8 |
| BREAK_PLACE | SHORT | 4 | 0 | 112.6 | 233.5 | 13624 | 321.8 | 3.7 |
| BREAK_PLACE | MEDIUM | 4 | 0 | 4384.5 | 6848.7 | 607396 | 1925.6 | 160.3 |
| BREAK_PLACE | LONG | 4 | 4 | 10013.5 | 10053.2 | 1424576 | NaN | 318.9 |

All: median 149.6 ms, p95 10010.2 ms, failures 4 of 60 queries, 3 reps.

Score (travel-first): failure rate 6.7%, mean travel 1202.1 ticks, mean time-to-arrive 1221.1 ticks (travel + plan).

