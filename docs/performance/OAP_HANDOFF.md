# OAP (Ostinato Adaptive Performance) handoff

Evidence labels: **Verified** (inspected or measured here), **Reported** (documented elsewhere, not re-checked),
**Unknown** (not enough evidence), **Failed** (tested, did not work). Tiers follow `docs/ostinato2`: **Tier S** is
headless (planner ticks, synthetic worlds), **Tier L** is a live 26.3 client. Nothing in this file is Tier L.

## 1. Revision and working tree

- Worktree `Documents/MinecraftDev/Ostinato-oap`, local branch `perf/oap`, created from `ostinato2/claude-review` at
  `706c1b473` (2026-11-09 23:12, Phase 10 final). This is the only tree with LEGATO, PRESTO, PPE and Grace Note.
- Not committed, not pushed, not merged. `Ostinato-o2review` and every other worktree were not modified.
- Machine for every number here: i5-1145G7 (4 cores, 8 threads, laptop), 16 GB, JDK 25.0.4, Windows 11. About 2.3 GB
  free commit during the runs (other sessions running), so test heaps were capped at 1536 MB (`-PbenchXmx=1536m`).

## 2. Control inventory (Stage 0)

Each row: where it is read, whether it can change at runtime, who consumes it, when a change lands, correctness effect,
restoration. "OAP?" says whether OAP may move it.

| Control | Read at | Runtime? | Consumer / thread | Change lands | Correctness | OAP? |
|---|---|---|---|---|---|---|
| `planAheadPrimaryTimeoutMS` (4000) | `PathingBehavior.findPathInNewThread` L782, once per search | yes | plan-ahead A* on `Baritone.getExecutor()` (background) | next plan-ahead search; a running search keeps its captured value | only binds once a partial path > 5 blocks exists (`!failing`); returns stock `bestSoFar`. Escalation (`plannedStarts` doubling) still lifts it to the failure budget | **candidate** (Verified); studied, no profile passed its gates (sections 4, 6) |
| `primaryTimeoutMS` (500) | same, L779, first search (`current == null`) | yes | first A* search; the bot stands still while it runs | next first search | same partial-path rule; shorter = shorter first segment | not moved (Stage 1 holds it fixed) |
| `failureTimeoutMS` (2000), `planAheadFailureTimeoutMS` (5000), `failureTimeoutMaxMS` (16000) | same, L780-790 | yes | the budget for searches with no usable path yet, and the escalation cap | next search | decide whether a hard route is found at all (BREAK_PLACE/CAVES escalate to 8-16 s in Phase 07) | **excluded**: reliability (Verified) |
| `pathingFarSegmentWeight` (2.0) | `PathingBehavior.farSegmentWeight`, per search | yes | A* heuristic weight on segments ≥ 128 blocks from goal | next search | paths stay valid; cost up to w× optimal. Owner-approved default (Reported: ~5.8× faster, +2.4% cost) | second candidate, not studied (Stage 3 rule: one parameter first) |
| `planningTickLookahead` (150) | `PathingBehavior` L368 | yes | when plan-ahead starts | next segment | timing, not effort | excluded |
| `costHeuristic`, `pathingMaxChunkBorderFetch`, `minimumImprovementRepropagation` | A* setup | yes | A* semantics | next search | change costs or loaded-border handling | excluded: algorithm semantics |
| `PhysicsTravel.NODE_BUDGET` (1500), `CorrectionPolicy.RETRY_NODES` | `static final` | **no** | physics driver, client thread | rebuild | movement driver; Phase 06A's slip reliability depends on the retry | excluded: not runtime, safety |
| Kinematic rollouts, `LOOKAHEAD`, sim travel probe | constants in the drivers | no | client thread, every driving tick | rebuild | movement correctness | excluded |
| PRESTO, LEGATO, PPE, SimEdge, Maestro, Cadenza | boolean flags, all default false | yes | various | various | experimental, unvalidated | excluded: gates stay as they are (`simEdgeMaxStates`, `maestroMaxStates` belong to off features) |
| Grace Note, collision, physics prediction | — | — | — | — | safety | never touched |
| `mineGoalUpdateInterval`, `builderTickScanRadius` | per tick in Mine/Farm/GetToBlock/Builder | yes | client thread | next tick | change when goals refresh (behaviour) | excluded: Unknown cost, behaviour change |
| `render*` path visualisations (on by default) | render thread | yes | frame time | next frame | none (display) | Unknown cost; needs Tier L; not studied |
| `spectateBudgetMs` (1.5) | spectate decoder thread | yes | an off-by-default feature | next tick | none | out of scope |
| planner thread pool | `Baritone` L67, `min(4, cpus)` threads | no | all searches | — | — | excluded (no new threads rule) |

