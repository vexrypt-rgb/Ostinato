# OAP Phase 2 — CPU-aware scheduling & runtime efficiency: handoff

Evidence labels: **Verified** (reproduced or measured in this phase), **Reported** (from earlier documents or
external knowledge, not re-measured), **Unknown**, **Failed** (tested, did not hold). Sources: *source* (code
inspection), *headless* (JUnit/sim runs, no client), *live* (a real Minecraft 26.3 client).

## 1. Revision and tree state

- Branch `perf/oap`, HEAD `706c1b473`, worktree `Ostinato-oap`. Nothing committed, pushed or merged in this phase.
- Uncommitted, this phase (harness and tools only; inert unless `-Dostinato.legatotest.arms=planload`):
  - `src/main/java/baritone/harness/PlanLoad.java` (new): the controlled planner load and CPU/GC counters.
  - `src/main/java/baritone/pathing/legato/LegatoLiveTest.java`: the `planload` timed arm (load per lap, frame
    intervals, integrated-server tick times, per-tick build/GC flags, per-lap CPU/GC fields).
  - `docs/performance/tools/oap_live_launch.ps1`, `oap_sched_summary.py`, `oap_thread_prio.ps1`,
    `oap_frame_explore.py` (exploratory, not pre-registered) (new).
  - `docs/performance/OAP_SCHEDULING_PREREG.md`, this file, `docs/performance/raw/sched-*` (raw run data).
- Uncommitted, earlier OAP session, preserved untouched: `src/test/java/baritone/pathing/calc/LongRouteSim.java`,
  `LongRouteSimTest.java`, `docs/performance/OAP_HANDOFF.md`, `OAP_PREREG.md`, `tools/oap_budget_summary.py`,
  `tools/oap_cpu_decomposition.py`, `raw/oap-*`.
- Production code (`src/main` outside the harness, `src/api`): no change (Verified: `git diff 706c1b473 -- src/api`
  empty; `src/main` diff is `LegatoLiveTest.java` only, plus the new `harness/PlanLoad.java`).
- Not tracked: `fabric/run/client/options.txt` (git-ignored run directory).

## 2. Previous OAP evidence

Verified (headless, re-run): `python docs/performance/tools/oap_budget_summary.py docs/performance/raw oap-ho`
reproduces every ratio in `OAP_HANDOFF.md`: ECO a1000 held-out planner-CPU ratio 0.851 [0.733, 0.939], which failed
G2 (needed ≤ 0.75 with CI upper < 0.90); MAX a8000 1.108 with no trip-time gain. No production change was shipped
(Verified: `git diff 706c1b473 -- src/main src/api` was empty before this phase). AUTO not implemented; no live run.
That study measured planner CPU, not client responsiveness: it cannot say whether planning harms the client. This
phase asks that question live. ECO/MAX budgets are not revisited.

## 3. Thread and workload classification

Source inspection unless marked. Pool: `Baritone.getExecutor()` = `ThreadPoolExecutor(min(4, cpus), MAX_VALUE, 60 s,
SynchronousQueue)` — no queue, every task gets a thread; daemon threads "Ostinato worker N" at Java priority
`NORM_PRIORITY − 1` (4; `MIN_PRIORITY` when ≤ 2 processors visible).

