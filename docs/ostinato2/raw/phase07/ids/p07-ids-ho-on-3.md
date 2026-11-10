## p07-ids-ho-on-3 (wa:2)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 0.4 | 25.5 | 34 | 183.4 | 0.5 |
| FLAT | MEDIUM | 4 | 0 | 1.5 | 2.5 | 234 | 916.7 | 0.6 |
| FLAT | LONG | 4 | 0 | 3.0 | 5.3 | 491 | 1797.6 | 1.3 |
| HILLS | SHORT | 4 | 0 | 3.1 | 47.5 | 618 | 257.9 | 0.7 |
| HILLS | MEDIUM | 4 | 0 | 4.8 | 13.4 | 848 | 1262.1 | 1.0 |
| HILLS | LONG | 4 | 0 | 23.1 | 100.2 | 3006 | 2397.6 | 2.5 |
| CAVES | SHORT | 4 | 0 | 12.5 | 5821.2 | 2648 | 1277.8 | 42.8 |
| CAVES | MEDIUM | 4 | 0 | 2134.6 | 4055.6 | 401690 | 2348.2 | 13.6 |
| CAVES | LONG | 4 | 2 | 426.8 | 10007.2 | 97116 | 2506.2 | 13.3 |
| WATER | SHORT | 4 | 0 | 2.7 | 6.7 | 288 | 193.8 | 0.3 |
| WATER | MEDIUM | 4 | 0 | 2.6 | 4.7 | 257 | 1034.2 | 0.7 |
| WATER | LONG | 4 | 0 | 4.2 | 13.0 | 557 | 2093.1 | 1.2 |
| BREAK_PLACE | SHORT | 4 | 0 | 48.3 | 242.3 | 10086 | 358.2 | 2.9 |
| BREAK_PLACE | MEDIUM | 4 | 0 | 4818.2 | 8020.9 | 943015 | 1963.7 | 16.7 |
| BREAK_PLACE | LONG | 4 | 3 | 10001.0 | 10002.9 | 1930176 | 3932.8 | 38.3 |

Perf: 191586 nodes/sec, total 298837 ms, 1636 MB allocated, max peak heap 548 MB, mean path length 245.0, heap max 3072 MB, cpus 8.

All: median 6.7 ms, p95 10000.7 ms, failures 5 of 60 queries, 3 reps.

Score (travel-first): failure rate 8.3%, mean travel 1332.4 ticks, mean time-to-arrive 1350.4 ticks (travel + plan).