Where the cost actually is (Verified from committed Phase 07 and Phase 10 raw data, this repo):

- **Client thread, production defaults (Run P, `travelStack=auto`):** `kinNs` per lap 64-139 ms over ~340 ticks, about
  0.2-0.4 ms per tick against a tick p50 of 5.8-7.9 ms. With the physics stack forced (Phase 04), `physUs` is
  0.8-1.5 s per ~300-tick lap (3-5 ms per tick). Neither has a runtime budget knob.
- **Planner thread (Phase 07 held out, 850 PROD searches, 400 s of planning):** the time budget binds almost only on
  BREAK_PLACE, CAVES and MAZE, where plan-ahead searches run their full 4 s (BREAK_PLACE known: 99.6 s in 27
  plan-ahead searches, 81 s of it in timed-out ones). OPEN, FOREST, MOUNTAINS, WATER and MIXED together used under
  3 s: those searches reach the goal or the load horizon in milliseconds and no budget binds.
- So the planner is already workload-adaptive in one sense: easy terrain costs nothing to begin with. The only
  substantial optional compute is the time a hard plan-ahead search keeps spending after it already holds a partial
  path. That is the one control Stage 1 studies.

Measurement available at runtime without new instrumentation: per search, wall time (start/end in
`findPathInNewThread`), `timedOut()`, `exhausted()`, `getNumNodesConsidered()`. Client tick time only through the
harness `TickClock` (`-Dostinato.tickClock=true`).

## 3. Calibration methodology (Stage 1)

Pre-registered in `docs/performance/OAP_PREREG.md` before any sweep data. The candidate was fixed there after the dev
set and before any held-out output was read.

- **Instrument (Tier S):** `LongRouteSimTest.corpus`, the Phase 07 segmented-trip simulator. Every search is the
  production `AbstractNodeCostSearch.calculate` on a finder built the way `createPathfinder` builds it (arm `PROD`), and
  the budgets come from a line-for-line copy of `findPathInNewThread`'s budget logic (`LongRouteSim.budgets`, reading
  the same settings, escalation included). Segments are walked in their movement costs (planner ticks).
  A plan-ahead search is hidden behind the walk that is left, and planning the bot must stand still for is `wait`
  (wall ms / 50). Not modelled: the executor, the dead set, mobs, chunk loading latency.
- **Workloads:** 8 synthetic terrain families (OPEN, FOREST, MOUNTAINS, WATER, MIXED, CAVES, MAZE, BREAK_PLACE) × 3 route
  lengths (500, 1000, 2000 blocks) × 4 modes (`known` goal in loaded chunks, `horizon` load horizon reveals as the bot
  walks, `change` world changes on the route, `goal` goal switch mid-trip). 58 routes per seed.
- **Arms:** the same routes run under named budget arms (`OSTINATO_LONGROUTE_BUDGETS`), interleaved per route with the
  order reversed on alternate routes, after a warm-up pass of every arm. Only the plan-ahead primary budget differs
  (a8000 also raises the plan-ahead failure budget to 8000, or the primary could not bind).
- **Metrics per trip:** `cpuMs` (planner thread CPU, `ThreadMXBean.getCurrentThreadCpuTime` around each `calculate`;
  the sim runs searches on the test thread, so this is the search's CPU), `planMs` (wall), allocation, `total` (travel
  + wait ticks), `ok` (reached). Windows thread-CPU resolution is ~15.6 ms per reading; the ratios pool hundreds of
  searches, and wall time is reported beside it.
- **Statistics:** routes paired across arms by (set, seed, family, mode, length, n). Ratios of sums (pooled), 95%
  percentile bootstrap over paired trips, 10,000 resamples, `random.Random(20261109)`. Script:
  `docs/performance/tools/oap_budget_summary.py <dir> <label>`.
- **Sets:** dev seed 2000 (Phase 07's dev seed, used to choose). Held out seeds 4101 and 4102 (never used before, run
  once after the candidate was fixed).
- **Command:** `OSTINATO_LONGROUTE=<label> OSTINATO_LONGROUTE_SET=<dev|heldout> OSTINATO_LONGROUTE_SEEDS=<seeds>
  OSTINATO_LONGROUTE_ARMS=PROD OSTINATO_LONGROUTE_MODES=known,horizon,change,goal OSTINATO_LONGROUTE_BUDGETS=<arms>
  gradlew --offline --no-daemon --no-build-cache cleanTest test -x nativeHost -PbenchXmx=1536m --tests
  baritone.pathing.calc.LongRouteSimTest.corpus`. `--no-build-cache` matters: environment variables are not task
  inputs, so the first held-out launch was served from the cache of the dev run and produced no data (caught, re-run).
