# Pi-cheap pathfinding (`piCheapPathing`, default off)

`#set piCheapPathing true` plans for one core and about 512 MB of heap. Off, which is the default, the search is the usual one: the same 16×8×16 node cells, a new open set each time, and no block-read cache. Recorded path costs stay identical. `PathBenchTest.stockAStarMatchesGoldenCosts` covers that.

On, the route cost and the number of nodes expanded stay the same. Four cuts:

- Nodes sit in a 16×16 slice for each y the search touches, instead of a full 16×8×16 block of mostly empty slots.
- The open-set arrays stay on the pathing thread and are cleared after the search, so the next search does not allocate them again and does not keep the previous nodes.
- A 4096-slot cache remembers block reads for that search. A miss reads the world the same way as before.
- An edge into a node already reached for the same or a lower cost is not priced again. Every move the search accepts costs more than zero, so that edge cannot win. A move that picks its landing while it runs is still priced.

Unchecked-move offers are reused for the same search instead of allocated one by one.

Bench, from the repo root:

```
scripts/pathbench-phase1.sh <label> normal
scripts/pathbench-phase1.sh <label> pi
```

`OSTINATO_BENCH_PI=1` turns the setting on for that run. The `pi` profile is one core and `-PbenchXmx=512m`.

## PC bench 2026-10-25 (REPS=2, `--no-build-cache`)

Fingerprints: main == off == on for both normal and Pi profiles (Pi = 512m heap, 1 CPU). Path costs, failures (4/60, all BREAK_PLACE LONG timeouts) and travel ticks are identical.

| run | MB allocated | max peak heap | nodes/sec |
|---|---|---|---|
| main-pi (2 runs) | 2501 / 2743 | 429 / 513 MB | 86k / 128k |
| off-pi | 2751 | 461 MB | 130k |
| on-pi (2 runs) | 2354 / 2334 | 505 / 471 MB | 115k / 106k |
| off-normal | 2695 | 567 MB | 126k |
| on-normal | 2309 | 541 MB | 104k |

Peak heap is within noise of main; allocation is about 15% lower with on. Throughput is about 10-18% lower with on, so on is a memory-for-CPU trade.

Note: the bench must run with `--no-build-cache`. The `OSTINATO_BENCH*` env vars are not task inputs, so `:test` otherwise restores FROM-CACHE.
