# Long-horizon planning bench

Same PathBench worlds and 60 queries as `baseline.md` (5 terrains × short/medium/long × 4), 3 reps, one JVM,
10 s timeout per query. Raw rows are in `lh-*-raw.csv`; `lh-astar-fingerprint.txt` pins the query set.

| config | finder spec | median ms | p95 ms | failures | mean travel (ticks) | time-to-arrive (ticks) |
|---|---|---|---|---|---|---|
| stock A* | `astar` | 149.6 | 10010.2 | 4 / 60 | 1202.1 | 1221.1 |
| weighted A* w=2 | `wa:2` | 10.7 | 9932.2 | 3 / 60 | 1328.9 | 1341.0 |
| hierarchy, 1 level | `hpa:0:1:1:2:1` | 253.7 | 3837.9 | 0 / 60 | 1385.1 | 1403.2 |
| hierarchy, 1 level, bidirectional | `hpa:0:1:1:2:1:bi` | 153.3 | 3602.5 | 0 / 60 | 1385.5 | 1399.8 |
| hierarchy, 2 levels | `hpa:0:1:2:2:1` | 477.2 | 3849.2 | 0 / 60 | 1385.1 | 1404.9 |
| hierarchy, 2 levels, bidirectional | `hpa:0:1:2:2:1:bi` | 340.5 | 4099.6 | 0 / 60 | 1385.5 | 1403.1 |

Mean travel only counts the queries that config solved, so the A* figure leaves out its 4 hardest queries.

Long queries, median ms:

| terrain | A* | hierarchy | hierarchy + bidirectional |
|---|---|---|---|
| FLAT | 15.2 | 219 | 102.5 |
| HILLS | 4119 | 2116 | 1769 |
| BREAK_PLACE | 4 of 4 time out | 4506 | 3694 |

On open flat ground plain A* is still fastest; the hierarchy pays off where A* floods hills or digging.

## What this decided

- **Bidirectional abstract search is on by default** (`pathingHierarchyBidirectional = true`). The route has the
  same cost as the one-sided backward Dijkstra (checked by `BidirectionalRouteTest` on every bench world) and the
  hierarchy plans faster with it: median 253.7 → 153.3 ms on one level. It only applies while `pathingHierarchical`
  is on and `pathingHierarchyHeuristicFactor` is 0.
- **The hierarchy itself stays off by default.** It removes every failure and cuts p95 from about 10 s to 3.6 s, but
  its routes run about 4% longer than weighted A* and its median is slower. Turn it on for long trips across hard
  terrain (`#pathingHierarchical true`).
- **One level beats two** on this bench; `pathingHierarchyLevels` stays 1.
- **No Rust port for the abstract search.** Section edges are priced lazily through Java block lookups (the same
  reason the A* loop stays in Java, docs/RUST_NATIVE.md §1.1), so a native search would cross the FFM boundary for
  every section it prices. The hierarchy's time is in pricing sections and the block-level corridor search, both of
  which read Java blocks.