- **Hardware:** one machine (section 1). The budgets are wall clock, so the node count a budget buys is machine
  dependent. Measured throughput on the hard searches here is roughly 45-85 nodes/ms. A slower machine gets fewer
  nodes per budget, which makes the arms sweep a node-count curve too: a1000 here is about what base buys on a
  machine 4× slower. That is an inference, not a cross-hardware measurement.

## 4. Workloads and results

All **Verified, Tier S, this machine**. Raw CSVs, progress logs and summaries: `docs/performance/raw/oap-{dev,ho}-*`.
CPU split by search kind: `raw/oap-cpu-decomposition.txt` (`tools/oap_cpu_decomposition.py`).

**Dev (seed 2000, 58 routes per arm, used to choose):**

| arm | reached | cpu s | cpu ratio [95% CI] | total ratio [95% CI] | worst family total | timed out (plan-ahead) |
|---|---|---|---|---|---|---|
| base | 58/58 | 140.1 | — | — | — | 26 (15) |
| a2000 | 58/58 | 111.1 | 0.793 [0.695, 0.907] | 1.0006 [0.9985, 1.0036] | MAZE 1.0082 | 36 (24) |
| a1000 | 58/58 | 93.3 | 0.666 [0.556, 0.800] | 1.0009 [0.9986, 1.0041] | MAZE 1.0088 | 44 (33) |
| a500 | 58/58 | 76.8 | 0.548 [0.440, 0.672] | 1.0045 [0.9995, 1.0119] | MAZE 1.0419 | 81 (69) |
| a8000 | 58/58 | 139.8 | 0.998 [0.887, 1.087] | 1.0004 | CAVES 1.0004 | 18 (6) |

**Held out (seeds 4101, 4102, 116 routes per arm, run once after the candidate was fixed):**

| arm | reached | cpu s | cpu ratio [95% CI] | total ratio [95% CI] | worst family total | wait ticks | verdict |
|---|---|---|---|---|---|---|---|
| base | 112/116 | 444.6 | — | — | — | 2617 | — |
| a2000 | 112/116 | 415.5 | 0.934 [0.845, 0.998] | 0.9991 [0.9969, 1.0009] | CAVES 1.0056 | 2602 | G2 fails |
| **a1000** | 112/116 | 378.2 | **0.851 [0.733, 0.939]** | 1.0005 [0.9989, 1.0023] | CAVES 1.0101 | 2543 | **G2 fails** |
| a500 | 112/116 | 341.2 | 0.767 [0.624, 0.871] | 1.0002 [0.9986, 1.0019] | CAVES 1.0101 | 2536 | G2 fails (not eligible) |
| a8000 | 112/116 | 492.8 | 1.108 [1.019, 1.210] | 0.9996 [0.9980, 1.0010] | MAZE 1.0035 | 2964 | MAX gate fails |

The same 4 trips fail in every arm (2 BREAK_PLACE, 2 CAVES). No arm lost or gained a trip, on either set.

**Where the held-out CPU went (seconds):**

| arm | all | trips that failed | escalated re-searches | plan-ahead stopped by primary budget | other plan-ahead | first searches |
|---|---|---|---|---|---|---|
| base | 444.6 | 119.2 | 87.5 | 128.0 | 93.0 | 17.0 |
| a1000 | 378.2 | 110.0 | 113.8 | 73.4 | 64.0 | 17.0 |
| a8000 | 492.8 | 135.7 | 53.7 | 173.6 | 113.3 | 16.6 |

(Dev had no failing trips, and only 15.5 s of escalated searches.)

Findings:

1. **Trip time does not depend on the plan-ahead primary budget.** Across a 16× range (500 to 8000 ms), pooled trip
   time stays within ±0.1% on held out and ±0.5% on dev, and every trip-time CI covers 1.00. The time a hard
   plan-ahead search spends after it holds a usable path does not shorten trips in this simulator (Verified, Tier S).
2. **The saving is real but smaller than the dev set suggested.** a1000 cut the CPU of budget-bound plan-ahead
   searches by 43% on held out (128.0 → 73.4 s). Two things dilute it. Trips that fail in every arm spend 27% of base
   CPU on failure and escalation budgets OAP may not touch. And a shorter primary budget leads to more re-searches from
   the same start (87.5 → 113.8 s).