| Work | Thread | Trigger | Cost | Cancellation | Thread safety | Fallback | Class |
|---|---|---|---|---|---|---|---|
| Path execution, movement, input (`PathingBehavior.tickPath`, executor) | Render | every client tick | part of the ~8 ms tick (live) | n/a | client thread | n/a | **Critical** |
| Search construction (`findPathInNewThread`/`createPathfinder`: `CalculationContext`, `Favoring`, finder) | Render | each path calc (segment start, plan-ahead, replan, escalation) | ~3.4 ms per build warm, ~17 ms cold (live, E1, same constructors via `PlanLoad`) | n/a | snapshots client state, must stay on the client thread | n/a | **Latency-sensitive** |
| A* search (`calculate`) | worker | as above; budgets 4000/5000 ms, escalation up to 16000 ms | up to one core for the budget; live far searches end at the loaded edge (stock `pathingMaxChunkBorderFetch=50`) in ~30–160 ms (live, pilots) | `cancel()` flag per node; reset to false at `calculate()` start (stock race) | reads its own context snapshot; client reads `bestPathSoFar()` unsynchronized (stock), O(path length) | no path → wait / replan | first segment: **Latency-sensitive** (player waits); plan-ahead: **Deferrable** until the current segment nears its end |
| Search result hand-off | worker under `pathPlanLock` | search end | brief; `markDead` O(nodes) only on the exhausted branch | n/a | lock shared with `tickPath` | n/a | **Latency-sensitive** (holds a lock the tick takes) |
| Path render (`PathRenderer`) | Render | every frame | O(path length) | n/a | unsynchronized read (stock) | n/a | cosmetic |
| Chunk packing (`CachedWorld.PackerThread`) | worker, permanent | chunk load queue | per chunk `ChunkPacker.pack` | drops when queue > `chunkPackerQueueMaxSize` | own queue | cache only | **Deferrable** |
| Cache save (`CachedWorld`, `WorldData.onClose`) | worker | every 10 min; world close | disk I/O | n/a | own data | n/a | **Deferrable** |
| Section-graph prefetch (`GameEventHandler`) | worker | chunk populate, only with `pathingHierarchical` (default off) | per chunk | n/a | own context | n/a | **Deferrable** |
| Process rescans (Mine, GetToBlock, Farm), region load (Explore) | worker | periodic while those processes run | world scan | replaced by the next | own context | stale results | **Deferrable** |
| Physics predictions (`PredictionScheduler`) | "Ostinato physics", Java 4 | ThreatService / Bombard tickets (polled, never awaited) | per request | ticket cancel | job queue + cache | no prediction yet | **Latency-sensitive** (threat response); inactive in this course |
| Predictor warm-up | "ostinato-ppe-warmup" | once per JVM | short | n/a | n/a | n/a | **Deferrable** |
| Elytra solver / nether pathfinder executors, MovementTas frontier, SeedPlanning, SpectateLearner, KitBench | own threads | only in those features | not measured | — | — | — | **Unknown** (inactive in navigation) |
| Integrated server, G1 GC threads | Minecraft / JVM | — | — | — | — | — | not Ostinato's (Critical / JVM) |

## 4. Instrumentation

All live, all harness-only (`arms=planload`):

- **Client tick**: `TickClock` (`-Dostinato.tickClock`), `Minecraft.tick` HEAD→RETURN, every tick → `ticks.csv`.
- **Frame interval**: nanoTime between successive `LevelRenderer.render` RETURNs (`onRenderPass`) → `frames.csv`.
- **Integrated-server tick**: `MinecraftServer.getTickTimesNanos()` ring, every finished tick → `server.csv`.
- **CPU per lap**: `ThreadMXBean` CPU of "Ostinato worker*", "Render thread", "Server thread"; process CPU
  (`OperatingSystemMXBean`); GC pauses and pause ms (G1 Young + Old), concurrent GC ms.
- **Load**: searches started / finished, total search wall time, client-thread build time (`loadBuildUs`), searches
  still running at lap start (`loadLeft`).
- **Per-tick flags**: 1 = the load built a search in that tick; 2 = a GC pause ended since the previous tick.
- **Machine**: total CPU %, Processor Performance % (turbo), client process CPU %, every 5 s (`counters.csv`).
- **Threads as Windows sees them**: `oap_thread_prio.ps1` joins `jstack` to `Get-Process` threads by native id:
  Java priority, `os_prio`, Windows base and current priority, CPU time, top frames of Ostinato threads.
- **Platform priority mapping** (Verified on this machine, JDK 25.0.4, a stand-in JVM read with
  `oap_thread_prio.ps1`): Java 5 → `os_prio` 0 (NORMAL) → Windows base 8; Java 4 (Ostinato workers) → −1
  (BELOW_NORMAL) → base 7; G1 GC and JIT compiler threads → 2 (HIGHEST) → base 10. A thread back from a wait shows a
  current priority above its base (dynamic boost).
- **GPU-bound vs CPU-bound**: frame p50 sits at the 60 FPS cap (16.6 ms) in every arm; the Render thread is busy
  about half the wall time in E1 and a third in E0 (live): the client is not GPU-bound at this cap.

## 5. Pre-registered gates

See `OAP_SCHEDULING_PREREG.md` (written after the pilots, before any decisive run). Primary: pooled client tick p95,
B − A, in E1; MME +1.0 ms; Supported iff estimate ≥ +1.0 ms and CI lower bound > 0.

## 6. Baseline live results

Live, arm A (no planner load), warm-up block left out. E1 = 4 logical processors (2 cores + SMT), JVM told 4,
two Normal busy loops on the same processors; E0 = whole machine, 8 visible, no stress.

