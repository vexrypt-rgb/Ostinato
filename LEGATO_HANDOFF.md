# LEGATO v1 — any-angle navigation handoff

Status labels: **Verified** (read in code / reproduced), **Reported** (from prior docs, not re-checked),
**Contradicted** (prior claim found false), **Unknown**, **Measured** (numbers from a run).

| Item | Value |
|---|---|
| Worktree | `Ostinato-ppe` |
| Branch | `feat/predictive-physics` |
| Base HEAD | `1aed5cc2` (Presto v4.1 work uncommitted on top — preserved, not touched) |
| Version target | 26.3 / Java 25 (this worktree only) |
| Default | `legato = false` |
| Changes | uncommitted, for review |

## Milestone status

| M | Scope | State |
|---|---|---|
| M0 | Audit + plan | done (this file) |
| M1 | Feasibility evaluator | done — `LegatoFeasibility`, 22 tests |
| M2 | Collision-aware smoothing | done — `LegatoSmoother` + `Path.smooth()`, 13 tests |
| M3 | Continuous-angle execution | done — `MovementStraight`, kinematic-drivable; live check in M6 |
| M4 | Hybrid Theta* (ground only) | done — `AStarPathFinder` parent substitution, 5 tests |
| M5 | Optimisation | done — evaluator 0 B / 20k checks; smoother O(1) per candidate (prefix sums), 0.28 ms on a 142-move route |
| M6 | Tests + live A/B | done — 45 new tests; live run 2: 80/80, B −2.4 % (target 5 % not met) |
| M7 | Handoff finalisation | done |

---

## M0 — Audit

### Integration points (Verified)

* **Path assembly** — `pathing/calc/Path.java`: `assembleMovements` → `runBackwards` matches each node pair to a
  `Moves` entry by offset; `postProcess()` runs on the calc thread after `sanityCheck()` with the
  `CalculationContext` available ("// more post processing here"). This is the one place where a route can be
  rewritten *before* any executor sees it → chosen seam for smoothing.
* **Search** — `pathing/calc/AStarPathFinder.calculate0` relax block (`neighbor.previous = currentNode; neighbor.cost
  = tentativeCost`). `PathNode.previous` is the parent pointer → Theta* parent substitution fits here with no
  second graph.
* **Execution** — `PathExecutor.tickOnce` driver chain: takeoff → KINEMATIC → kinematicFamily → PHYSICS (off) →
  SIM (jumps) → `Movement.updateState`. All input goes through `InputOverrideHandler`; look turns go through the
  aim processor (≤32°/tick). Legato adds **no** input writer: a new `MovementStraight` is just a movement.
* **Kinematic driver** — `KinematicController.drivable()` decides which movements the polyline driver steers;
  `buildLine` builds a polyline over up to 12 drivable moves; rollouts (PlayerSim, yaw offsets {0,±8,±20,±40})
  reject plans that fall below path or leave the WIDE=1.1 corridor. Adding `MovementStraight` to `drivable()`
  makes straights inherit rollouts, margins, turn gates, Presto seams and `syncPosition`.
* **Staleness** — `PathExecutor` recalculates the current movement's cost every tick and verifies upcoming movements
  up to `costVerificationLookahead`; `COST_INF` → cancel + replan + `RouteReuse.forget`. A `MovementStraight` whose
  `calculateCost` re-runs the evaluator is therefore invalidated by terrain change by construction.
* **Off-path** — `MAX_DIST_FROM_PATH`=2 / `MAX_MAX`=3 measured against all movements' valid positions; only on
  ticks no driver claimed. Straights must expose their swept corridor cells as valid positions.
* **Route reuse** — `RouteReuse` stamps block ids at path positions and keys reuse by `stamp()`
  (`CostCalibration.scaleStamp`). Legato mode must be folded into the stamp so an A route is never reused in D.
* **Settings** — `Settings.java` Presto block (~2270) is the pattern; `SettingCategorizer` line 61 movement list;
  `scripts/gen_setting_descriptions.py` regenerates `setting-descriptions.json` (SettingCatalogTest enforces).
* **Live harness** — `PrestoLiveTest` (state machine, fresh world seed 71006, server-side course build, laps via
  `setGoalAndPath`, `-Dostinato.prestotest.exit`) is the template for `LegatoLiveTest`; register in `TestHarness`.

