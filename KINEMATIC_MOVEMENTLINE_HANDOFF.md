# Kinematic MovementLine: handoff

Status: implemented behind `kinematicLine` (default **off**), unit-tested, measured live. Decision: **B, partial.**
Nothing is committed. Everything below sits on top of the existing uncommitted Presto / PPE / Legato work.

## Branch and worktree

- Worktree `Ostinato-ppe`, branch `feat/predictive-physics`, HEAD `1aed5cc2`.
- About 82 uncommitted entries: Presto, PPE, Legato and this change. None was reset, stashed or discarded.

## Files changed in this phase

| File | Change |
|---|---|
| `src/api/java/baritone/api/Settings.java` | new `kinematicLine` setting, default `false` |
| `src/main/java/baritone/gui/model/SettingCategorizer.java` | lists `kinematicLine` with the other experimental flags |
| `src/launch/resources/assets/baritone/ostinato/setting-descriptions.json` | regenerated (+1 entry) |
| `src/main/java/baritone/pathing/legato/LegatoFeasibility.java` | `plainCell(...)`: the evaluator's per-cell check, exposed |
| `src/main/java/baritone/pathing/movement/movements/MovementLine.java` | records whether the whole rectangle was plain ground at the last cost check (`plainGround()`, `groundReason()`, `groundChecked()`, `kinematicDecision`) |
| `src/main/java/baritone/pathing/kinematic/KinematicLine.java` (new) | the eligibility decision, plus decision and downgrade counters |
| `src/main/java/baritone/pathing/kinematic/KinematicController.java` | `drivable()` gains a `MovementLine` branch, and is now package-visible for tests; `syncPosition`'s rule moved, unchanged, into static `syncedPosition(...)` |
| `src/main/java/baritone/pathing/legato/LegatoLiveTest.java` | `-Dostinato.legatotest.arms=kline` (the four arms below), plus per-tick and per-lap instrumentation |
| `src/test/java/baritone/pathing/kinematic/KinematicLineTest.java` (new) | 10 deterministic tests |

Scratch analysis scripts live in `build/` and are not part of the source tree: `kline_analyze.py`, `kline_harness_edit.py`.
The raw run log is `build/kline-live-1.log`.

## Audit findings

- **The exclusion was incidental (Verified).**
  - The kinematic controller was ported in `ed9cea55`; `MovementLine` arrived later, in `bed6add2`.
  - `KinematicController.drivable()` was never updated for it, and no comment, test or commit explains the omission.
- **`MovementLine` fits the driver's model (Verified from code).**
  - It is flat by construction: dest `y` equals src `y`.
  - It never breaks or places: `positionsToBreak` is empty and `positionToPlace` is null.
  - Its valid positions are the full 2×1 rectangle, so cutting across the rectangle is never "off path".
  - Its completion rule (feet in the dest cell) is the same rule `syncPosition` already uses.
- **No stale steering plan exists (Verified).** `buildLine` clears and rebuilds the polyline every tick from the live path, from `pathPosition` forward.
- **Turn-first is already handled (Verified).**
  - Past 90° between look and path, `turningAround` declines to Baritone, so `MovementLine.updateState` turns in place.
  - Under that, the existing walk gate and rotation handling apply unchanged.
- **Decision A, a safe narrow implementation, was feasible.** Implemented as A.

## Eligibility: exactly when the kinematic driver may steer a `MovementLine`

`KinematicLine.decide(enabled, line)`:

1. `kinematicLine` is on. Otherwise `OFF`.
2. src `y` equals dest `y`. Otherwise `NOT_FLAT`; this is unreachable by construction and kept as a guard.
3. The line has had at least one cost check. Otherwise `PENDING`: the stretch ends before it, for now.
4. At that last check, the src cell and every rectangle cell (`LineWalk.corridor`) passed `LegatoFeasibility.plainCell`. Otherwise `GROUND`. A cell passes when it is:
   - loaded and inside the world border;
   - free of fluid in the feet and head blocks;
   - free of doors, gates, climbables, powder snow, scaffolding and `avoidWalkingInto` hazards;
   - free of any collision shape in the feet and head blocks (carpet and snow layers are refused);
   - standing on a full normal cube with friction 0.6 and speed and jump factors of 1.0. Ice, soul sand, honey, slime, slabs and magma are refused.

   If the line's own cost is infinite, the reason is `"no line"`.

