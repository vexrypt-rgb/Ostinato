# Hyperopt audit (Phase 0)

Date: 2026-11-08. Read-only inspection of `origin/main` at `67e12b96e` (Minecraft 26.3, Java 25, mod 1.24.0). No production code was changed for this audit. The optimization experiment lives on local branch `perf/hyperopt` in worktree `Documents/MinecraftDev/Ostinato-hyperopt`.

Measurements taken after this audit are in `HYPEROPT_BASELINE.md`, `HYPEROPT_QUEUE.md`, and `HYPEROPT_HANDOFF.md`. The block-read cache described below was measured and reverted. It is not the active experiment.

## Repository state

`main` is the primary line (26.3 / Java 25). Older lines: `1.21.11` and `1.21.4` (Java 21), `1.16.1` (Java 8), `conductor-module`. Native code exists only on 26.3.

The git dir has on the order of 60 worktrees under `Documents/MinecraftDev/Ostinato-*`. Many are dirty and belong to other efforts (maze, parkour, portals, PPE, swarm). This work does not touch them. `Ostinato-o2base` is a clean detached checkout of `origin/main` and was left clean. `perf/hyperopt` is a new local branch. Nothing has been pushed.

Protected branches were not modified. No commit has been made.

## What is already fast

Measured history in `docs/pathbench/` and `docs/RUST_NATIVE.md` (same laptop class, JDK 25, the 60-query PathBench: 5 terrains × short/medium/long × 4, 10 s timeout):

| Fact | Source | Status |
|---|---|---|
| Shipped Ostinato A* is about 5.5× stock Baritone on the paired geomean, median 6.6 ms vs 83 ms, 59/60 solved | `docs/pathbench/THREE_WAY.md` | Reported, not re-run here |
| The win is fewer nodes (far-segment weight), not cheaper nodes | same | Reported |
| Per node, shipped Ostinato is 6.01 µs and 203 bytes. Soprano is 3.58 µs and 84 bytes. Bare Ostinato A* is 6.70 µs and 260 bytes | same | Reported. This is the open gap |
| Open-set sift uses a parallel `double[]` key array (Soprano, about 15% in their notes) | `BinaryHeapOpenSet` | In tree |
| Node map is 16×8×16 cells with a one-cell lookaside | `AbstractNodeCostSearch` | In tree |
| Mining-duration memo, per thread, during `claimSearchCaches` | `SearchCache` | In tree. PathBench calls `calculate0` directly, so this memo is **not** on during the bench |
| `piCheapPathing` (default off): y-slices, pooled open set, 4096-slot block cache, edge skip. About 15% less allocation and 10–18% fewer nodes/s. Costs unchanged | `docs/pathbench/PI_CHEAP.md` | Reported. Kept off. The four cuts were not measured separately |
| Weighted A*, hierarchy, tick-accurate costs, momentum costs | `docs/pathbench/` | Hierarchy and tick costs stay off by default after their benches |
| Rust/FFM for TasBrute (3.1–4.9×), physics planner (1.4–1.9×), full jump grid (2.4–3.7×), momentum hop (2.4–3.0×), bit-identical, Java fallback | `docs/RUST_NATIVE.md` | Shipped. A* loop and block lookups explicitly rejected for native |

## Active execution paths

- Pathing: `AStarPathFinder.calculate0`. Each expansion calls `Moves.apply` (dozens of cost functions) which read the world through `CalculationContext.get` → `BlockStateInterface.get0`, and also through `bsi.get0` inside walkability checks.
- `PathBench` (the reproducible harness) calls `calculate0`, not `calculate`. A cache installed only in `claimSearchCaches` would not show up on the bench.
- Movement execution: `PathExecutor` plus kinematic controllers (`JumpSearch`, `MomentumHop`, `TakeoffController`). Local jump re-search stays Java; the full grid is native.
- Physics travel: `PhysicsPathfinder.plan`, native when `nativeAcceleration` is on.
- PPE / Grace Note / Bombard / Scherzo: `SecondoFeatures` looks them up by class name and does nothing if the class is absent. The implementations are not on `main`. They are on `feat/predictive-physics` (`Ostinato-ppe`, 15 commits ahead of this main, 2 behind). Not in this experiment.
- Combat, building, mining: present. Not the measured hot spot in the pathbench record.

## Level notes

1. **Micro.** Per-node time is the recorded gap versus Soprano. Soprano's `BlockStateInterface.get0` has a direct-mapped block cache on by default ("22 movements re-read the same ~50 cells ~110 times"). Ostinato's equivalent runs only under `piCheapPathing`.
2. **Algorithmic.** Far-segment weight already cuts expansions on hills and water. Caves medium got worse (410 ms vs stock 219 ms) because the greedy weight enters dead ends. Hierarchy fixes failures and p95 but lengthens routes and is off by default. Do not turn those on without a new bench.
3. **Architectural.** `piCheapPathing` bundled four ideas and lost throughput. Do not enable the bundle. Repeated world reads across moves are the concrete shared cost.
4. **Native.** Already used where a whole search fits in one FFM call. A* stays Java: costs are Java, goals are Java, TenorClef hooks are Java. A native A* with a Java cost upcall cannot win (`RUST_NATIVE.md` §1.1).
5. **Hardware.** The open-set key array is the cache-locality change already taken. SIMD is not justified until a scalar kernel is both hot and numeric. The block cache is scalar and data-local.

## Chosen experiment

**Block-read cache on every A* `get0`**, default on, `OSTINATO_BLOCK_CACHE=0` restores the old read.

Why this one: it is the largest measured per-node gap that is still open, it does not change path costs if the cached state is the state just read, and the previous attempt (`piCheapPathing`) mixed it with layout changes that slowed the search. This experiment is the cache alone.

Not chosen yet, and why:

- Native A*: rejected by the existing boundary analysis.
- Turning on hierarchy or tick costs: previous benches said no.
- PPE kernels: the code is not on this line.
- Re-enabling Presto/Legato-style movement experiments: the prompt says not to without new evidence. Nothing in this audit is that evidence.

## Unverified at audit time

- Fresh timings on this machine (Phase 1).
- Whether the cache's hit rate pays for the extra hash on short, flat searches.
- Live Minecraft tick cost. PathBench is headless. In-game navigation is not re-run here.