### Reusable capabilities (Verified)

* `MovementHelper.canWalkThrough / canWalkOn / avoidWalkingInto / collisionTop / isBlockNormalCube`.
* `CalculationContext.get / isLoaded` (calc-thread safe world snapshot).
* `MovementLine` + `LineWalk` — knight (2×1) moves, cost = hypot × per-block × surface; template for a straight.
* Kinematic rollouts + corridor margins + turn gates (kinematic `turningAround` >90°, `PhysicsTravel.offPlan` 45°,
  `MovementHelper.walkGate` 60°, ChainJump/SimTravel 45°) — rotation-before-translation already enforced.
* Headless bench: `PathBench`, `SyntheticWorld`, `Terrain`, `AStarPathFinder` with `Favoring`.

### Missing capabilities (Verified)

* No arbitrary-angle segment beyond knight moves; A* paths are 8/16-connected zig-zags on open ground.
* No swept-volume feasibility check; existing checks are per-move cell checks.
* No smoothing pass; no shortcut failure memory.

### Risks

* **Executor handover** (Verified risk): smoothing only the kinematic polyline would desync valid positions,
  `syncPosition`, and the off-path guard → rejected; smoothing rewrites the `IPath` instead.
* **Cost model mismatch**: straight cost must use the same units as Traverse/Diagonal
  (WALK 4.633 / SPRINT 3.564 per block, `SPRINT_MULTIPLIER` 0.769) or A*/Theta* comparisons are meaningless.
* **Patience**: `DRIVER_PATIENCE` = 100 ticks per path position — straights capped at 12 blocks (~43 ticks sprint).
* **Movement construction is not headless** (`Movement` ctor calls `baritone.getPlayerContext()`); smoother core is
  therefore written over plain positions + callbacks so it is unit-testable.
* **Planner code must not call `Baritone.settings()`** in headless paths → flags go through `CalculationContext`.
* **Edge collisions**: a centre ray can graze corners — evaluator sweeps the full player box + margin.
* Memory headroom on the dev box is tight (~3.5 GB free virtual); builds at `-Xmx1536M`, client never concurrent.

### Proposed minimal implementation

1. `baritone.pathing.legato.LegatoFeasibility` — static, allocation-free swept-AABB check over a 2D segment at
   fixed feet y; verdicts FEASIBLE / INFEASIBLE / UNSUPPORTED / UNKNOWN. Flat only; span ≤ 12; half-width 0.3
   player + 0.2 margin; per cell: chunk loaded (else UNKNOWN), feet+head passable with empty collision shape, not
   fluid, not `avoidWalkingInto` (hazard → INFEASIBLE); doors/gates/climbables → UNSUPPORTED; floor full cube,
   `canWalkOn`, friction 0.6 / speed 1 / jump 1 (else UNSUPPORTED: ice, slime, soul sand, honey, slabs, stairs,
   carpet, snow layer); magma/lava floor → INFEASIBLE; recent failure memory → INFEASIBLE.
2. `LegatoSmoother` — greedy farthest-feasible shortcut over contiguous eligible flat walking movements
   (Traverse / Diagonal / Line with nothing to break or place), with a turn-penalty model; accepts only strict
   improvements.
3. `MovementStraight` — a movement whose cost re-runs the evaluator, valid positions = swept corridor cells,
   `updateState` = pure-pursuit fallback that turns before it walks. Added to `KinematicController.drivable()`.
4. Theta* parent substitution in `AStarPathFinder` behind `legatoSearch`, ground walking only; `Path` builds a
   `MovementStraight` for Theta* edges.
5. Settings `legato`, `legatoSmoothing`, `legatoSearch`, `legatoDebug`; `LegatoStats` counters; Legato bits in the
   reuse stamp; failure memory fed by `PathExecutor` cancel/timeout of straights.

### Baseline (Reported, from PRESTO_HANDOFF.md / docs/LEGATO_READINESS.md)

* Full suite 209 suites / 1,309 tests / 0 failures / 15 skipped.
* Presto v4.1: 72 live A/B laps, Presto default off. Evidence `docs/presto-validation/v4.1/`.
* Live baseline numbers for Legato's own course: see M6 (Measured, mode A).

