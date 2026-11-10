## p07-ids-ho-off-4 (wa:2)

| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |
|---|---|---|---|---|---|---|---|---|
| FLAT | SHORT | 4 | 0 | 0.4 | 26.2 | 34 | 183.4 | 0.5 |
| FLAT | MEDIUM | 4 | 0 | 1.6 | 2.6 | 234 | 916.7 | 0.6 |
| FLAT | LONG | 4 | 0 | 3.1 | 3.4 | 491 | 1797.6 | 1.3 |
| HILLS | SHORT | 4 | 0 | 3.2 | 50.6 | 618 | 257.9 | 0.7 |
| HILLS | MEDIUM | 4 | 0 | 4.7 | 13.6 | 848 | 1262.1 | 1.0 |
| HILLS | LONG | 4 | 0 | 15.5 | 103.2 | 3006 | 2397.6 | 2.5 |
| CAVES | SHORT | 4 | 0 | 12.6 | 5892.3 | 2648 | 1277.8 | 42.8 |
| CAVES | MEDIUM | 4 | 0 | 2087.9 | 4139.6 | 401690 | 2348.2 | 13.6 |
| CAVES | LONG | 4 | 2 | 427.4 | 10001.5 | 97116 | 2506.2 | 12.8 |
| WATER | SHORT | 4 | 0 | 1.7 | 5.6 | 288 | 193.8 | 0.3 |
| WATER | MEDIUM | 4 | 0 | 1.7 | 4.0 | 257 | 1034.2 | 0.7 |
| WATER | LONG | 4 | 0 | 4.0 | 6.8 | 557 | 2093.1 | 1.2 |
| BREAK_PLACE | SHORT | 4 | 0 | 49.4 | 204.0 | 10086 | 358.2 | 2.9 |
| BREAK_PLACE | MEDIUM | 4 | 0 | 4918.1 | 8240.6 | 943015 | 1963.7 | 16.7 |
| BREAK_PLACE | LONG | 4 | 3 | 10000.7 | 10003.2 | 1875328 | 3932.8 | 37.4 |

Perf: 187441 nodes/sec, total 302390 ms, 1620 MB allocated, max peak heap 492 MB, mean path length 245.0, heap max 3072 MB, cpus 8.

All: median 6.1 ms, p95 10000.9 ms, failures 5 of 60 queries, 3 reps.

Score (travel-first): failure rate 8.3%, mean travel 1332.4 ticks, mean time-to-arrive 1350.9 ticks (travel + plan).

