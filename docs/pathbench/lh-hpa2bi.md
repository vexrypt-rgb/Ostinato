## lh-hpa2bi (hpa:0:1:2:2:1:bi)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 127.1 | 132.8 | 42 | 277.0 | 5.5 |
| FLAT | MEDIUM | 4 | 0 | 205.5 | 220.2 | 272 | 907.7 | 11.5 |
| FLAT | LONG | 4 | 0 | 324.3 | 337.7 | 620 | 1806.9 | 19.2 |
| HILLS | SHORT | 4 | 0 | 1.9 | 18.7 | 338 | 228.9 | 0.7 |
| HILLS | MEDIUM | 4 | 0 | 900.1 | 2171.3 | 99461 | 1233.6 | 50.2 |
| HILLS | LONG | 4 | 0 | 2101.1 | 2552.6 | 248010 | 2218.8 | 87.7 |
| CAVES | SHORT | 4 | 0 | 1.3 | 6.4 | 244 | 262.4 | 0.4 |
| CAVES | MEDIUM | 4 | 0 | 340.7 | 711.4 | 18128 | 1868.9 | 23.3 |
| CAVES | LONG | 4 | 0 | 537.9 | 715.1 | 30238 | 2584.7 | 32.9 |
| WATER | SHORT | 4 | 0 | 0.4 | 231.1 | 36 | 212.2 | 2.4 |
| WATER | MEDIUM | 4 | 0 | 479.4 | 715.3 | 23560 | 1040.1 | 25.2 |
| WATER | LONG | 4 | 0 | 1122.5 | 1441.4 | 94435 | 2042.7 | 55.4 |
| BREAK_PLACE | SHORT | 4 | 0 | 76.9 | 91.9 | 13624 | 321.8 | 3.7 |
| BREAK_PLACE | MEDIUM | 4 | 0 | 1970.2 | 2307.6 | 303938 | 1925.6 | 88.9 |
| BREAK_PLACE | LONG | 4 | 0 | 4232.0 | 5275.2 | 648493 | 3851.0 | 188.9 |

All: median 340.5 ms, p95 4099.6 ms, failures 0 of 60 queries, 3 reps.

Score (travel-first): failure rate 0.0%, mean travel 1385.5 ticks, mean time-to-arrive 1403.1 ticks (travel + plan).

