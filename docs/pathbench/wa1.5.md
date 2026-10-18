## wa1.5 (wa:1.5)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 3.5 | 7.3 | 771 | 290.5 | 0.3 |
| FLAT | MEDIUM | 4 | 0 | 1.2 | 2.0 | 252 | 922.3 | 0.2 |
| FLAT | LONG | 4 | 0 | 2.3 | 3.6 | 502 | 1829.1 | 0.5 |
| HILLS | SHORT | 4 | 0 | 2.8 | 14.9 | 467 | 232.4 | 0.4 |
| HILLS | MEDIUM | 4 | 0 | 39.9 | 1774.3 | 5833 | 1291.3 | 2.7 |
| HILLS | LONG | 4 | 0 | 46.0 | 121.4 | 9966 | 2363.5 | 1.7 |
| CAVES | SHORT | 4 | 0 | 1.6 | 29.7 | 247 | 320.9 | 0.3 |
| CAVES | MEDIUM | 4 | 0 | 91.4 | 745.7 | 13971 | 1928.9 | 6.0 |
| CAVES | LONG | 4 | 0 | 64.7 | 238.5 | 10053 | 2631.5 | 1.9 |
| WATER | SHORT | 4 | 0 | 1.3 | 14.0 | 180 | 215.1 | 0.2 |
| WATER | MEDIUM | 4 | 0 | 2.3 | 3.2 | 363 | 1058.2 | 0.2 |
| WATER | LONG | 4 | 0 | 14.4 | 29.2 | 1759 | 2164.2 | 0.8 |
| BREAK_PLACE | SHORT | 4 | 0 | 64.7 | 116.5 | 13877 | 328.8 | 1.7 |
| BREAK_PLACE | MEDIUM | 4 | 0 | 2478.4 | 3539.3 | 340680 | 1936.0 | 41.2 |
| BREAK_PLACE | LONG | 4 | 2 | 9956.3 | 10003.0 | 1291200 | 3860.2 | 125.6 |

All: median 13.4 ms, p95 8487.8 ms, failures 2 of 60 queries, 3 reps.

