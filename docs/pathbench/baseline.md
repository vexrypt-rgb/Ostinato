## baseline (stock A*)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 6.7 | 17.6 | 771 | 290.5 | 0.7 |
| FLAT | MEDIUM | 4 | 0 | 9.1 | 118.9 | 807 | 920.1 | 3.3 |
| FLAT | LONG | 4 | 0 | 116.2 | 212.8 | 13167 | 1825.4 | 10.0 |
| HILLS | SHORT | 4 | 0 | 4.5 | 33.6 | 467 | 232.4 | 0.9 |
| HILLS | MEDIUM | 4 | 0 | 1513.2 | 6752.6 | 128412 | 1237.3 | 119.1 |
| HILLS | LONG | 4 | 0 | 5843.5 | 10001.0 | 408751 | 2219.1 | 244.0 |
| CAVES | SHORT | 4 | 0 | 1.8 | 47.0 | 247 | 320.9 | 0.6 |
| CAVES | MEDIUM | 4 | 0 | 278.6 | 1605.3 | 21627 | 1920.5 | 17.9 |
| CAVES | LONG | 4 | 0 | 367.2 | 874.0 | 38571 | 2607.7 | 20.6 |
| WATER | SHORT | 4 | 0 | 1.8 | 18.0 | 180 | 215.1 | 0.4 |
| WATER | MEDIUM | 4 | 0 | 303.8 | 729.0 | 29506 | 1041.2 | 18.8 |
| WATER | LONG | 4 | 0 | 1876.0 | 2738.6 | 190734 | 2069.4 | 100.8 |
| BREAK_PLACE | SHORT | 4 | 0 | 82.4 | 124.9 | 13877 | 328.8 | 6.6 |
| BREAK_PLACE | MEDIUM | 4 | 1 | 6651.6 | 10006.0 | 616316 | 1933.4 | 404.4 |
| BREAK_PLACE | LONG | 4 | 4 | 10000.3 | 10145.1 | 669184 | NaN | 496.3 |

All: median 177.5 ms, p95 10000.3 ms, failures 5 of 60 queries, 3 reps.

