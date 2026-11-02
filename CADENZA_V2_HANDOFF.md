# Cadenza v2 — Turn-Aware Positioning & Task Batching: Handoff

Status: **complete, uncommitted, for review.** All stages ran. Every pre-registered family ran once, with no
re-runs, after the TWO_POSE gate was passed (section 8a). Every new setting is off by default. Evidence labels: **Verified** (checked in code or
by test), **Measured** (live run, numbers given), **Reported** (from earlier handoffs, not re-checked), **Unknown**,
**Contradicted**.

## 1. Branch, HEAD, worktree

- Worktree `Ostinato-ppe`, branch `feat/predictive-physics`, HEAD `1aed5cc2` (Merge PR #142, maze-tunnels).
- Nothing committed, pushed, stashed, reset or cleaned. All v2 changes are uncommitted, alongside a large set of
  earlier uncommitted work (Presto, Legato, PPE, Maestro, Cadenza v1, combat, grace note…). That work is untouched
  except where v2 edits the same files (listed below). A pre-v2 snapshot of every file v2 touches is at
  `%TEMP%/claude/.../scratchpad/pre-v2/pre-v2.tar`, so a v2-only diff can be made.

## 2. Files changed by v2

| File | Change |
|---|---|
| `src/api/.../pathing/goals/TerminalCostGoal.java` | `terminalCost(x,y,z, fromX,fromY,fromZ)` default method (arrival-aware), defaults to the old one |
| `src/main/.../pathing/calc/AStarPathFinder.java`, `MaestroPathFinder.java` | the terminal charge passes the previous path node (one line each) |
| `src/main/.../pathing/cadenza/Cadenza.java` | `TurnModel`; `Candidate.yaw`, `pitchTicks`, `base()`; value equality on `Target`/`Candidate`/`Result` |
| `src/main/.../pathing/cadenza/CadenzaGoal.java` | `Options` (turn model, batch, ordering), heading-aware and batch terminal costs, v2 equality |
| `src/main/.../pathing/cadenza/CadenzaSession.java` | options overload of `goal`, `liveOptions`, `turnCap`; `elapsed()`/`budget()` (diagnostics) |
| `src/main/.../pathing/cadenza/CadenzaGoal.java` (diag) | `cellCount()` (diagnostics) |
| `src/main/.../process/BuilderProcess.java` | placement instrumentation (`PlacePhase`), `JankyGoalComposite` forwards the arrival-aware cost, v2 options, least-turn placement choice |
| `src/main/.../process/MineProcess.java` | v2 options, least-turn ore choice (with a reach prefilter); **TWO_POSE fix**: an ore is started only if the look holds on it under the aim noise (`steady`); diagnostics `cadenzaNote`, `cadenzaGaveUp`, `cadenzaTrace()` |
| `src/main/.../pathing/cadenza/Cadenza.java` (fix) | **TWO_POSE fix**: `AIM_TOLERANCE_DEG` (2°) and `steady`; a MINE cell must see the target with that margin |
| `src/api/.../Settings.java`, `gui/model/SettingCategorizer.java`, `setting-descriptions.json` | `cadenzaTurnAware`, `cadenzaBatch` (both off) |
| `src/main/.../pathing/cadenza/CadenzaLiveTest.java` | Builder phase columns, `arms=v2` (A/B/C/D), 10 opt-in scenarios, start yaw, mid-lap invalidation, per-cycle trace (`-Dostinato.cadenzatest.trace=true`) |
| `src/test/.../pathing/cadenza/CadenzaV2Test.java` | new, 21 tests |
| `build/cadenza-v2-ab.py` | analysis (pre-registered rules, below) |

## 3. Diagnosed Builder bottleneck (Stage 0–1)

**Measured** (`build/v2-diag1.log`, PLACE_ROW, reps 3; A stock, B v1, C v1 + Legato):

| | A stock | B/C v1 |
|---|---|---|
| lap ticks | 50 / 59 / 94 | 55 / 64 / 68, 57 / 65 / 67 |
| first click (tick) | 25 | 25 |
| yaw turned while aligning | 14–22° | 52–128° |
| yaw error at each arrival | ~12–17° | ~30–45° |
| arrivals (starts of an align) | 3–7 | up to 10 |
| total yaw turned in the lap | 35–42° | 119–435° |

- Navigation ticks are about the same (A 35–37, B 31–32), and the first placement lands on the same tick. Pitch held
  while walking is 5–12°, so the pitch error is small. **Measured.**
- Cause: v1 ends at the cell that is cheapest for the single nearest target, and it ignores yaw. That leaves
  off-axis poses like (10.4, 9.0) or (10.5, 12.2), against stock's (11.8–12.3, 9.6). From there each block needs a
  large turn, and the bot re-approaches per block. v1's handoff guessed "pitch but not yaw"; the per-arrival yaw
  errors above confirm it. **Measured.**
- Builder places opportunistically. `searchForPlacables` takes the first placeable hit in scan order, wherever it
  is, so the clicked target can be behind the bot. **Verified** (code).
- PLACE_HIGH is the same in all arms (64 ticks). Cadenza mostly reports NO_CANDIDATE, and 12 ticks are blocked
  (unsafe to cancel or airborne). **Measured.**

## 4. Turn-cost model (Stage 2)

**Verified** (code: `LookBehavior.calculateMouseMove`, `HumanInputCaps`):

- The aim processor moves yaw and pitch **in the same tick**, each capped at `ostinato.aim.deg` (32°). The human
  gate then caps them at `maxYawPerTick` (120°) / `maxPitchPerTick` (90°) when `humanInputCaps` is on.
- The processes click only once `isLookingAt` holds or the look is really close.
- So `turnTicks = ceil(max(|Δyaw|, |Δpitch|) / cap)` with `cap = min(aim.deg, maxYawPerTick)`. The two axes do
  not add.

Arrival look (`Cadenza.TurnModel.fromArrival`):

- Yaw: the direction of the path's last horizontal step, from A*'s previous node.
- Pitch: the walk's held pitch of 8°, from the measured 5–12°.
- Heading uncertainty: the real look is 12–17° off the step direction (Measured, stock). The charge is therefore
  the **mean over the step yaw and ±15°**.
- Unknown heading (empty path, or a vertical last step): a conservative **90°** of yaw is charged.
- Live player yaw is deliberately not fed into the goal, to avoid a replan storm.

Terminal cost per cell, with turn-aware on:

`base + turn(arrival → aim)`, minimised over the targets the cell reaches.

- `base` is v1's terminal (risk, robustness, far reach, setup) **without** v1's heading-blind pitch term. The turn
  model replaces that term, so nothing is double-counted.
- With turn-aware off (the default), v1 is unchanged. `OFF` delegates to the old 3-argument cost (Verified by
  test).

## 5. Candidate selection

- **Goal side.** A* charges the terminal cost once, on a goal cell's first pop, then re-queues the cell. That
  charge now knows the arrival node, so the search ends where `travel + turn + base` is lowest. Equal targets are
  ordered by turn; ties keep v1's deterministic order.
- **Executor side** (under `cadenzaTurnAware` or `cadenzaBatch`, and only with `cadenzaBuilder` for Builder):
  - Builder's `searchForPlacables` collects every placeable hit in its box and returns the one needing the least
    turn (`max(|Δyaw|, |Δpitch|)` from the current look). Stock takes the first in scan order.
  - Mine's `cadenzaMine` keeps the current target if it is still reachable. Otherwise it picks the reachable ore
    with the least turn. Ores out of reach + 1 skip the ray casts.
  - Nothing else in the executors changed: same look, same click, same confirmation.
- **Stability.** Goals are rebuilt every tick, and FORCE_REVALIDATE drops a path whose goal is not `equals`. v2
  equality therefore compares the terminal map, the options and the result set by value. Order is ignored except
  for a STRICT batch, because the miner re-sorts targets by distance from the moving player. Verified by test.

## 6. Batch algorithm (Stages 5–6)

Contract: the existing consumers already pass a bounded target list, so no new batch interface was added.

- Mine passes the known ores, at most `cadenzaMaxTargets` = 8.
- Builder passes `placeNow`, which is already dependency-filtered: no target has another target below it.
  `possibleToPlace` needs existing support.

Ordering policies (`CadenzaGoal.Ordering`):

- **ANY**, used by both consumers. Each target is revalidated live by the executor.
- **STRICT**, the caller's order: a cell must reach target 0, and only the unbroken prefix counts as done there.
  It is implemented and tested, but no consumer uses it yet.

Per cell, with `C` = the targets it reaches (for STRICT, the prefix):

- `fixed = Σ base(C) + sweep(C) + |U| · R + walk(cell → nearest box of U)`
  - `U` is the set of reachable targets left undone from this cell.
  - `R = 8` ticks per reposition. This is an **estimate, not yet measured**.
  - The walk uses the stock `GoalBlock.calculate` to the box of that target's candidate cells, which is a lower
    bound.
- `sweep(C)`: sort C by aim yaw, cut the circle at the widest gap (the short arc), and add `max(1, turnTicks)`
  between neighbours.
- `terminal = fixed + min(turn(arrival → either end of the sweep))`. STRICT has one end.

Bounds:

- Targets ≤ 8.
- Candidates per target ≤ 11 × 10 × 11 (v1's box).
- Per-cell work O(k log k), once per goal build. The goal is memoised by `CadenzaCache`.
- Per search node, O(1) for the batch cost and O(k) for turn-only.

**Not optimistic.** The goal only positions. Targets leave the list only when the world shows them done (mine: the
ore is gone, `confirm`; build: the block is placed), exactly as in v1.

## 7. Tests (Stage 4/8)

- `CadenzaV2Test`: 21 tests, all passing. Verified.
  - Turn model: concurrency, cap, wrap, heading, unknown heading.
  - Aim yaw on real candidates.
  - v1 parity when off.
  - Arrival-aware charge, and the cheapest target per heading.
  - A* ending where the approach faces the aim.
  - Batch whole-job pricing against v1's choice, short-arc sweep, STRICT order and prefix.
  - Real ore vein batch.
  - Equality: order, options, aims, strict order.
  - Session options passthrough.
- `CadenzaTest`: 29, unchanged, all passing.
- Full suite (after the TWO_POSE fix): 1424 tests, 0 failures, 16 skipped (pre-existing). Verified.
- Since the summary above: `realPlacementRowSearchIsOptimalUnderTheTurnModel` (real candidates through the real A*:
  v2's end is no worse than v1's under v2's own objective, and is deterministic),
  `occludedAndInvalidTargetsStayOutOfV2Goals`, `aWorldChangeChangesTheGoal`, `batchShrinksAsTargetsAreConfirmed`,
  `batchWithoutSharedCellsIsSequential`, and the two TWO_POSE tests (section 8a).

## 8. Pre-registration (written before any v2 live data)

Course: `CadenzaLiveTest`, `-Dostinato.cadenzatest.arms=v2`. Peaceful, death and fall detected, the same fresh seed
(71006), the same inventory, start (2.5, 200, 10.5).

| Arm | Settings |
|---|---|
| A | stock |
| B | `cadenza` + `cadenzaBuilder` (v1) |
| C | B + `cadenzaTurnAware` |
| D | C + `cadenzaBatch` |

- In every arm, Presto, Legato search, Legato, Maestro and kinematicLine are off, as in v1's A/B.
- Arm order is a Latin square: `(k + rep + scenario) mod 4`.
- 5 reps per scenario per arm, one run per family. A family is re-run only for a harness defect; any re-run is
  recorded here.

Families:

| Family | Scenarios |
|---|---|
| placement | PLACE_ROW, PLACE_ROW_BACK (start facing away), PLACE_HIGH, PLACE_BELOW, PLACE_SPREAD, PLACE_TRENCH |
| mine-single | EXPOSED, EXPOSED_BACK, WALL, ABOVE, BELOW, OCCLUDED, HAZARD |
| mine-batch | CLUSTER, TWO_POSE, MULTI, VISIBILITY, INVALIDATE (an ore turned to stone at lap tick 25) |
| mixed | MIXED_MINE, PLACE_MIXED |

A reps=1 smoke run of the new scenarios checks the harness only. Its laps are not evidence and are reported apart.

Metric: lap ticks to DONE, paired by rep. Every lap is reported, including failures and timeouts, plus a censored
ratio where a timeout counts its 1200 ticks. Per scenario: n, the median paired ratio, a bootstrap 95% CI, and wins
out of n. Secondary metrics: completions, yaw turned, path calculations, poseUnusable, Cadenza ms, arrivals, align
turn.

Rule, for arm X against base Y over a family (the same rule as v1):

- **BETTER**: completions(X) ≥ completions(Y), and the median ratio is ≤ 0.9 on at least half the scenarios.
- **WORSE**: fewer completions, or the median ratio is ≥ 1.1 on at least half the scenarios.
- **NEUTRAL**: anything else.

Decisions:

- **Turn-aware Builder** (C on placement and mixed placement):
  - A: C BETTER than A, with completions ≥ A.
  - B: the regression is removed, meaning C is not WORSE than A, PLACE_ROW C/A ≤ 1.05, and C/B on PLACE_ROW ≤ 0.95.
    C is not BETTER than A.
  - C: neither of the above, and completions are not lower.
  - D: fewer completions than A or B.
- **Mining batching** (D on mine-batch and MIXED_MINE):
  - A: D BETTER than C and not WORSE than A, with completions ≥ both.
  - B: D/C ≤ 0.9 on some scenario(s), but not BETTER over the family.
  - C: no scenario ≤ 0.9.
  - D: fewer completions than C or A.
  - Also reported: turn-aware alone on mining (C vs B), with the same rule.
- **Placement batching** (D vs C on placement): classified as for mining batching.
- None of this changes a default. Every capability stays off unless it is classed A and someone decides
  otherwise.

## 8a. TWO_POSE correctness gate (a v1 defect, fixed before the A/B)

The reps=1 smoke run (`build/v2-smoke.log`, not evidence) had TWO_POSE time out in **all three Cadenza arms**
(B, C, D), while stock finished in 171 ticks. Because B is plain v1, the batching code could not be the cause, but
that was not assumed. The A/B was held until Cadenza passes TWO_POSE reliably.

**Instrumentation.** `-Dostinato.cadenzatest.trace=true` writes a `CADENZATEST trace` line whenever any of these
change, and every path event, each with the lap tick, feet and yaw/pitch:

- the controlling process and command (navigation ownership);
- the goal (value hash, cells, whether the feet are in a candidate or in the goal);
- the path's destination and whether its goal still equals the current one (the invalidation the control manager
  checks);
- whether a calculation is in progress, and the trigger (CALC_STARTED, NEXT_SEGMENT…, CANCELED, AT_GOAL);
- the targets left;
- the actual crosshair block, and whether the click is held;
- the miner's selection, with counts of skipped ores (gone, blacklisted, out of range, no ray, thin);
- the interaction in progress with its give-up timer (`t elapsed/budget`), the blacklist size, and the give-ups.

**Smallest repro.** TWO_POSE, reps=1, all four arms (`build/v2-twopose1.log`).

| Arm | Result | Ticks | Calcs | Give-ups |
|---|---|---|---|---|
| A stock | DONE | 222 | 3 | 0 |
| B v1 | DONE | 309 | 8 | 1 |
| C turn-aware | DONE | 272 | 19 | 1 |
| D batch | TIMEOUT | 1201 | 36 | 1 |

**Root cause (Verified from the trace and the code):** the aim margin. Cadenza stands, or mines on the way, where
the ore's face is seen at a grazing angle. The crosshair cannot stay on it under the live aim noise.

- **The pose.** For example, from (12,200,12) to ore (13,201,16): eye (12.49, 201.62, 12.97), a 3.7-block reach,
  so it is within reach and a ray hits. But the west face, at x=13, spans only about ±1.2° of yaw around the aim
  point.
- **The noise.**
  - The aim processor adds `randomLooking113` (2° by default): a per-tick yaw offset of up to ±1°.
  - `RotationUtils.reachable` tests the *settled* rotation, which includes that offset. On some ticks the centre
    ray misses, and reachable falls back to a face point, or to nothing (`sel none`).
  - On a `sel none` tick the miner hands control back to pathing for a tick, and the path executor re-takes the
    look (pitch 15→6).
- **The effect.**
  - The trace shows the crosshair alternating between the ore and its wall neighbours (13,201,15) and (13,201,17)
    every 1–5 ticks, with yaw between −6° and −10°.
  - Vanilla restarts the break whenever the targeted block changes, so the ore was never broken within its
    73-tick budget (60 + break ticks).
  - It was given up and blacklisted, both from the planned candidate cell (12,200,12) and from en-route cells the
    miner had grabbed opportunistically: (12,200,11), and (12,200,8) toward (13,201,4).
  - A blacklisted target is never retried (by design). In D, and in the smoke run's B/C/D, an ore stayed unbroken,
    and the bot then went exploring for other ores: 89 cancels at the end, stray digging, then TIMEOUT.
- **Why stock is immune.** It walks into the ore's neighbour cell, where the face spans tens of degrees.

**Ruled out, from the same trace:**

- Goal equality: the path goal stayed `=` across ticks, and there were no cancels before the give-up.
- Target switching: `cur` held one target until it broke or failed.
- Unreachable execution poses: `reach true` at every give-up.
- Batching: B fails the same way.

The give-up timer did what it is meant to do. It caught a pose that cannot work; it did not cause the failure.

**Fix (narrow; v1 and v2 alike; stock untouched):** one test for "the look holds on the target", used in both
places that choose where to mine from.

1. **Planner.** In `Cadenza.candidates`, a MINE cell counts only if the aim ray, turned `AIM_TOLERANCE_DEG` = 2°
   either way in yaw and in pitch, still hits the target (`Cadenza.steady`: 4 extra rays per seeing cell). The 2°
   is `randomLooking113`'s ±1° plus the mouse step and fallback jitter seen live. Grazing cells along a wall drop
   out; cells beside the ore or facing it at range stay.
2. **Executor.** `MineProcess.cadenzaMine` starts a *new* ore only if the same four turned looks, ray-traced live
   from the real eye, all hit it (`steady`). The ore in progress is kept as before, so there is no new oscillation.
   A thin ore is skipped (`thin` in the trace), and the bot walks on to its planned cell.

No timeout, retry or recovery path was added or changed.

**Tests.** Verified:

- `grazingCellsAlongAWallAreNotCandidates`: TWO_POSE geometry. The cell beside the ore and the head-on cell at
  range are candidates; cells one off the face and 3+ along it are not.
- `steadyNeedsTheMarginBothWays`.
- Cadenza: 29 + 21 pass. Full suite: 1424 tests, 0 failures, 16 skipped (pre-existing).

**After the fix (Measured).** TWO_POSE, reps=4, Latin square, all arms (`build/v2-twopose-fix.log`):

| Arm | Done | Ticks (per rep) | Median | Calcs | Cancels | Give-ups |
|---|---|---|---|---|---|---|
| A stock | 4/4 | 201, 173, 170, 171 | 172 | 2–3 | 0 | 0 |
| B v1 + fix | 4/4 | 153, 165, 164, 145 | 158.5 | 3 | 0 | 0 |
| C turn-aware + fix | 4/4 | 140, 161, 154, 164 | 157.5 | 3 | 0 | 0 |
| D batch + fix | 4/4 | 155, 157, 151, 163 | 156 | 3 | 0 | 0 |

Before the fix (smoke + repro, reps=1 each): Cadenza 3/6 laps done, 4 timeouts, 10 give-ups; stock 2/2.

After: 12/12 Cadenza laps done, 0 give-ups, and as few path calculations as stock (3 vs 2–3). The executor's
thin-ore skip fired on 37 trace lines, and mining was done from (12,200,14) and (9,200,6), which are oblique, not
grazing. **Gate passed.**

**Consequence for the A/B.** Arm B is now "v1 + the aim-margin fix", not the v1 of v1's own A/B. Every Cadenza arm
carries the fix, so the B/C/D comparisons are like-for-like. v1's earlier mining numbers are not comparable to
this B. The fix went in before any pre-registered family was run; no rule changed.

## 9. Live results

All Measured. Seed 71006, Peaceful, `arms=v2`, Latin square, 5 reps per scenario per arm, one run per family, no
re-runs. There were no deaths, falls or timeouts: **400 of 400 laps DONE** (100 per arm).

- Logs: `build/v2-ab-{placement,minesingle,minebatch,mixed}.log`.
- Analyses: the matching `.txt` files, and `build/v2-ab-all-verdicts.txt`, from
  `python build/cadenza-v2-ab.py <logs>`.
- The script now also prints the verdicts over the two pre-registered decision scopes (placement + PLACE_MIXED, and
  mine-batch + MIXED_MINE). It uses the same rule; only the grouping is new.

The tables give median lap ticks. Each ratio is the median paired ratio, with a bootstrap 95% CI in brackets where
it is not a point. n = 5 pairs everywhere, and every pair finished, so the censored and uncensored ratios are
equal.

**Placement** (30 laps per arm):

| Scenario | A | B | C | D | B/A | C/A | C/B | D/C |
|---|---|---|---|---|---|---|---|---|
| PLACE_ROW | 47 | 58 | 54 | 55 | 1.26 [0.75,1.45] | 1.13 [0.70,1.22] | 0.93 [0.81,1.00] | 1.02 [0.76,1.11] |
| PLACE_ROW_BACK | 51 | 58 | 57 | 56 | 1.13 [1.06,1.20] | 1.12 [1.04,1.16] | 0.98 [0.91,1.02] | 0.95 [0.82,1.15] |
| PLACE_HIGH | 64 | 64 | 64 | 64 | 1.00 | 1.00 | 1.00 | 1.00 |
| PLACE_BELOW | 39 | 39 | 39 | 39 | 1.00 | 1.00 | 1.00 | 1.00 |
| PLACE_SPREAD | 51 | 50 | 50 | 43 | 0.98 | 0.98 | 1.00 | **0.86**, wins 5/5 |
| PLACE_TRENCH | 60 | 63 | 60 | 61 | 1.05 [1.00,1.21] | 1.05 [0.90,1.15] | 0.95 [0.86,1.05] | 1.02 [0.97,1.09] |

Family verdicts: B, C and D vs A are all NEUTRAL; C vs B is NEUTRAL; D vs C is NEUTRAL.

**Mine-single** (35 laps per arm):

| Scenario | A | B | C | D | B/A | C/B | D/C |
|---|---|---|---|---|---|---|---|
| EXPOSED | 48 | 35 | 35 | 36 | 0.73 [0.47,0.73] | 1.00 | 1.03 [1.00,1.17] |
| EXPOSED_BACK | 51 | 38 | 38 | 38 | 0.75 | 1.00 | 1.00 [1.00,1.03] |
| WALL | 51 | 38 | 38 | 39 | 0.75 | 1.00 | 1.03 [1.00,1.03] |
| ABOVE | 54 | 39 | 39 | 39 | 0.72 [0.72,0.74] | 1.00 | 1.00 [1.00,1.03] |
| BELOW | 54 | 39 | 39 | 40 | 0.72 | 1.00 | 1.03 [1.00,1.03] |
| OCCLUDED | 67 | 55 | 55 | 55 | 0.82 [0.81,0.82] | 1.00 | 1.00 [1.00,1.02] |
| HAZARD | 55 | 35 | 35 | 39 | 0.64 | 1.00 | **1.11** [1.09,1.11] |

Family verdicts: B, C and D vs A are all BETTER, ≤ 0.9 on 7 of 7 scenarios. C vs B is NEUTRAL. D vs C is NEUTRAL
(≥ 1.1 on 1 of 7).

**Mine-batch** (25 laps per arm):

| Scenario | A | B | C | D | B/A | C/B | D/C |
|---|---|---|---|---|---|---|---|
| CLUSTER | 146 | 92 | 92 | 92 | 0.63 [0.56,0.71] | 1.00 [0.89,1.01] | 1.00 [1.00,1.01] |
| TWO_POSE | 169 | 156 | 146 | 157 | 0.93 [0.86,0.99] | 0.94 [0.87,1.11] | 1.08 [0.92,1.18] |
| MULTI | 129 | 101 | 100 | 137 | 0.78 [0.77,0.85] | 1.00 [0.88,1.02] | **1.36** [1.04,1.52] |
| VISIBILITY | 81 | 56 | 56 | 56 | 0.69 | 1.00 | 1.00 |
| INVALIDATE | 125 | 74 | 74 | 74 | 0.59 | 1.00 | 1.00 [1.00,1.01] |

Family verdicts: B vs A is BETTER (4 of 5 scenarios ≤ 0.9), C vs A BETTER (5 of 5), D vs A BETTER (3 of 5). C vs B
and D vs C are NEUTRAL.

TWO_POSE, in the family run: 20 of 20 done, a median of 2–3 path calculations per lap, no give-ups. This is the
gate's result reproduced under pre-registered conditions.

**Mixed** (10 laps per arm):

| Scenario | A | B | C | D | B/A | C/A | C/B | D/C |
|---|---|---|---|---|---|---|---|---|
| MIXED_MINE | 183 | 135 | 135 | 143 | 0.75 [0.61,0.76] | 0.74 [0.60,0.75] | 1.00 [0.96,1.01] | 1.06 [0.90,1.12] |
| PLACE_MIXED | 40 | 42 | 41 | 40 | 1.05 | 1.02 | 0.98, wins 5/5 | 0.98, wins 5/5 |

Decision scopes:

- placement + PLACE_MIXED: C vs A is NEUTRAL (≥ 1.1 on 2 of 7); D vs C is NEUTRAL (≤ 0.9 on 1 of 7).
- mine-batch + MIXED_MINE: D vs C is NEUTRAL (≤ 0.9 on 0 of 6, ≥ 1.1 on 1 of 6); D vs A is BETTER; C vs B is
  NEUTRAL.

**MULTI D/C 1.36: a diagnostic, not evidence.** It came from `build/v2-multi-trace.log` (MULTI, reps 4, traced).

- **Bimodal.** D's laps are bimodal: 103 and 105 ticks in 2 of 5 reps, matching C, and 137–150 ticks in 3 of 5,
  with 21.6–27.2 blocks travelled instead of about 16 and 700–1000° turned. Measured.
- **Not batching.** In the traced run, arm **B (v1, no batching) took the long mode too**: 144 and 127 ticks
  (27.0 and 22.2 blocks) in 2 of 4 reps. Stock's turning also varied, from 214 to 1346°. So the long mode is not a
  batching effect. Measured.
- **The moving goal.** Within one lap the Cadenza goal value keeps changing while the ore targets stay the same:
  - the cell count stays at 107 and one ore is left;
  - the goal hash flips every 1–5 ticks;
  - the miner's `known` list swings between 3 and 6 entries (Measured, from the trace).

  The cause is in the code (Verified). `MineProcess.updateGoal` builds the list with
  `prune(..., droppedItemsScan())`, and `mineScanDroppedItems` is on by default, so the list includes dropped item
  positions. Every non-target location rides along as an `extras` goal at terminal cost 0, ordered by distance from
  the moving player. `CadenzaGoal.equals` compares `extras` in order.
- **The detour.** In the long lap, next-segment and splice plans end at (11,200,15) and (16,200,11). Neither is a
  Cadenza cell, and neither appears in the short lap. The bot then comes back to the planned cell (12,200,7).

  **Inferred, not verified per destination:** the long mode is a detour to pick up items, and whether it happens
  depends on where the drops landed. Vanilla throws drops with random velocity, and that randomness is not tied to
  the seed.

There were no cancels (`REVALIDATE` keeps a path when the goal changes) and 5–7 path calculations, so this is not
the TWO_POSE kind of over-replanning. The pre-registered number stands as measured. The class below does not depend
on it: no D/C in the scope is ≤ 0.9.

## 10. Performance overhead

All Measured, over all 100 laps per arm (all families):

| Arm | nav ticks (median) | click | idle | Cadenza ms per lap (mean / max) | path calcs (total) | cancels (total) |
|---|---|---|---|---|---|---|
| A | 52.5 | 0 | 4.5 | 0 / 0 | 292 | 287 |
| B | 29.5 | 13 | 3.0 | 4.0 / 36 | 237 | 307 |
| C | 30.0 | 13 | 3.0 | 3.4 / 14 | 217 | 280 |
| D | 28.5 | 13 | 4.0 | 3.3 / 14 | 218 | 301 |

- The turn-aware and batch searches cost nothing measurable over v1. Their per-lap Cadenza time is no higher than
  B's. B's 36 ms worst case is one mixed-family lap.
- Per family, the mean is 1.9–8.5 ms per lap, against laps of 2–9 s.
- All cancels are in Builder laps (placement and PLACE_MIXED). The mining families had 0 in every arm. They are
  Builder's normal per-placement goal switching, and the counts are alike across arms, stock included.
- The planner's new margin check (4 rays per seeing MINE cell) is inside the Cadenza ms above.
- The executor's `steady` check (4 ray traces per considered new ore per tick, Cadenza arms only) is not timed
  separately.

## 11. Reliability observations

- **Completions.** 400 of 400 laps DONE: no timeouts, deaths or falls. The 5 placement "stray" counts occur in all
  four arms equally, so they come from the course, not an arm. Measured.
- **Failed interactions.** After the fix, 0 given up or blacklisted in any arm over all families. Before the fix,
  TWO_POSE alone had 10 give-ups in 6 Cadenza laps (section 8a). Measured.
- **The TWO_POSE gate.** Passed twice: in the gate run (16 of 16, reps 4) and in the pre-registered family (20 of
  20). Path calculations are at stock level. Measured.
- **Blocks dug.** Stock digs through ores' neighbours: 50 blocks over all laps. The Cadenza arms dig 5 (B, C) and
  9 (D). Measured.
- **Goal value churn from dropped items.** See MULTI in section 9. It is harmless under `REVALIDATE` (no cancels),
  but under `legitMine` the miner uses `FORCE_REVALIDATE_GOAL_AND_PATH`, which may cancel a partial path on every
  unequal goal. That was not exercised here; Unknown.

## 12. Default-setting recommendations

**Classifications** (the pre-registered rules, unchanged):

- **Turn-aware Builder (C on placement + PLACE_MIXED): class C.**
  - C vs A is NEUTRAL, not BETTER, so it is not A.
  - Not B: C is not WORSE than A, and PLACE_ROW C/B = 0.93 ≤ 0.95, but PLACE_ROW C/A = 1.13 > 1.05.
  - Completions are equal (35 vs 35), so not D. Hence C.
  - Turn-aware cuts v1's PLACE_ROW regression (B/A 1.26 → C/A 1.13) but does not remove it. The C/A CI
    [0.70, 1.22] is wide, so this point estimate is uncertain.
  - PLACE_ROW_BACK (B/A 1.13, C/A 1.12) shows the same regression, nearly untouched by turn-aware.
- **Mining batching (D vs C on mine-batch + MIXED_MINE): class C.**
  - No scenario has D/C ≤ 0.9. The best are CLUSTER, VISIBILITY and INVALIDATE at 1.00.
  - Completions are equal (30 vs 30). D is still BETTER than stock, but only because it carries v1.
- **Placement batching (D vs C on placement): class B,** scenario-specific.
  - PLACE_SPREAD D/C = 0.86 (5 of 5 pairs identical), wins 5 of 5: two targets that share a cell, placed from one
    stop.
  - Over the family it is NEUTRAL (1 of 6 ≤ 0.9; with PLACE_MIXED, 1 of 7). Completions are equal.
- **Turn-aware alone on mining (C vs B): class C.** NEUTRAL in every mining family. Every C/B is 1.00 except
  TWO_POSE at 0.94 [0.87, 1.11].
- Also measured, outside the classes: **v1 + the aim-margin fix (B) is BETTER than stock** on mine-single (7 of
  7), mine-batch (4 of 5) and mixed, and NEUTRAL on placement. This repeats v1's mining result on the fixed v1.

**Defaults.** Nothing changes:

- `cadenzaTurnAware` and `cadenzaBatch` stay **off**. Neither is class A. The only gain (PLACE_SPREAD) is narrow.
- `cadenzaBuilder` stays off; the PLACE_ROW regression is still there.
- `cadenza` and Presto, Maestro, Legato search, Legato smoothing and kinematicLine keep their defaults (Verified: no
  default changed in `Settings.java`).
- The aim-margin fix has no setting. It is on wherever Cadenza mines, because it is a correctness fix (section 8a).

## 13. Known limitations

- `R = 8` reposition ticks is an estimate.
- The batch walk to the remaining targets is a lower bound (box distance), so a cell inside a far target's box pays
  no walk.
- The arrival heading is the last step's direction, give or take 15°. Turn-before-move movements (Presto), or a
  path ending in a fall, can arrive at another look.
- Builder's executor choice is per tick, by least turn. Its scan box (±5, −5..+1) is unchanged.
- The aim margin covers MINE only. PLACE and USE targets get no margin. A placement is a single click, so it does
  not restart, but a grazing PLACE cell could still miss on one tick. Not observed in 140 placement laps.
- `AIM_TOLERANCE_DEG` is a constant (2°). It is not derived from `randomLooking113` or the mouse step, so a user
  who raises `randomLooking113` well above 2 gets a margin that is too small.
- A blacklisted ore is never retried, by design. The give-up and blacklist path is unchanged. After a give-up the
  miner explores elsewhere, which is what made the pre-fix TWO_POSE laps time out.
- Dropped items enter the Cadenza goal as ordered `extras` at terminal cost 0. The goal value changes as items
  move, and that is what drives the bimodal MULTI laps (section 9).
- Placement batching's only win is the shared-cell case (PLACE_SPREAD). The scenarios do not test larger build
  sets.
- n = 5 per scenario. Several CIs are wide (PLACE_ROW, TWO_POSE, MULTI). The classes follow the pre-registered point
  rule, not the CIs.

## 14. Next engineering action

1. **Builder's PLACE_ROW and PLACE_ROW_BACK regression** (v1, B/A 1.13–1.26; turn-aware recovers only part of
   it). It is the one remaining Cadenza loss against stock.
   - Use the Builder phase columns (section 3) on those two scenarios to see whether the extra ticks are in
     reposition, alignment or idle.
   - Only then decide whether `cadenzaBuilder` can ever default on.
2. **Dropped-item goal churn** (section 9).
   - Make `CadenzaGoal.equals` and `hashCode` independent of the order of `extras`. It is narrow, and it matters
     under `legitMine`'s FORCE_REVALIDATE.
   - Then run MULTI with `mineScanDroppedItems=false` as a control, to confirm that the drops cause the bimodality.
     That run is a diagnostic, not a pre-registered family.
3. Leave `cadenzaTurnAware` and `cadenzaBatch` off. Revisit `cadenzaBatch` only for builds with many targets that
   share cells, the one case where it measured better.
