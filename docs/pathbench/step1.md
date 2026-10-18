## step1 (astar)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 5.6 | 10.6 | 771 | 290.5 | 0.3 |
| FLAT | MEDIUM | 4 | 0 | 5.7 | 60.1 | 807 | 920.1 | 1.6 |
| FLAT | LONG | 4 | 0 | 69.5 | 108.1 | 13167 | 1825.4 | 4.8 |
| HILLS | SHORT | 4 | 0 | 4.1 | 21.8 | 467 | 232.4 | 0.4 |
| HILLS | MEDIUM | 4 | 0 | 1039.1 | 4047.2 | 128412 | 1237.3 | 27.0 |
| HILLS | LONG | 4 | 0 | 3312.2 | 3946.5 | 408751 | 2218.9 | 58.7 |
| CAVES | SHORT | 4 | 0 | 1.1 | 63.4 | 247 | 320.9 | 0.3 |
| CAVES | MEDIUM | 4 | 0 | 111.0 | 667.1 | 21627 | 1920.5 | 9.8 |
| CAVES | LONG | 4 | 0 | 196.8 | 390.4 | 38571 | 2607.7 | 9.7 |
| WATER | SHORT | 4 | 0 | 0.9 | 7.4 | 180 | 215.1 | 0.2 |
| WATER | MEDIUM | 4 | 0 | 141.5 | 372.3 | 29506 | 1041.2 | 7.1 |
| WATER | LONG | 4 | 0 | 1109.3 | 2109.5 | 190734 | 2069.4 | 30.5 |
| BREAK_PLACE | SHORT | 4 | 0 | 64.8 | 117.2 | 13877 | 328.8 | 1.7 |
| BREAK_PLACE | MEDIUM | 4 | 0 | 4634.9 | 6837.1 | 616316 | 1936.0 | 71.7 |
| BREAK_PLACE | LONG | 4 | 4 | 9999.7 | 10018.3 | 1478784 | NaN | 169.6 |

All: median 111.0 ms, p95 9999.2 ms, failures 4 of 60 queries, 3 reps.