---

## Implementation (Verified: compiled, unit-tested)

| File | Role |
|---|---|
| `api/.../Settings.java` | `legato` (off), `legatoSmoothing` (on), `legatoSearch` (off, experimental), `legatoDebug` (off) |
| `gui/model/SettingCategorizer.java`, `setting-descriptions.json` | movement category; json regenerated (also gained the three `presto*` entries it was missing) |
| `pathing/movement/CalculationContext.java` | `legatoSmooth`, `legatoSearch` flags (both need `legato`) — planner never reads `Baritone.settings()` |
| `pathing/legato/LegatoFeasibility.java` | swept-box evaluator, verdicts FEASIBLE / INFEASIBLE / UNSUPPORTED / UNKNOWN + reason + failing cell |
| `pathing/legato/LegatoFailures.java` | failed-line and failed-cell memory, 60 s TTL, 256 entries |
| `pathing/legato/LegatoStats.java` | counters, `LegatoStats.line()` |
| `pathing/legato/LegatoSmoother.java` | string-pulling over flat runs, returns a `Plan` (points + kept move per step) |
| `pathing/movement/movements/MovementStraight.java` | the any-angle movement (cost = evaluator; valid cells = swept corridor; pure-pursuit fallback, turns before it walks) |
| `pathing/calc/Path.java` | builds straights for Theta* edges; `smooth()` in `postProcess` before `sanityCheck` |
| `pathing/calc/AStarPathFinder.java`, `PathNode.java` | Theta* parent substitution on flat walking moves only (`legato` flag on the node) |
| `pathing/RouteReuse.java` | Legato mode bits folded into the reuse stamp |
| `pathing/kinematic/KinematicController.java` | straights are drivable; wander capped to half the evaluator margin on a stretch with a straight |
| `pathing/path/PathExecutor.java` | M01 / M02 / M03 faults on a straight record the failure and forget the reused route |
| `pathing/legato/LegatoCourse.java`, `LegatoLiveTest.java`, `harness/TestHarness.java` | live A/B (`-Dostinato.legatotest=true`) |

### Evaluator (M1)

Flat only (`y0 != y1` → UNSUPPORTED), span ≤ 12, player half-width 0.3 + margin 0.2, strict slab overlap (a box
edge touching a wall is clearance). Per swept cell: real chunk data (`bsi.isKnown`, not a SeedPlan guess) else
UNKNOWN; world border; failed-cell memory; feet + head empty collision (lava / `avoidWalkingInto` → INFEASIBLE,
fluid → UNSUPPORTED, door / gate / climbable / powder snow / scaffolding → UNSUPPORTED); floor a full plain cube
with friction 0.6, speed 1, jump 1 (else UNSUPPORTED: ice, slime, soul sand, honey, slab, stairs, carpet; magma
without `allowWalkOnMagmaBlocks` → INFEASIBLE). Cost = length × sprint (or walk) per-block, the same units as
Traverse / Diagonal. Collision tops memoised per block state (`TopMemo`).

### Smoother (M2)

Eligible moves: Traverse / Diagonal / Line / Straight, flat, finite cost, nothing in the way to mine and solid
ground at the end (no bridge to place) — checked read-only so the executor's block caches are untouched. From each
position the farthest position of the same flat run (≤ 256 moves) is tried first; a line is cut into ≤ 12-block
pieces through the cells nearest the true line, each piece evaluated. Accepted only if pieces + turns cost at
least 0.3 ticks less than the planned moves + turns. Turn model: ≤ 90° turns on the run, 0.25 tick per 32° look
step; > 90° stops and turns, 2 + ceil((deg − 45) / 32) ticks. Prefix sums make each candidate O(1) before any
world read; at most 4000 piece checks per path, after which the rest is kept as planned.

### Search (M4)

When `legatoSearch`: for a flat walking relaxation (Traverse / Diagonal / Line, same y) the grandparent is tried as
parent if the straight from it is ≥ 0.05 cheaper and passes the evaluator (span ≤ 12). A* fallback is implicit: the
normal relax is unchanged when the line fails, so unsupported ground (ice) yields exactly the A* path (tested).