3. **More budget is pure cost.** a8000 spends 11% more CPU and makes the bot wait 13% more ticks, for no trip-time gain.

## 5. Tradeoffs

- **Shorter plan-ahead primary budget (a2000 → a500):**
  - CPU saved: 7% to 23% on held out, 21% to 45% on dev.
  - 4% to 12% less allocation.
  - Slightly fewer wait ticks: a search that would run its full 4 s ends sooner when the bot reaches the segment end.
  - Cost: more timed-out searches (79 → 198), more escalated re-searches, and partial plan-ahead paths that are
    shorter or less refined. That cost is not visible in pooled trip time, but individual families move by up to +1%
    (CAVES held out) or +4% (MAZE, a500 on dev).
- **Longer budget (a8000):** +11% CPU, +13% wait, nothing gained.
- **Hardware:** the budget is wall time, so its node count scales with the machine. Two reasonable inferences
  (Unknown, not measured):
  - The default 4000 ms is generous on this laptop.
  - A machine about 4× slower already lives at roughly the a1000 point. One 8× slower is past a500, where the dev
    MAZE ratio started to move.
- **Safety:** none of the arms touched first-search, failure or escalation budgets (escalation only reacts to them). No
  arm lost a trip. Movement, collision, physics and Grace Note were never in scope.

## 6. Profiles and their exact behaviour

**No profile ships. The only behaviour is the existing one (BALANCED = production defaults, unchanged).** Verified:
`git diff` against `706c1b473` touches no file under `src/main` or `src/api`.

| profile | status | why |
|---|---|---|
| BALANCED | the baseline and only behaviour | production defaults: 500 / 2000 / 4000 / 5000 / 16000 ms |
| ECO | **not shipped (Failed, G2)** | fixed candidate a1000 kept trips and trip time but saved 0.851 [0.733, 0.939] of CPU on held out; the pre-registered bar was ≤ 0.75 with CI upper < 0.90 |
| MAX | **not shipped (Failed)** | a8000 bought no trip time on dev or held out, at +11% CPU on held out |
| AUTO | **not implemented** | section 7 |

For the record, the ECO implementation drafted during the held-out run (then removed) was:
- a `planningEffort` setting, default `balanced`;
- in `findPathInNewThread`, `primaryTimeout = min(planAheadPrimaryTimeoutMS, 1000)` for plan-ahead searches only;
- read before the escalation block, which is exactly the tested a1000 arm. One consequence of this placement: a retry
  from the same start doubles from 1000 ms, not 4000.

A user who wants the measured tradeoff today can lower `planAheadPrimaryTimeoutMS` themselves. That is the existing
setting and the same code path. The data above is the only evidence for doing so, and it is Tier S on one machine.

## 7. Controller status (Stages 3 and 4)

**AUTO is not implemented.** The stopping rules call for a controller only when evidence justifies one, and there is
none here:

- **No profile to switch between.** Stage 3 starts from a validated profile. None passed (section 6).
- **No signal to steer by.** The only cost OAP can cut is planner CPU on the background executor (`Ostinato worker`
  threads, below client-thread priority). Whether that CPU slows the client tick, frame time or battery on any machine
  is **Unknown**: nothing here measured it. On this machine (4C/8T) one search occupies one of eight hardware threads.
  Live tick noise in this repo is large (per-lap p95 swings 15-34 ms, D14), so a planner-induced tick effect needs a
  dedicated Tier L design to see.
- **The hardware direction is backwards from intuition.** Wall-clock budgets already give slower machines fewer nodes.
  A naive "slow hardware → cut budget" rule would cut quality where it is thinnest. The principled form is a
  node-count-normalised budget, `clamp(N / measured nodes-per-ms, floor, base)`. It needs throughput and quality
  measured on more than one machine before N can be chosen. **Unknown**, not attempted.
- **Workload awareness already exists (Stage 4).** On easy terrain (OPEN, FOREST, MOUNTAINS, WATER, MIXED) plan-ahead
  searches finish in milliseconds and no budget binds: together they used 6 s of 444.6 s of held-out base CPU. Any
  budget change only ever acts on hard terrain. A workload classifier would add a decision with nothing left to decide.

## 8. Tests and regressions

All Verified, this machine:

- `compileTestJava`: BUILD SUCCESSFUL after the production revert.
- `LongRouteSimTest` (class): 7 tests, 0 failures, 1 skipped. The skipped one is `corpus`, which is env-gated and ran
  twice as the instrument: dev BUILD SUCCESSFUL in 9 m 50 s, held out in 35 m 27 s, no OOM at a 1536 MB heap.
