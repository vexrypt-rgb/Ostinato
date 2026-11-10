# OAP Phase 2 — pre-registration: does background planning harm live client responsiveness?

Written 2026-11-10 ~03:45, after two pilot runs (`sched-pilot-e1`, `sched-pilot2-e1`) and **before** any decisive run.
Not edited after a decisive run started. Any later change goes in an appended, dated section.

Revision: `706c1b473` plus uncommitted harness-only changes (`src/main/java/baritone/harness/PlanLoad.java`, the
`arms=planload` arm of `LegatoLiveTest`, `docs/performance/tools/oap_live_launch.ps1`, `oap_sched_summary.py`,
`oap_thread_prio.ps1`). No production code path changes unless `-Dostinato.legatotest.arms=planload` is set.

## Hypothesis

**H1.** On a CPU-constrained client (4 logical processors, two other busy programs), one continuously running
production planner search, the most production planning runs at once (PathingBehavior keeps at most one search in
progress), makes the client tick slower: pooled client tick p95 rises by at least the minimum meaningful effect.

H0: the rise is below the minimum meaningful effect.

## Design

One live client per run. Course: `LegatoLiveTest` timed arms on SimEdgeCourse, legs slalom / ridges / bumps / steps,
about 300 ticks per lap, arm order a Latin square within each (rep, leg) block. Each block holds one lap of each arm:

| arm | planner load |
|---|---|
| A | none |
| B | 1 search kept running (dose 1, the production maximum) |
| C | none (A/A′ control: the noise floor) |
| D | 3 searches kept running (dose 3, beyond production: dose-response only) |

The load (`PlanLoad`) is production A* on the production pool (`Baritone.getExecutor()`, workers at Java priority 4),
built like a plan-ahead (`CalculationContext(baritone, true)`, `Favoring`, far-segment weight, 4000/5000 ms budgets),
from a standing position 40 blocks west of the course toward a goal 4000 blocks further west. Results are thrown away.
`pathingMaxChunkBorderFetch` is unbounded in every arm of a planload run, so each search runs to its budget (pilot 1
showed stock far searches stop at the loaded edge in ~30 ms; that finding is reported separately). A finished search
is replaced in the next client tick.

## Environment

Machine: Intel i5-1145G7 (4 cores / 8 logical), Windows 11 Pro 26300, AC power, Balanced plan. JDK 25.0.4 (Temurin),
G1, client `-Xmx1536M`, Minecraft 26.3 Fabric dev client via `gradlew :fabric:runClient`. Client: 60 FPS cap, vsync
off, render distance 16 / simulation 12 (as the client actually ran in both pilots; the intended 8/8 did not take
effect), muted, second monitor, no mouse grab, Peaceful.

- **E1 (decisive):** launcher affinity 0x0F (logical 0–3: two physical cores with their SMT siblings), inherited by
  Gradle and the client; `-XX:ActiveProcessorCount=4` (JDK 25 on Windows otherwise reports 8 whatever the affinity);
  two Normal-priority Python busy loops pinned to the same processors. With 4 processors visible, Minecraft leaves
  the Render and Server threads at Java priority 5 (verified in 26.3 bytecode: `Minecraft.run` raises the game thread
  to 10 and `MinecraftServer` the server thread to 8 only when `availableProcessors() > 4`), as on a real 4-thread
  machine.
- **E0 (context, not decisive):** no affinity, no stress, 8 processors visible.

Launch: `oap_live_launch.ps1 -tag e1-main -seed 93011 -reps 6 -mask 0F -stress 2`, then
`oap_live_launch.ps1 -tag e0-main -seed 93012 -reps 6`. During each run, `oap_thread_prio.ps1` is sampled a few
times (read-only) to record Windows base/current priorities of the Render, Server, worker and GC threads.

## Outcomes

- **Primary:** pooled client tick p95 (TickClock, `Minecraft.tick` HEAD→RETURN), B − A, in E1.
- **Secondary:** tick p99, tick mean, frame-interval p95/p99, long frames (> 25 ms) share, integrated-server tick
  p95, lap ticks ratio (traversal), D − A (dose-response), the same contrasts in E0.