The check runs inside `MovementLine.calculateCost`, which the executor calls:
- every tick for the current movement;
- once for the next `costVerificationLookahead`−1 movements when each movement starts.

So a world change reaches the decision within one tick for the current line. Everything not accepted keeps Baritone's own `MovementLine.updateState`, exactly as before.

## Safety invariants

| Invariant | How it holds |
|---|---|
| Baseline unchanged when off | `drivable()` returns false for every `MovementLine` when the flag is off, and no other branch changed (test `offLeavesLinesToBaritoneAndNothingElseChanges`). Arm A shows 138.8 BARITONE line ticks per lap. |
| Turn before moving / rotation cap | Untouched; the same `turningAround` gate and look path. Knight bends of 26.6° and 63.4° are steered; reversals are declined (test). |
| No stale plans | The polyline is rebuilt every tick. A replanned path's lines start undecided (`kinematicDecision` −1, `PENDING`) (test). |
| Handoff / no stuck keys | Unchanged handoff code. Live: **0** laps with any input still forced after cancel (checked right after `cancelEverything` and again before the next lap). |
| Completion contract | Unchanged rule, now in `syncedPosition`; tested at knight boundaries (mid-rectangle is not done; the far column in the wrong row is not done; skipping to a later dest; airborne ±1). |
| World invalidation / support loss | Support removed → the line's cost becomes infinite → the executor cancels; the decision downgrades; a restored world re-accepts (test). |
| Grace Note preemption, Presto, Legato defaults | Not touched. Presto, Legato and the Legato search stay default off; `kinematicLine` is default off. |
| No new input writer | None added; the kinematic driver's existing `InputOverrideHandler` writes are reused. |

## Tests

New `KinematicLineTest`, 10 tests, headless, all pass:
- eligible knight moves in five orientations;
- setting off;
- unusual ground: ice, packed ice, soul sand, honey, slime, bottom slab, carpet, water, door;
- a hazard in the rectangle;
- world change → downgrade → recovery;
- unchecked lines and a new plan;
- unloaded terrain;
- knight headings and the turn gate;
- completion at the dest cell;
- `MovementStraight` unaffected.

The 14 requested areas:

| Area | Coverage |
|---|---|
| 1. Eligible line | `plainKnightMoveIsSteered` |
| 2. Ineligible fallback | `unusualGroundFallsBack`, `hazardInTheRectangleFallsBack` |
| 3. Large yaw mismatch | `knightHeadingsAndTheTurnGate` |
| 4. Rotation-limited turning | `knightHeadingsAndTheTurnGate` |
| 5. Handoff | live counters |
| 6. Ownership change | unchanged code path; the live run shows no faults |
| 7. Stale-plan invalidation | `uncheckedLinesAreNotSteered` |
| 8. Target reuse | `uncheckedLinesAreNotSteered` |
| 9. Completion at a waypoint boundary | `completionIsTheDestCell` |
| 10. World invalidation | `worldChangeDowngradesThenRecovers` |
| 11. Collision / support loss | `worldChangeDowngradesThenRecovers` |
| 12. Setting disabled | `offLeavesLinesToBaritoneAndNothingElseChanges` |
| 13. Recovery after rejection | `worldChangeDowngradesThenRecovers` |
| 14. No persistent keys after cancel | live check |

Areas 5, 6 and 14 need a real client and were checked live; nothing headless covers them.

Full suite: **215 suites, 1,364 tests, 0 failures, 0 errors, 15 skipped**. Before: 214 / 1,354 / 0 / 15. No existing test expectation was changed.

## Live benchmark (Measured)

**Setup:**
- The four-leg Legato course (open, wall, pillars, corridor), in a fresh survival world on seed 71006, at Peaceful.
- Presto off and Legato search off in every arm.
- 6 repetitions × 4 legs × 4 arms = **96 laps**, in Latin-square order.
- Route reuse and the line-failure memory were cleared before every lap.
- Run on 2026-10-31, 28 minutes.

**Command:**