- `docs/ostinato2/tools/longroute_summary.py` still reads `p07-ho`: it reads columns by name. The new columns
  (`cpuMs` on trips, `cpuMs` and `allocMB` on searches) are appended last. Without `OSTINATO_LONGROUTE_BUDGETS`, arm
  names stay bare (`PROD`), so Phase 07 commands produce the same layout.
- **Unit suite not run.** No production file changed. The test-side changes are confined to `LongRouteSim` and
  `LongRouteSimTest`, which the two corpus runs and the class's own tests exercised.
- Regressions: none in production (no change). In test tooling: `LongRouteSim.Search` gained a final `cpuMs`
  component, and nothing else constructs it.

Changed files (uncommitted, worktree `Ostinato-oap`):
- `src/test/java/baritone/pathing/calc/LongRouteSim.java`: per-search thread CPU.
- `src/test/java/baritone/pathing/calc/LongRouteSimTest.java`: `OSTINATO_LONGROUTE_BUDGETS` arms and the new CSV
  columns.
- `docs/performance/`: this file, `OAP_PREREG.md`, `tools/oap_budget_summary.py`, `tools/oap_cpu_decomposition.py`,
  and `raw/`.

## 9. Live status

**Nothing in this study is Tier L.** No live client ran.

- During the sweeps, free commit memory was about 1.5 GB, too little for a 26.3 client beside the test JVM. The owner
  freed memory (about 4 GB free) as the held-out run finished. By then the candidate had failed its pre-registered
  gate, so no production change existed to validate live. Building a live harness for a change that will not ship was
  not done.
- No existing live harness exercises this budget. The SimEdge/Legato courses plan in 3-20 ms (BENCHMARKS, Phase 05
  diagnostic), where it never binds.
- A Tier L design, if one is wanted later:
  - **Fixture:** the sim's MAZE family built at y 200. Flat floor with full-height bedrock walls, 1-3 narrow gaps per
    wall every 48-72 blocks, about 400 × 128 blocks, side walls. Walking only (`allowBreak` and `allowPlace` off), so
    laps take minutes, need no world restore and pair cleanly. MAZE is where the shortest budget hurt most on dev.
  - **Laps:** alternate the goal end every lap so `plannedStarts` clears. The Phase 07 note records same-goal laps
    escalating budgets (500 → 8000 ms). ABBA arm order, `RouteReuse.clear()` per lap, Peaceful plus death detection.
  - **Per-lap measurements:** lap ticks, reached, planner CPU (`ThreadMXBean` over the `Ostinato worker` threads),
    planner wall time (`PlanStats.astarNs`), ticks waiting for a path, and client tick p50/p95 (`TickClock`, with a
    pooled per-tick statistic, not a per-lap p95).

## 10. Unsupported assumptions, blockers, next steps

- **Single machine** (i5-1145G7, Windows, JDK 25). No cross-hardware claim is made. The node-count argument in section
  5 is inference.
- **Tier S abstractions:** no executor, mobs or chunk-load latency; trip time in planner ticks.
- **Thread CPU granularity:** Windows `getCurrentThreadCpuTime` has about 15.6 ms resolution per reading. The ratios
  pool 389-915 searches per arm, and wall time (reported beside CPU) agrees within 2%.
- **The assumption behind OAP is unverified:** that background planner CPU costs the user something (tick time, frame
  time, battery, a shared server). If it costs nothing noticeable, there is nothing for a budget profile to save. This
  is the open question, and it is **Unknown**.
- **Blocked or not done:**
  - Tier L (section 9).
  - Second machine.
  - `pathingFarSegmentWeight`, the second candidate. Not studied, by the one-parameter rule.
- **Observation, out of OAP's scope:** on held out, the biggest planner CPU sinks are trips that fail in every arm
  (27% of base) and escalated re-searches (20%). Both belong to reliability machinery (failure budgets, escalation),
  which hard rule 3 keeps closed to OAP. If that CPU matters, it is a reliability question first.
- **Next highest-value action:** measure, live, whether background planning affects the client at all. Run a hard
  plan-ahead workload (the MAZE fixture above) on a 4-thread or smaller machine, and compare client tick time and
  process CPU with planning busy versus idle.
  - If there is no effect, close OAP: the planner already adapts to workload and nothing needs a profile.
  - If there is an effect, revisit with the measured 0.85 saving and the escalation interaction.