### Headless results (Measured, `LegatoSearchTest`, `LegatoCourseTest`)

* Evaluator: 0 bytes allocated over 20 000 checks (was 1.1 MB before the collision-top memo).
* Bench route (4,6) → (190,51), straight 191.4: grid A* length 196.6 / 142 positions; Theta* 196.5 / 138 (834 line
  checks); smoothing 191.5 / 17 positions, 16 piece checks, 0.28 ms.
* Why Theta* alone gains little: the GoalBlock heuristic is inadmissible against knight moves, so A* is near-greedy
  and returns a run of collinear knight moves then one bend; grandparent substitution only cuts locally.
  String-pulling over the whole run reaches the straight line.
* Live course legs, planned length (A grid / B smoothed / C Theta* / D both), straight in brackets:
  open 81.5 / 79.5 / 81.4 / 79.5 (79.4); wall 84.1 / 82.4 / 83.1 / 82.4 (78.0); pillars 81.7 / 81.0 / 81.3 / 81.0
  (79.4); corridor 81.5 / 80.0 / 81.2 / 80.0 (79.4).
* **Geometric headroom is 0.8–2.5 %.** A ≥ 5 % travel-time gain cannot come from shorter routes alone on these
  legs; it has to come from execution (fewer driver handovers — `MovementLine` is not kinematic-drivable, a
  straight is — and fewer turns). The live A/B decides.

## M6 — live A/B (Measured)

Harness: `-Dostinato.testHarness=true -Dostinato.legatotest=true [-Dostinato.legatotest.reps=5] [-Dostinato.legatotest.exit=true]`,
seed 71006, fresh survival world, course `LegatoCourse` at y 200, Presto off, kinematicTravel on, route reuse and
failure memory cleared every lap, Latin-square mode order. Rows in `fabric/run/legatotest-result.txt`.

### Run 1 (2026-10-31, `build/legato-live-1.log`) — partly invalid

* Laps 52–79 are **invalid**: a creeper killed the player on the platform at lap 52 (wall leg, mode A); every later
  lap timed out with the player dead (travelled 0.00), in all four modes. The harness now sets Peaceful, detects
  death (result DIED, respawn) and rebuilds the course after any lap that did not reach the goal.
* Laps 0–51 (reps 0–2 complete, rep 3 open leg): 52 of 52 reached, 0 position packets, 0 rewinds. Ticks were
  near-deterministic per leg and mode (most repeats identical). Medians:

| Leg | A | B | C | D | B vs A | D vs A |
|---|---|---|---|---|---|---|
| open (n=4) | 298 | 284 | 291 | 284 | −4.7 % | −4.7 % |
| wall (n=3) | 302 | 294 | 297 | 294 | −2.6 % | −2.6 % |
| pillars (n=3) | 294 | 290 | 291 | 290 | −1.4 % | −1.4 % |
| corridor (n=3) | 293 | 286 | 291 | 286 | −2.4 % | −2.4 % |

* Travelled distance fell about as much as planned length (open 81.5 → 79.3 B / 78.8 D).
* Baseline laps hand over to the Baritone fallback driver for knight runs (lap 0: 119 of 303 ticks BARITONE; lap 7
  wall: 206 of 302). Smoothed laps were driven KINEMATIC end to end (lap 1: 283 of 285). The extra time saved over
  the length saved comes from that.
* C (search only) is a small gain (−0.3 … −1.7 %) for 3–4× the line checks; D ≈ B.
* Totals: 52 552 evaluations, 104 ms evaluator time, 57 ms smoothing time across the run; 32 straight faults
  recorded (all in the invalid tail).

### Run 2 (2026-10-31, `build/legato-live-2.log`) — clean, the result of record

Peaceful, death detection on; 5 repetitions × 4 legs × 4 modes = 80 laps. **80 / 80 reached**, 0 DIED / FELL /
TIMEOUT, 0 position packets, 0 rewinds, 0 straight faults, 320 straights driven to their end.