```
$env:JAVA_TOOL_OPTIONS='-Dostinato.noMouseGrab=true -Dostinato.windowPos=2600,40 -Dostinato.testHarness=true -Dostinato.legatotest=true -Dostinato.legatotest.exit=true -Dostinato.legatotest.arms=kline -Dostinato.legatotest.reps=6 -Dostinato.seed=71006'
.\gradlew.bat :fabric:runClient -PclientXmx=1536M "-Dorg.gradle.jvmargs=-Xmx1536M" --console=plain *> build\kline-live-1.log
python build/kline_analyze.py build/kline-live-1.log
```

| Arm | kinematicLine | smoothing | Reached | Median ticks | Mean |
|---|---|---|---|---|---|
| A baseline | off | off | 24/24 | 294.0 | 296.0 |
| B MovementLine | on | off | 24/24 | 290.5 | 292.8 |
| C Legato | off | on | 24/24 | 288.0 | 288.5 |
| D combined | on | on | 24/24 | 287.5 | 288.2 |

Per leg: median ticks, path length travelled, and total yaw turned per lap (medians):

| Leg | A | B | C | D |
|---|---|---|---|---|
| open | 291.5 / 80.87 / 156° | 291 / 80.73 / 352° | 284 / 78.79 / 144° | 284 / 78.79 / 139° |
| wall | 302 / 83.49 / 66° | 301 / 82.94 / 466° | 294 / 81.57 / 274° | 294 / 81.57 / 282° |
| pillars | 294 / 80.92 / 245° | 290 / 80.39 / 555° | 290 / 80.15 / 362° | 289 / 80.15 / 408° |
| corridor | 293 / 80.98 / 146° | 290 / 80.43 / 430° | 286 / 79.33 / 272° | 286 / 79.32 / 278° |

Paired with A (same leg and repetition). The interval is a 95% bootstrap interval on the median ratio:

| Comparison | Pairs | Median ratio [95%] | Median Δ ticks | Faster / tie / slower |
|---|---|---|---|---|
| B vs A | 24 | **0.990** [0.988, 0.997] | −3 | 21 / 3 / 0 |
| C vs A | 24 | **0.976** [0.974, 0.976] | −7 | 24 / 0 / 0 |
| D vs A | 24 | **0.976** [0.974, 0.976] | −7 | 24 / 0 / 0 |
| D vs C | 24 | 1.000 [1.000, 1.000] | 0 | — |
| B vs C | 24 | 1.015 [1.014, 1.024] | — | — |

B against A, per leg (median ratio):

| Leg | B vs A |
|---|---|
| open | 0.998 (3 laps tied) |
| wall | 0.997 |
| pillars | 0.986 |
| corridor | 0.990 |

Within a leg and arm the lap times are almost identical (e.g. every B corridor lap took 290 ticks). The intervals are narrow because the course and inputs are deterministic, not because the sample is large. One outlier: A's first lap of the run (open, 314 ticks) includes about 23 ticks of start-up before the first path; it only widens the open-leg intervals.

## Driver utilization (per lap, Measured)

| | A | B | C | D |
|---|---|---|---|---|
| BARITONE (fallback) ticks | 138.8 | 0.6 | 2.3 | 0.2 |
| KINEMATIC ticks | 155.9 | 291.8 | 285.5 | 287.2 |
| MovementLine ticks by driver | BARITONE 138.8, KINEMATIC 1.5 | KINEMATIC 146.5, BARITONE 0.6 | ~2 | ~2 |
| MovementStraight ticks by driver | — | — | KINEMATIC 274.8 | KINEMATIC 274.8 |
| Driver changes | 4.17 | 1.00 | 1.50 | 1.00 |
| `kinematicLine` decisions, run total (accepted / off / ground / pending; downgrades) | 0 / 420 / 0 / 0; 0 | 420 / 0 / 0 / 0; 0 | — | 6 / 0 / 0 / 0; 0 |
| Turn-in-place ticks | 0 | 0 | 0 | 0 |
| Faults (M01–M04), rewinds, server corrections, cancelled plans | 0 | 0 | 0 | 0 |
| Path calculations | 1.00 | 1.00 | 1.00 | 1.00 |
| Kinematic controller time per lap (`kinNs`) | 40.6 ms | 76.9 ms | 70.4 ms | 71.1 ms |