- **Mechanism (for choosing an intervention, not for the H1 decision):** per-tick flags: 1 = the load built a search
  in that tick (client-thread construction cost, measured ~3.4 ms each in pilot 2), 2 = a GC pause ended since the
  previous tick. "Clean" ticks (flag 0) isolate CPU/SMT/power competition from build cost and GC pauses. Worker/
  Render/Server/process CPU per lap, GC pauses and pause ms per minute.

Statistics: nearest-rank quantiles pooled over all laps of an arm (each lap's first tick dropped: it holds the lap
setup); 95% CI by block bootstrap over (rep, leg) blocks, 2000 resamples, `numpy.random.default_rng(20261110)`. The
(rep, leg) block holding the run's first lap is a warm-up block (JIT, world load: pilot lap 0 took ~560–600 ticks vs
~290) and is left out. Tool: `oap_sched_summary.py docs/performance/raw <tag>`.

## Noise and minimum meaningful effect

Pilots, A/A′ (C − A) tick p95: +0.36 ms (pilot 1, 4 blocks), +0.35 ms [−1.46, +1.76] (pilot 2, 3 blocks; A p95
11.3 ms). Per-lap A tick p95 in pilot 2: 10.3–11.9 ms.

- **Minimum meaningful effect (MME), tick p95: +1.0 ms** (~9% of E1's A p95; ~3× the A/A′ point estimates; a
  6% share of a 16.7 ms frame).
- MME for secondary outcomes: tick p99 +3 ms; long-frame share +2 percentage points; frame p95 +2 ms.

## Validity checks (a run failing any of these is invalid, not evidence)

1. ≥ 95% of laps in every arm REACHED; no death; keys stuck 0.
2. ≥ 95% of laps with `loadLeft=0` (no search of an earlier lap still running at lap start).
3. The dose ran: worker cores in B exceed A by ≥ 0.25.
4. The control is quiet: the C − A tick p95 95% CI contains 0.

An invalid run may be repeated once after a fix; a second invalid run stops the study (infrastructure finding).

## Decision rule (H1, E1, primary outcome)

- **Supported:** B − A tick p95 ≥ +1.0 ms and its 95% CI lower bound > 0.
- **Unsupported:** B − A tick p95 point estimate < +1.0 ms.
- **Inconclusive:** point ≥ +1.0 ms but CI lower bound ≤ 0: one extension run (`e1-ext`, seed 93013, 6 reps), blocks
  pooled with `e1-main`, rule applied once more; still not Supported → Unsupported.

E0 and D are reported, never used to rescue H1.

## What happens next

- **Unsupported:** no production scheduling change. Document and stop.
- **Supported:** the channel decides, from the B − A contrasts:
  - **Build ticks** carry it (clean-tick B − A p95 < half the all-tick B − A p95, and flag-1 ticks are where B's
    excess sits): the cost is construction on the client thread, not scheduling. At production cadence (one build per
    replan, seconds apart) it reaches p99, not p95. Documented; no scheduling change.
  - **GC** carries it (flag-2 ticks): the remedy is allocation reduction inside the search, which this phase excludes
    (no pathfinding optimisation). Documented; no change.
  - **Clean ticks** carry it (CPU/SMT/power): one candidate only, chosen with `oap_thread_prio.ps1` evidence. If
    planner workers are seen at a Windows current priority ≥ the Render thread's (dynamic boosts), candidate I1 =
    planner workers at Java `MIN_PRIORITY` on ≤ 4 visible processors behind a disabled-by-default setting. If not,
    no in-scope remedy exists (no new threads, native code, affinity or sleeps in planning loops): documented; no
    change.
- **Intervention gate (I1 only):** a new E1 run with the setting on (`e1-i1`, seed 93014) must reduce B − A tick p95
  by ≥ 50% of `e1-main`'s B − A estimate with CI upper bound of the reduced effect < +1.0 ms, with guardrails: lap
  ticks ratio B/A within 1.00 ± 0.02, load searches per minute in B not below 80% of `e1-main` (planning still runs),
  validity checks pass. Then one held-out E1 run (`e1-i1-ho`, seed 93015) must pass the same gate. Any failure: the
  setting is reverted, and with one candidate only, the study stops.

## Stopping conditions

- H1 Unsupported → stop.
- Two invalid runs, or a harness that needs more than one further fix → stop (infrastructure finding).
- Two failed interventions → stop (only one is pre-registered, so the first failure stops).
- The client cannot be launched or instrumented reliably → stop before any production change.
