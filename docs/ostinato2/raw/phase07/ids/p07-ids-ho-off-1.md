## p07-ids-ho-off-1 (wa:2)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 0.5 | 27.8 | 34 | 183.4 | 0.5 |
| FLAT | MEDIUM | 4 | 0 | 1.6 | 3.0 | 234 | 916.7 | 0.6 |
| FLAT | LONG | 4 | 0 | 3.3 | 4.9 | 491 | 1797.6 | 1.3 |
| HILLS | SHORT | 4 | 0 | 3.3 | 39.5 | 618 | 257.9 | 0.7 |
| HILLS | MEDIUM | 4 | 0 | 4.8 | 9.8 | 848 | 1262.1 | 1.0 |
| HILLS | LONG | 4 | 0 | 16.0 | 121.3 | 3006 | 2397.6 | 2.5 |
| CAVES | SHORT | 4 | 0 | 13.1 | 6182.7 | 2648 | 1277.8 | 42.8 |
| CAVES | MEDIUM | 4 | 0 | 2185.6 | 4324.0 | 401690 | 2348.2 | 13.6 |
| CAVES | LONG | 4 | 2 | 450.8 | 10002.6 | 97116 | 2506.2 | 12.3 |
| WATER | SHORT | 4 | 0 | 3.1 | 5.7 | 288 | 193.8 | 0.3 |
| WATER | MEDIUM | 4 | 0 | 2.1 | 2.9 | 257 | 1034.2 | 0.7 |
| WATER | LONG | 4 | 0 | 3.9 | 7.3 | 557 | 2093.1 | 1.2 |
| BREAK_PLACE | SHORT | 4 | 0 | 51.8 | 198.1 | 10086 | 358.2 | 2.9 |
| BREAK_PLACE | MEDIUM | 4 | 0 | 5293.6 | 8711.6 | 943015 | 1963.7 | 16.7 |
| BREAK_PLACE | LONG | 4 | 3 | 10000.7 | 10003.1 | 1805376 | 3932.8 | 36.0 |

Perf: 179013 nodes/sec, total 310347 ms, 1597 MB allocated, max peak heap 462 MB, mean path length 245.0, heap max 3072 MB, cpus 8.

All: median 7.0 ms, p95 10000.8 ms, failures 5 of 60 queries, 3 reps.

Score (travel-first): failure rate 8.3%, mean travel 1332.4 ticks, mean time-to-arrive 1351.8 ticks (travel + plan).

