## lh-wa2 (wa:2)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 0.6 | 3.7 | 42 | 277.0 | 0.2 |
| FLAT | MEDIUM | 4 | 0 | 1.9 | 9.5 | 222 | 910.2 | 0.5 |
| FLAT | LONG | 4 | 0 | 3.6 | 13.1 | 473 | 1812.6 | 1.0 |
| HILLS | SHORT | 4 | 0 | 5.0 | 40.7 | 338 | 228.9 | 0.6 |
| HILLS | MEDIUM | 4 | 0 | 45.8 | 746.2 | 3692 | 1331.2 | 4.3 |
| HILLS | LONG | 4 | 0 | 28.8 | 89.7 | 3646 | 2447.4 | 2.5 |
| CAVES | SHORT | 4 | 0 | 1.8 | 13.8 | 244 | 262.4 | 0.4 |
| CAVES | MEDIUM | 4 | 0 | 285.5 | 2397.1 | 25206 | 1852.8 | 16.1 |
| CAVES | LONG | 4 | 0 | 146.1 | 1430.3 | 19420 | 2670.6 | 7.9 |
| WATER | SHORT | 4 | 0 | 1.8 | 36.6 | 36 | 212.2 | 0.3 |
| WATER | MEDIUM | 4 | 0 | 2.6 | 9.3 | 308 | 1073.9 | 0.6 |
| WATER | LONG | 4 | 0 | 6.6 | 14.1 | 811 | 2168.6 | 1.3 |
| BREAK_PLACE | SHORT | 4 | 0 | 92.1 | 290.3 | 13624 | 321.8 | 3.7 |
| BREAK_PLACE | MEDIUM | 4 | 1 | 5094.6 | 10003.2 | 413837 | 1902.7 | 95.3 |
| BREAK_PLACE | LONG | 4 | 2 | 9852.2 | 10005.2 | 630620 | 3881.9 | 143.1 |

All: median 10.7 ms, p95 9932.2 ms, failures 3 of 60 queries, 3 reps.

Score (travel-first): failure rate 5.0%, mean travel 1328.9 ticks, mean time-to-arrive 1341.0 ticks (travel + plan).