| | E1 `e1-main` (23 blocks) | E0 `e0-main` |
|---|---|---|
| Render / Server thread priority (Java → Windows base) | 5 → 8 / 5 → 8 | 10 → 10 / 8 → 9 |
| client tick p50 / p95 / p99 ms | 7.82 / 11.56 / 24.92 | 5.01 / 6.40 / 7.88 |
| frame p50 / p95 / p99 ms; FPS | 16.60 / 28.82 / 45.34; 56.7 | 16.66 / 23.29 / 24.99; 59.5 |
| integrated-server tick p50 / p95 ms | 6.65 / 13.73 | 3.75 / 5.41 |
| Render thread busy (CPU/wall); process cores (per-lap medians) | 0.51; 1.38 | 0.33; 0.86 |
| Ostinato worker cores, no load (per-lap median) | 0.31 | 0.16 |
| GC pauses/min; pause ms/min | 14.4; 248 | 12.4; 123 |
| machine CPU %; Processor Performance % (in-lap means) | 53; 138 | 17; 152 |
| lap ticks (median) | 289 | 295 |

Verified (live):

- **Not GPU-bound at this cap.** Frame p50 sits on the 60 FPS cap in both. The frames that carry a client tick
  (about one in three at 60 FPS / 20 TPS: p70–p80 of frame intervals; E1 23.7–25.5 ms, E0 22.0–22.4 ms) miss it.
- **Thread priorities as Minecraft sets them** (live, `raw/sched-*-threads.txt`): E1 (4 processors visible) Render
  and Server at Java 5 → Windows base 8, Ostinato workers Java 4 → base 7, seen at current 8 when boosted; E0
  (8 visible) Render Java 10 → base 10 (current 11), Server Java 8 → base 9, workers base 7 (current 7–8).
- **The chunk packer is the standing background load, not planning.** With no planner load, worker threads still
  use 0.31 cores in E1 and 0.16 in E0 (per-lap medians). `oap_thread_prio.ps1` stack samples (`raw/sched-e1-main-threads.txt`, 4 samples 2.5 min apart)
  show it is `Ostinato worker 1` = `CachedWorld.PackerThread` in `ChunkPacker.pack` / `CachedChunk.<init>`:
  21.8 → 65.0 → 117.7 → 174.0 s CPU, ~0.36 cores sustained (E0's one sample: the same thread, in
  `PalettedContainer.get`, 113.8 s). Inferred (source, not counted): each lap teleports the
  player ~95 blocks back (`LegatoLiveTest.java:481`), and at render distance 16 that unloads and reloads a band of
  chunks that the packer packs on load (`GameEventHandler` post-populate) and before unload. Ordinary travel churns
  chunks the same way, so the packer's cost scales with travel speed × render distance. It is Deferrable work at
  BELOW_NORMAL, the same in every arm.
- **Live planning is cheap by default.** With stock `pathingMaxChunkBorderFetch=50`, a far plan-ahead stops at the
  loaded edge: pilot 1 load searches averaged ~30 ms (first lap) to ~160 ms wall each. Long searches happen only when
  the goal is inside loaded terrain and hard to reach (mazes, escalation).
- **Search construction costs ~2–4 ms on the client thread** (`loadBuildUs` per build: E1 3.52 ms in B, 3.82 in
  D; E0 2.24 in B, 2.68 in D; ~17 ms cold, pilot 2). Production pays it once per path calc (`findPathInNewThread` runs the same constructors).

## 7. Contention findings

Pre-registered decision (E1, primary): **H1 Unsupported** (Verified, live).

| E1 `e1-main`, 23 blocks, B − A unless noted | estimate | 95% CI | MME | |
|---|---|---|---|---|
| **tick p95 (primary)** | **+0.25 ms** | [−2.03, +1.95] | +1.0 | below MME → Unsupported |
| tick p99 | +0.83 ms | [−4.88, +6.43] | +3.0 | |
| tick p50 | +0.49 ms | [+0.34, +0.67] | — | real, small |
| tick mean | +0.88 ms | [+0.44, +1.22] | — | real, small (~10%) |
| frame p95 / p99 | +0.82 / +2.09 ms | [−0.76, +1.84] / [−2.02, +5.95] | +2.0 | |
| long frames (> 25 ms) share | +8.4 pts | [+6.4, +10.4] | +2 pts | above MME, see below |
| server tick p95 | +0.42 ms | [−1.78, +2.19] | — | |
| clean-tick p95 (no build, no GC) | −0.02 ms | [−2.31, +1.51] | — | |
| lap ticks B/A | 1.002 | [0.998, 1.008] | — | traversal unchanged |
| control C − A tick p95 | +0.72 ms | [−0.53, +2.03] | — | contains 0: valid |
| dose 3, D − A tick p95 | +1.66 ms | [+0.13, +2.67] | — | beyond production; clean-tick D − A +0.45 [−0.85, +1.52] |