- **The execution path did change.** The ticks were counted per tick from the actual driver, not inferred from eligibility. In B, 99.6% of MovementLine ticks were steered kinematically; in A, 99%+ were Baritone's.
- **Attribution artifact.** The ~1.5 KINEMATIC line ticks in A (and ~2 in C/D) come from the harness sampling the path position after the executor has already advanced onto a line, on the tick the previous stretch finished.
- **D has almost no lines to drive.** Smoothing replaces nearly every knight run with a `MovementStraight` (6 line decisions in 24 laps).

## Failure analysis

There were no failures in any arm:
- 96/96 laps reached the goal;
- no deaths, falls or timeouts, and no course rebuilds;
- 0 movement faults, 0 rewinds, 0 server position corrections, 0 cancelled plans;
- 0 laps with keys left forced down after cancel;
- 0 `kinematicLine` downgrades. The course is plain stone, so the GROUND and downgrade paths were exercised only by the unit tests.

**Observed cost (Measured):** B turns the camera 2–7× more than A (e.g. wall 466° against 66° per lap). The driver now follows the cell-centre polyline of mixed knight and traverse moves, and its heading wobbles at every small bend. It cost no time on this course, but it looks worse, and it is the likeliest source of trouble on narrow ground. The heading wobble is Measured; the narrow-ground risk is Inferred.

## Decision: **B — partial**

The change is safe and real, but it recovers only part of Legato's gain:
- B is faster than A in 21 of 24 pairs and never slower; median −3 ticks (−1.0%).
- Legato smoothing (C) gains −7 ticks (−2.4%).
- So B recovers about 40% of Legato's gain overall (−3 of −7 median ticks).
- By leg: open 0% (291 against 291.5), wall about 12% (−1 of −8), pillars 100% (−4 of −4), corridor about 43% (−3 of −7).

Combining them adds nothing over smoothing alone (D/C = 1.000), because smoothing leaves almost no `MovementLine` to execute.

## Recommended default

Keep `kinematicLine` **off**.
- **Reasons:**
  - the gain is about 1%;
  - it doubles to septuples yaw travel;
  - it has been validated on one flat stone course only.
- **Safety record so far:** clean. No faults, no stuck keys, no regressions.
- **Before enabling by default, it would need:**
  - a heading low-pass (or a look target further along the polyline) to remove the wobble;
  - a live run over mixed terrain: slab and ice edges, narrow bridges, mobs on.

## Limitations

- One course, one seed, flat full-block stone. Every live lap took the ACCEPTED path; GROUND, PENDING and downgrade handling are covered by unit tests only.
- The lap-to-lap variance is near zero, so the confidence intervals describe this deterministic setup, not a population of worlds.
- Lines beyond the executor's cost-check window are `PENDING`, so a stretch ends at most about four moves past the current one. On this course the 1.2-block handback was never reached early: 0 extra driver changes in B. It could matter on very short lines.
- Ownership changes, Grace Note preemption and key release are covered by unchanged code and the live counters, not by headless tests.

## Next step

If knight execution matters again, add the heading low-pass and run the mixed-terrain validation. Otherwise leave this off and spend the effort on Legato's geometry, which is where the time is (below).

## Was Legato's improvement primarily a movement-execution improvement, or does its any-angle geometry provide meaningful independent value?

**Mostly geometry.** The earlier reading (that Legato won mostly by escaping Baritone's knight-move fallback) does not hold up. All three findings below are Measured.

1. **Execution alone is worth little.** Making the kinematic driver execute every knight move moved A's 138.8 fallback ticks per lap to the kinematic driver, but saved only 3 ticks per lap. On open ground it saved nothing: Baritone's `MovementLine.updateState` already sprints at full speed.
2. **The time follows path length.**
   - On every leg, lap time tracks the distance actually travelled at about 0.28 blocks per tick.
   - Against A, smoothing shortens the walked route by 0.8–2.1 blocks per leg (open 80.87 → 78.79), which is the 4–8 tick gain.
   - B shortens it only by the corner cutting a kinematic driver gets for free (0.1–0.6 blocks), which is its 0–4 ticks.
3. **Execution adds nothing on top of the geometry.** D equals C (paired ratio 1.000).

So any-angle geometry provides meaningful independent value: about 60% of Legato's gain overall, and all of it on open ground. Execution accounts for the remaining ~40% overall. Pillars is the one leg where the two are equal: there the smoothed route is barely shorter than the grid route.
