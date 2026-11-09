## p07-ids-ho-on-2 (wa:2)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 0.4 | 25.6 | 34 | 183.4 | 0.5 |
| FLAT | MEDIUM | 4 | 0 | 1.5 | 2.6 | 234 | 916.7 | 0.6 |
| FLAT | LONG | 4 | 0 | 3.1 | 5.4 | 491 | 1797.6 | 1.3 |
| HILLS | SHORT | 4 | 0 | 3.2 | 52.3 | 618 | 257.9 | 0.7 |
| HILLS | MEDIUM | 4 | 0 | 6.8 | 13.8 | 848 | 1262.1 | 1.0 |
| HILLS | LONG | 4 | 0 | 17.2 | 99.8 | 3006 | 2397.6 | 2.5 |
| CAVES | SHORT | 4 | 0 | 12.4 | 5661.1 | 2648 | 1277.8 | 42.8 |
| CAVES | MEDIUM | 4 | 0 | 2066.5 | 3943.7 | 401690 | 2348.2 | 13.6 |
| CAVES | LONG | 4 | 2 | 428.2 | 10001.9 | 97116 | 2506.2 | 13.5 |
| WATER | SHORT | 4 | 0 | 1.8 | 6.2 | 288 | 193.8 | 0.3 |
| WATER | MEDIUM | 4 | 0 | 2.4 | 4.6 | 257 | 1034.2 | 0.7 |
| WATER | LONG | 4 | 0 | 6.2 | 12.6 | 557 | 2093.1 | 1.2 |
| BREAK_PLACE | SHORT | 4 | 0 | 49.7 | 206.4 | 10086 | 358.2 | 2.9 |
| BREAK_PLACE | MEDIUM | 4 | 0 | 4791.9 | 7895.5 | 943015 | 1963.7 | 16.7 |
| BREAK_PLACE | LONG | 4 | 3 | 10000.8 | 10002.8 | 1949760 | 3932.8 | 38.4 |

Perf: 194642 nodes/sec, total 296287 ms, 1641 MB allocated, max peak heap 502 MB, mean path length 245.0, heap max 3072 MB, cpus 8.

All: median 7.3 ms, p95 10001.3 ms, failures 5 of 60 queries, 3 reps.

Score (travel-first): failure rate 8.3%, mean travel 1332.4 ticks, mean time-to-arrive 1350.1 ticks (travel + plan).