How strong this verdict is: the primary CI, [−2.03, +1.95], is four MMEs wide. E1's tail is wide and noisy under
the busy loops and the 4-processor limit (A tick p95 11.6 ms vs 6.4 in E0; control C − A CI [−0.53, +2.03] vs
[−0.18, +0.37] in E0), so E1 failed to detect an effect of
+1 ms; it does not show there is none. The verdict is the pre-registered one and stands; E0 below is the sharper
measurement of the same contrast.

Validity (pre-registered): 92/92 laps REACHED (96/96 with the warm-up block), no death, keys stuck 0, `loadLeft` 0 in every lap, control CI
contains 0. Dose check: per-lap median worker cores A 0.31, B 0.81 (+0.50), C 0.31, D 0.91. (The pooled per-arm
worker CPU in the summary table reads −0.22 for A: idle non-core pool threads expire after 60 s and their CPU leaves
the sum; per-lap medians are not affected by the few laps where that happens: A 3, B 1, C 0, D 0.)

What the load did (Verified, live): load searches ran 89% of wall time in B (13.2 s of 14.9 s per lap) and D, yet a
B search got only ~0.5 of a core and three D searches ~0.6 cores together: at BELOW_NORMAL (Windows base 7) they ran
on what the Normal threads (base 8: Render, Server, two busy loops) left. Build ticks were 1.1% of B's ticks (2.1% in
D); GC-flagged ticks 1.3% vs 1.2% in A; GC pause time per minute +26% (248 → 312 ms/min).

The long-frame secondary (exploratory reading, `tools/oap_frame_explore.py`, not pre-registered): mean frame
interval B − A +0.13 ms [−0.21, +0.39] (FPS 56.7 → 56.3; control C − A +0.18 [−0.01, +0.44]). The frames that carry
a client tick form a cluster at 23.7–25.5 ms (p70–p80) and the 25 ms threshold sits inside it, so the ~1 ms shift of
those frames (p70 23.7 → 25.4 ms) moves ~8% of all frames across the threshold. The pre-registered long-frame metric
was a badly placed threshold; the frame effect is the same ~0.5–1 ms tick slowdown seen in tick p50/mean, not a
separate frame-pacing harm.

Mechanism (Verified, live; exploratory): Render-thread CPU per frame 9.09 ms (A) → 9.91 ms (B, +9%) → 10.04 ms (D);
control C 9.22 (+1.4%). The client thread does the same work in more CPU time: it executes slower while a planner
runs. The extra ~0.8 ms of CPU per frame is enough to explain the ~0.5 ms tick median shift without any added
waiting. That fits SMT-sibling sharing (E1 has 2 cores × 2 threads) and/or a shared clock/power budget (in-lap
Processor Performance A 137.9%, B 132.9%, C 135.6%, D 130.2%). Added scheduling delay is not excluded: in thread
samples the Render thread was seen Ready (runnable, not running) in 2 of 4, with workers at current priority 8 (base
7 + boost), tying the Render thread's base 8. Its share was not quantified.

### E0: the whole machine (context; pre-registered as never decisive)

| E0 `e0-main`, 23 blocks, B − A unless noted | estimate | 95% CI | MME |
|---|---|---|---|
| tick p95 | +1.27 ms | [+0.96, +1.65] | +1.0 |
| tick p99 | +1.40 ms | [+0.96, +2.50] | +3.0 |
| tick p50 / mean | +0.29 / +0.44 ms | [+0.21, +0.39] / [+0.33, +0.58] | — |
| frame p95 / p99 | +1.02 / +1.97 ms | [+0.70, +1.39] / [+1.53, +2.35] | +2.0 |
| long frames (> 25 ms) share | +2.3 pts (A 1.0%) | [+1.5, +3.3] | +2 pts |
| mean frame interval (exploratory); FPS | +0.015 ms; 59.5 → 59.5 | [+0.005, +0.027] | — |
| server tick p95 | +0.83 ms | [+0.47, +1.13] | — |
| clean-tick p95 (no build, no GC) | +1.18 ms | [+0.83, +1.55] | — |
| lap ticks B/A | 0.996 | [0.992, 1.000] | — |
| control C − A tick p95 | +0.10 ms | [−0.18, +0.37] | — |
| dose 3, D − A tick p95; FPS | +4.03 ms; 59.0 | [+3.78, +4.23] | — |