| Leg | A | B | C | D | fallback ticks / lap A → B |
|---|---|---|---|---|---|
| open | 292 | 284 (−2.7 %) | 291 (−0.3 %) | 284 (−2.7 %) | 119 → 0 |
| wall | 302 | 294 (−2.6 %) | 297 (−1.7 %) | 295 (−2.3 %) | 206 → 0 |
| pillars | 294 | 290 (−1.4 %) | 291 (−1.0 %) | 290 (−1.4 %) | 110 → 8 |
| corridor | 293 | 286 (−2.4 %) | 291 (−0.7 %) | 286 (−2.4 %) | 120 → 0 |

Median ticks, n = 5 each; spread within a cell ≤ 1 tick except one A open outlier (310). Paired median ratio vs the
same leg and repetition in A, all legs: **B 0.976, C 0.990, D 0.976** (n = 20 each). Travelled distance tracks the
planned length (open 80.8 → 78.8). Evaluator 85.6 ms and smoothing 45.8 ms over the whole run (35 785 checks).

Run 1's open-leg A median (298) was lifted by a cold first lap; run 2 supersedes it.

### Verdict

* **Target not met**: ≥ 5 % lower median travel time on open ground; measured 2.7 %. Legato stays **off by default**
  (`legato = false`, unchanged).
* **Why it cannot be met on this course (Measured + arithmetic)**: sprint ground speed is ≈ 0.281 blocks/tick, so
  the 79.4-block open leg cannot take fewer than ≈ 283 ticks plus the start; B/D take 284, A 292. The whole gap
  to physics is 2.8 %. Grid A* with knight moves is already ≤ 2.7 % longer than straight (headless, above), and the
  rest of the baseline's loss is the Baritone-fallback handover on knight runs.
* Legato is safe as shipped: no fault, correction or rewind in 80 laps; every shortcut re-verified per tick.
* Search (C) is not worth its cost: −1.0 % for 3–4× the line checks of B, and D ≈ B. Keep `legatoSearch`
  experimental.

## Limitations

* Flat only: a line never spans a y change, so stairs/slabs/step-ups are never shortcut (UNSUPPORTED by design).
* Floors must be plain full cubes with default friction; ice, soul sand, honey, slime, carpet, slabs are
  UNSUPPORTED (route kept as planned).
* No turn cost in A* / Theta*; the turn model lives only in the smoother.
* Theta* gains little because the GoalBlock heuristic makes the search near-greedy; string-pulling after the search
  does the work.
* Live course is one seed, one arena, open-air flat; not yet measured on natural terrain or in tunnels.

## Recommendations (not done — outside Legato's scope)

* Most of what Legato wins on these legs is execution, not geometry: `MovementLine` (knight move) is not
  `KinematicController.drivable()`, so baseline knight runs are walked by the Baritone fallback (110–206 ticks per
  lap). Making `MovementLine` drivable would likely give the baseline most of B's gain without Legato; it needs
  its own A/B.

## Decisions

* Smoothing at `Path.postProcess` (calc thread), not in the executor.
* No turn cost in A*/Theta* (keeps the existing cost model); turn penalties live only in the smoother.
* Flat-only v1: never touches Ascend/Descend/Parkour/Pillar/Place/Mine/Swim/Climb/door movements.
* Grace Note preempts Legato automatically (Legato holds no input); Scherzo ownership inherited via PathExecutor.
* Stale-plan rule satisfied by per-tick cost re-evaluation of each straight (path identity + segment + world +
  terrain); a failed straight is recorded and the same pair is refused for ~60 s.

## Step-up stall

Not investigated further: Legato v1 never shortcuts across a y change (evaluator returns UNSUPPORTED for
`y0 != y1`, and the smoother ends a run at any height change), and the live course is flat, so the stall cannot
interact with Legato. Left for its own task.

## Tests (Measured)

Full suite: **214 suites / 1 354 tests / 0 failures / 15 skipped** (baseline 209 / 1 309 / 0 / 15; +5 suites,
+45 tests: `LegatoFeasibilityTest` 22, `LegatoSmootherTest` 13, `LegatoSearchTest` 7, `LegatoCourseTest` 2,
`LegatoReuseTest` 1). No existing test changed.

## Next action

Review the uncommitted diff (nothing committed). If wanted: A/B `MovementLine` as kinematic-drivable with Legato
off (see Recommendations), then rerun the Legato A/B against that stronger baseline and on natural terrain.