Validity: 96/96 laps REACHED, no death, keys stuck 0, `loadLeft` 0 in every lap, control CI contains 0. Dose: per-lap
median worker cores A 0.16, B 1.15 (+0.99: a full core per search), C 0.16, D 3.14. Build ticks 1.1% (B, D); GC-flagged
1.0–1.1% in every arm.

Reading (Verified, live): **in the quiet, unconstrained environment, one continuously running search raises client
tick p95 by ~1.3 ms (6.4 → 7.7 ms, +20%) and the median by ~0.3 ms.** Applied to E0, the pre-registered rule would
read Supported (≥ +1.0 ms, CI lower +0.96 > 0). E0 was pre-registered as context and does not change the H1 verdict.
It does change what the verdict means: planning has a real client cost on this machine; E1 was too noisy to see it.

Channel, by the pre-registered branch for "Supported", applied for information: clean ticks carry it (clean-tick
+1.18 of all-tick +1.27), not build ticks or GC. The I1 precondition fails: in E0 the Render thread is at Windows base
10 (current 11) and workers at base 7 (current 7–8), so workers never reach the Render thread's priority, and I1 is
defined for ≤ 4 visible processors only. The pre-registered path would also end at "no in-scope remedy: documented;
no change".

Mechanism (Verified, live; exploratory): Render CPU per frame A 5.58 ms, B 6.04 (+8.2%), C 5.57, D 8.18 (+47%);
in-lap Processor Performance A 151.7%, B 146.4% (−3.5%), C 151.2%, D 121.5% (−20%); machine CPU A 17%, B 30%. With 8
logical processors under a third busy and the Render thread three base levels above the workers, there is no
run-queue wait to remove: the client thread does the same work in more CPU time. The shared turbo/power budget
accounts for ~3.5 of the ~8 points (the clock drop); the rest is Unknown and not separable with these counters (SMT
sibling sharing, shared L3 cache or memory bandwidth: an A* search walks a large node map).

E1 and E0 together (exploratory): the relative slowdown of the client thread is about the same in both (Render CPU per
frame +9% / +8%; tick p50 +6.2% / +5.8%), although a B search got half a core in E1 and a full core in E0. FPS at the
60 cap did not change in E0 and fell 0.4 in E1 (CI includes 0); ticks stay far inside the 50 ms tick budget. How
much of real play has a search running (planner duty) was not measured in this phase; with stock border fetch, live
searches mostly end in tens of milliseconds (§6). Whether shorter or fewer searches lower the client cost in
proportion is not measured either.

## 8. Intervention

None. H1 was Unsupported, so by the pre-registration no production scheduling change is made. The decision would be
the same had E0 been decisive (§7: clean-tick channel, I1 precondition not met). The measured mechanism (slower
execution on shared hardware, ~8–9% more Render CPU per frame at dose 1, with the Render thread already above the
workers in priority) has no in-scope scheduling remedy: a lower Java priority cannot keep a planner off the SMT
sibling, out of the shared cache or out of the power budget; per-thread affinity needs native code; pausing the
planner while the client ticks would put waits into planning loops; doing less search work is pathfinding
optimisation. All are excluded in this phase.

## 9. A/B and held-out results

No intervention, so no intervention A/B or held-out run. The A/B evidence is the planner-load contrast itself: B vs A
within each (rep, leg) block, 23 blocks per environment, with C as the A/A′ control. Pilots (`pilot-e1`, `pilot2-e1`)
shaped the harness and the MME and are not evidence for H1.

## 10. Safety and regression testing

- Production code: unchanged (§1). Every change is in the harness or tools and runs only with
  `-Dostinato.legatotest.arms=planload`; other `LegatoLiveTest` arms write the same files as before (the `flags`
  column is added to `*-ticks.csv` only for planload).
- Live runs: all laps reached in every arm of every run (pilots included); no deaths, no stuck keys; the load was
  stopped at every lap end (`loadLeft` 0 in every lap of the decisive runs).
- Build: every live run built this tree with `gradlew :fabric:runClient` (no compile errors; the planload arm ran and
  wrote the `flags` column). No unit tests cover the harness; none were added, because nothing in production changed.
  `gradlew test` was not run in this phase.
- Client etiquette: second monitor, no mouse grab, muted, Peaceful; Gradle and the client exit on their own; no
  `gradlew --stop`.

## 11. Uncertainties

- **One machine, one CPU** (i5-1145G7, Tiger Lake mobile, AC power, Balanced plan). The mechanism is
  shared hardware (clock/power, SMT, cache), so its size depends on the CPU: a desktop CPU with more power headroom or
  no SMT may show less, a smaller laptop CPU more. Unknown.
- **E1 emulates a 4-thread machine** with affinity on 2 SMT cores plus two pinned Normal busy loops; a real 4-core
  no-SMT CPU would not share cores between the planner and the client. E1 turned out too noisy at p95 to detect a
  ~1 ms effect (§7); the pre-registration did not foresee that its CI would be four MMEs wide.
- **The E0 effect is context**, measured once (23 blocks), not replicated; its CI is tight and the control is quiet.
- **The load is a stand-in**: one production A* search kept running at its plan-ahead budget, made possible only by
  unbounding `pathingMaxChunkBorderFetch`. Real live planning is mostly short (§6). Real planner duty in normal play,
  and whether the client cost scales with it, are Unknown.
- **The split of the slowdown** between clock (measured ~3.5 points of ~8) and SMT / cache / memory bandwidth (the
  rest) is Unknown.
- **Render distance 16 / simulation 12**, not the intended 8/8 (the option did not take; cause Unknown). Consistent
  across all runs.
- **Long-frame metric**: pre-registered, but its 25 ms threshold sits inside E1's tick-frame cluster (§7).
- **Chunk-packer churn attribution** to the lap teleport is inferred from source; the packer's CPU is measured.
- **Thread states** come from 4 + 1 point samples, not a trace; in E1 the share of time the Render thread waits on a
  boosted worker is Unknown.
- **The worker CPU sum** loses expired threads (§7); per-lap medians are used for the dose.

## 12. Recommendation

1. **Do not change planner scheduling.** The pre-registered result is H1 Unsupported (E1: +0.25 ms [−2.0, +1.9] vs an
   MME of +1.0). The quiet E0 context run shows a real cost, +1.3 ms tick p95 [+1.0, +1.6] with one search running
   continuously, but its mechanism is slower execution on shared hardware, with the Render thread already three
   priority levels above the workers. Thread priority cannot remove it, and the other levers (affinity, sleeps, less
   search work) are out of scope. Workers already run at BELOW_NORMAL on Windows (verified mapping).
2. **Measure planner duty in real play before any planner-cost work.** The cost is now known per running search on
   this machine (~8% more client-thread CPU per frame, ~0.3 ms tick median, ~1.3 ms tick p95). Whether it matters
   depends on how much of the time a search runs in normal sessions, which nobody has measured; with stock border
   fetch most live searches end in tens of milliseconds. A future phase could log search wall time per minute of play
   (`PlanStats.astarNs`, A* wall time, already summed in `AbstractNodeCostSearch.calculate` in production) and
   pre-register any change from that. Reducing planner CPU
   is not assumed to help the client; it would have to be measured.
3. **Look at the chunk packer too** if background CPU matters again: at render distance 16 it used 0.16–0.36 cores
   continuously while the course ran, more than typical live planning. It is Deferrable work, so batching or
   rate-limiting it is the natural candidate, after a pre-registered measurement of its client impact.
4. **Search construction on the client thread (~2–4 ms per path calc)** is the planner's one direct client-thread
   cost. At production cadence it reaches p99, not p95. Profiling what in `CalculationContext` / `Favoring`
   construction costs that much is cheap and could be a runtime-efficiency follow-up.
5. **Keep the harness** (`PlanLoad`, the `planload` arm, the tools): inert unless selected, and it is the only live
   instrument here that measures client tick, frame, server tick and per-thread CPU together. Future live A/B work on
   this machine should use the E0 setup or a quieter constraint than E1's busy loops, which made p95 too noisy.
   Uncommitted, pending the owner's decision.
6. If a future phase pre-registers frame pacing, use the mean frame interval or the tick-carrying-frame quantiles,
   not a fixed 25 ms threshold.
