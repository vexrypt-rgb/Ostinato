# MAESTRO + CADENZA v1 — handoff

Evidence labels: **Verified** (checked in code or by a run in this session), **Measured** (a number from a run, with its
source), **Reported** (from earlier notes or memory, not re-checked), **Contradicted** (an earlier claim a run disproved),
**Unknown**.

## Worktree

- Worktree `Ostinato-ppe`, branch `feat/predictive-physics`, HEAD `1aed5cc2`, version 26.3 / Java 25. **Verified**
- Nothing is committed or pushed. **Verified** About 90 entries in `git status` before this work. Many were already
  uncommitted and unrelated; they are untouched. **Verified** Some of the files listed below may also hold those earlier
  edits, so diff them hunk by hunk before committing.
- Experimental defaults are unchanged: `legato` false, `legatoSmoothing` true, `legatoSearch` false, `kinematicLine`
  false, `presto` unchanged. **Verified** New settings are all off by default. **Verified**

## Audit (Stage 0)

- Old goals:
  - Mining used `GoalComposite` of `coalesce` boxes. It walks next to the ore, not to a cell that can see and reach it.
  - Containers used `GoalGetToBlock`/`GoalTwoBlocks`.
  - Builder used `placementGoal`, wrapped in `JankyGoalComposite`.
  - None of these knew whether the block could actually be acted on from the cell. **Verified**
- A* (`AStarPathFinder.calculate0`) stops on the first goal pop, so it had no way to rank goal cells. **Verified**
- `PathingControlManager` FORCE_REVALIDATE soft-cancels when a rebuilt goal is not `equals` to the old one. Any goal
  rebuilt every tick therefore needs value equality. **Verified**
- Builder only places from its `searchForPlacables` box: dx, dz in ±5 and dy (target − feet) in −5..+1. A cell
  outside it stalls the bot. **Verified**

## Cadenza design (Stages 1–3)

- `TerminalCostGoal` (api): a goal can charge ticks for ending in a cell. A* handles the charge like this:
  1. The first pop of a goal cell with a charge is expanded as usual.
  2. The cell goes back into the open set at cost + w·h + charge (`PathNode.terminalPaidAt`/`terminalCharge`).
  3. The path is returned only when that charged copy is popped.
  4. With zero charge the search is unchanged. **Verified** (`searchWithZeroTerminalCostsIsPlain`)
- `Cadenza.candidates(target)` scores cells:
  - The search is bounded and lazy: a reach box around the target, ordered by distance, with a work cap
    (`boundedWork`).
  - Hard constraints come first: a standable cell, no lava or fire, never standing on the target, the forbidden
    set, and a ray from the eye to a face of the target within reach (`CadenzaRay`).
  - Terminal cost = alignment (yaw/pitch turn) + setup (sneak, tool) + risk (deep edge 5, falling block above 6),
    in ticks.
  - Travel cost is left to A*.
  - When no cell qualifies the result is structured (`NO_STANDING`, `NO_SIGHT`, `INVALID`, ...); no position is
    invented. **Verified** (29 tests)
- `CadenzaGoal`:
  - The goal region is the candidates of up to `cadenzaMaxTargets` (8) targets.
  - The heuristic goes to each target's candidate box and never exceeds the stock one.
  - Old goals ride along as extras at zero charge (drops, targets past K, targets with no cell).
  - It has value `equals`/`hashCode`.
- `CadenzaSession`:
  - Caches goals per target state.
  - Tracks completion phases (`CadenzaStats.Phase`): GOAL, NO_CANDIDATE, POSE_UNUSABLE, ATTEMPTED, CONFIRMED,
    FAILED, INVALIDATED. "Path found" and "navigation started" are the pathing events already in place.
  - If the live reach check finds nothing from a cell for 10 ticks, the cell is dropped for 30 s and the bot
    replans (no stall).
- Short horizon (`cadenzaShortHorizon`, off): takes 4 ticks off a cell's cost per extra target it reaches. Not
  validated live yet.
- Integration (only when `cadenza` is on):
  - `MineProcess`:
    - goal from `cadenzaGoal`;
    - `cadenzaMine` breaks any known ore reachable from where the bot stands, then confirms once the block is gone;
    - failed targets go on the blacklist.
  - `GetToBlockProcess` (right-click-on-arrival blocks only):
    - USE targets;
    - a chest with a solid block on top keeps the old goal;
    - the container screen opening confirms;
    - the 20-tick timeout fails.
  - `BuilderProcess`:
    - PLACE targets with the crouching eye, through a `CellFilter` that keeps cells inside Builder's placement box;
    - `JankyGoalComposite` now forwards terminal costs;
    - break goals are not Cadenza'd.
- Movement executor: not changed. **Verified**

## Files

- New:
  - `src/api/java/baritone/api/pathing/goals/TerminalCostGoal.java`
  - `src/main/java/baritone/pathing/cadenza/`: `Cadenza`, `CadenzaRay`, `CadenzaGoal`, `CadenzaSession`,
    `CadenzaCache`, `CadenzaStats`, `CadenzaLiveTest`
  - `src/test/java/baritone/pathing/cadenza/CadenzaTest.java`
- Changed:
  - `PathNode`, `AStarPathFinder` (terminal charge)
  - `Settings` (`cadenza`, `cadenzaMaxTargets`, `cadenzaShortHorizon`, `cadenzaDebug`), `SettingCategorizer`,
    `setting-descriptions.json` (regenerated)
  - `MineProcess`, `GetToBlockProcess`, `BuilderProcess`
  - `TestHarness` (installs the live test)
  - `PathBench` (`plan` helper)

## Tests

- `CadenzaTest`: 29 tests, all passing. **Verified** They cover:
  - exposed, wall, above, below, occluded and hazardous ores, plus several from one cell;
  - placement on the floor and against a wall; the container's open side;
  - reach boundary, lava refusal, edge and falling-block risk;
  - goal equality; A* paying the terminal cost;
  - session lifecycle, unusable cells, the caller's filter;
  - cache and bounded work.
- Full suite after Maestro: 1,402 tests, 1 failure, 16 skipped. **Measured** The failure was
  `SettingCatalogTest`, because the descriptions JSON was stale. After regenerating the JSON, that test plus the
  Cadenza and Maestro tests passed. **Measured**
- Final full suite, with the final code (turn-cap fix, entity check reverted, harness changes): 1,403 tests,
  0 failures, 16 skipped. **Measured** (`build/final-full.log`)

## Live A/B (Stage 4)

- Harness: `-Dostinato.cadenzatest=true`, plus optional `.reps` (default 4), `.only=A,B` and `.exit`.
  - Every lap prints a `CADENZATEST lap ...` row to the client log (stdout). Summaries and paired ratios are
    printed at the end. `build/cadenza-ab.py <log>` gives the compact per-scenario table and the family verdicts.
  - Arms: A cadenza off; B cadenza; C cadenza + legato.
  - 12 scenarios: EXPOSED, WALL, ABOVE, BELOW, MULTI, CLUSTER, OCCLUDED, HAZARD, CHEST, CHEST_HIGH, PLACE_ROW,
    PLACE_HIGH.
  - Laps follow a Latin-square order; the world is Peaceful; DIED/FELL/TIMEOUT are detected.
- Decision rules, fixed before any results:
  - An arm is **better** only if it completes at least as many laps as A **and** its median paired ticks ratio
    against A is ≤ 0.9 on at least half of the scenarios in that family (mining, container, placement).
  - It is **worse** if it completes fewer laps than A, or its median ratio is ≥ 1.1 on half of them.
  - Otherwise it is **neutral**.
- Run 1 (smoke, reps=1): **invalid**. The first teleport landed on air before any arena existed, the player died,
  and lap 0 ran with a dead player. Harness fixed: the first arena is built before the teleport, and laps wait
  for the respawn round trip. **Verified**
- Debug runs (reps 1–2, CHEST/CHEST_HIGH/PLACE_ROW/PLACE_HIGH; `-Dostinato.cadenzatest.debug=true` logs every
  cell where the live reach check fails). They found four defects, all fixed before the main run:
  1. **Harness, placement.** The lap setup selected slot 0 only on the server. The client kept the previous lap's
     block slot and never re-sent it, so the server used the pickaxe and every placement became a client-side ghost.
     This hit every placement lap after the first, in all arms (laps C and A: 200+ click ticks, `held=0`).
     **Verified** Fix: the client selects slot 0 too, plus a full inventory resync.
  2. **Harness, PLACE_HIGH.** The target was at feet + 2, outside Builder's own placement box (dy ≤ +1). Every arm
     timed out, and B/C reported NO_CANDIDATE on every tick. **Verified** Moved to feet + 1 (head height against the
     pillar): all arms then complete. **Measured** (reps 1)
  3. **Cadenza, off-centre stops.** A path ends as soon as the feet enter the cell, so the eye can be about 0.33 off
     centre (live: eye x 13.17 in cell 13). Robustness sampled only ±0.25 on the axes, at 2 ticks per lost sample.
     A diagonal cell whose ray only grazes past a wall from the exact centre therefore won. **Measured**
     Fix: 8 offsets at ±0.3 (axes and diagonals). A lost offset costs `POSE_RECOVERY_TICKS / 8` = 10 ticks; the
     recovery cost comes from the 60 → 314 tick chest lap.
  4. **Integration, turn cap.**
     - Cause: `RotationUtils.reachable` judges the look after one capped turn of the aim processor
       (`ostinato.aim.deg`, 32°/tick, a pre-existing Ostinato PvP setting). A container more than 32° off the current
       heading never reads as reachable, and nothing turned the bot toward it.
     - Effect: in arm B the bot rejected 18 clear cells around the chest, including ones straight in front of its
       open face. It only succeeded once it stood next to the chest (286 ticks, 16 POSE_UNUSABLE). **Measured**
     - Fix, in the Cadenza branch of `GetToBlockProcess.rightClick` only: while a planned cell is on trial, face the
       nearest container. CHEST B went from 286 to 53–55 ticks with 0 POSE_UNUSABLE (2/2 reps). **Measured**
     - The stock path (arm A) has the same latent defect and was left as it is.
     - **Superseded** (after the main run, at the owner's direction): the facing workaround was removed and the
       defect fixed at its root; see "Turn cap: reach after the turn".
- The live cache also feeds `GetToBlock` natural chests (mineshafts; up to ~15 in range). Cadenza reports them as
  TARGET_UNLOADED and leaves them to the old goal. This affects all arms equally. **Verified**
- One rule was added after the pilots but before the main run. When the baseline never finishes a scenario, there
  is no pair to take a ratio from, so the rule uses the censored ratio (a timeout counts as 1201 ticks).
- Pilot signal, not part of the decision: PLACE_ROW B/C ran 1.30/1.37× A's ticks (reps 2). **Measured**
- Main run (reps 4, all 12 scenarios): see "Results".

## Turn cap: reach after the turn

- Cause, as above: `RotationUtils.reachableOffset` ray-traced with `IAimProcessor.peekRotation`, the look after
  **one** 32°/tick step. "Reachable" therefore meant "within about 32° of the current look", and nothing turned the
  bot toward targets behind it. This caused the CHEST failures in every arm and the Builder placement idling.
- Fix (option 1 of three offered; the visible 32°/tick turn cap is unchanged):
  - `IAimProcessor.peekSettledRotation(desired)`, a new default method: the rotation the look settles at with no
    per-tick limit (mouse-step quantisation kept). `LookBehavior.AbstractAimProcessor` implements it through
    `calculateMouseMove(..., capped = false)`.
  - `RotationUtils.reachableOffset` and Builder's placement ray (`BuilderProcess.possibleToPlace`) use it.
  - Callers still click only once the crosshair is on the target. Two did not: `MovementTraverse`'s iron-door
    opener and fence-gate branches clicked as soon as `reachable` held. They now pass
    `ctx.isLookingAt(...)` for the click. This is the one deliberate change in the movement layer, needed because
    `reachable` now means "after the turn".
  - Removed: the GetToBlock and Builder facing workarounds, `CadenzaSession.plannedTarget` and its test.
- The change affects every arm, including stock A, so the Cadenza A/B was rerun (see "Results").
- Tests after the change: targeted (Cadenza, Maestro, SettingCatalog) and full suite green, 1,403 tests,
  0 failures, 16 skipped. **Measured**

## Entities in the line of sight

- Prompt: after the B CLUSTER failure the owner suggested a mob had blocked the ore (**Reported**), then asked for
  entities to be handled as obstacles.
- First hypothesis, written before the runs and now **Contradicted**: "Minecraft's crosshair picks the entity over
  the block, so a click would not reach the ore."
  - True for the vanilla crosshair (`LocalPlayer.pick` in 26.3), but Baritone never uses it.
  - `BaritonePlayerContext.objectMouseOver` is a block-only ray (`RayTraceUtils.rayTraceTowards` → `level.clip`).
    `BlockBreakHelper` and the right-click path act on that block through the player controller directly. The server
    checks distance, not line of sight. **Verified** (code)
  - So an entity never blocks Baritone's mining or interaction.
- Live check, `MOB` scenario: EXPOSED with a cow that cannot move (NoAI) on the ore's near side. Each lap row now
  logs `mobs` and `mobHp`.
  - Reach check unchanged (`build/cadenza-mob-off.log`, reps 4): every arm 4/4. A 48, B 35, C 35 ticks, the same
    as EXPOSED. The cow stayed at 10.0 health every lap; arm A even ends overlapping it and B/C mine across it.
    **Measured**
  - With an entity-aware reach check (`build/cadenza-mob-on.log`, reps 4):
    - every arm 4/4;
    - A unchanged (48);
    - B and C slower, with medians about 65 and 52 against 35 and 1–2 POSE_UNUSABLE, from detouring around a cow
      that blocked nothing.
    - **Measured**
  - Pre-registered verdict for the entity-aware check: **worse** for B and C, neutral for A. It was reverted
    together with the related `cadenzaMine` click/budget changes.
- Kept, harness only:
  - every lap discards non-player entities in the arena, and mob spawning is off;
  - the opt-in `MOB` scenario;
  - the `mobs`/`mobHp` lap columns.
- Consequence: a mob is not a likely cause of the B CLUSTER failure, so that cause stays **Unknown**. Killing or
  pushing entities was not built: with nothing to unblock, it would only risk hitting villagers, pets, golems or
  players.

## Maestro (Stages 6–10)

- `maestro` (default off) makes `PathingBehavior.createPathfinder` return `MaestroPathFinder` instead of
  `AStarPathFinder`. Hierarchical and segmented routes still take precedence. **Verified**
- Search:
  - A* over the same movement graph with the same costs: `Moves`, `CostCalibration`, `JumpFailures`, `Favoring`,
    the dead set, and the Cadenza terminal charge.
  - The state is (cell, incoming heading in 16 sectors), created lazily per cell.
  - A heading change costs `maestroTurnCost` · 6 · (angle/180°)² ticks. With `maestroTurnCost` = 0 the result
    equals A*'s (`zeroTurnCostMatchesAStar`). **Verified**
  - Jump checks run immediately, not deferred: with sparse states there is nothing to gain from deferring them.
- Budgets: the same primary/failure timeouts, plus a state cap `maestroMaxStates` (1.5 M).
  - On the cap, an empty result or an exception, it falls back to `AStarPathFinder` with the remaining budget and
    the dead set. **Verified** (tests)
  - Cancellable: `cancel()` also cancels a running fallback. **Verified**
- Legato is reused, not duplicated: the `Path` built from Maestro's nodes goes through the same
  `AbstractNodeCostSearch.calculate` post-processing (Legato smoothing, route reuse, cutoffs). **Verified**
- PPE is not wired in. Maestro's search needs no physics prediction beyond what the movement costs already carry.
- Exact dominance pruning: a heading change costs at most `maxTurn` = `turn(0, 8)` = 6 · `maestroTurnCost`
  ticks. A state whose cost is at least a sibling heading's cost at the same cell plus `maxTurn` can never beat
  that sibling, so it is skipped, both before it is created and when it is popped. The objective is unchanged:
  `pruningKeepsTheObjectiveWithFewerStates` asserts an equal objective (1e-6) with fewer states. **Verified**
- Counters for live diagnosis: `MaestroPathFinder.searches/fallbacks/exceptions/capped/statesCreated`.
- Files:
  - new: `src/main/java/baritone/pathing/calc/MaestroPathFinder.java`,
    `src/test/java/baritone/pathing/calc/MaestroTest.java`
  - changed:
    - `PathNode` (sibling-state constructor), `PathingBehavior` (selection);
    - `Settings`, `SettingCategorizer` (`maestro`, `maestroTurnCost`, `maestroMaxStates`) and
      `setting-descriptions.json`;
    - `PathBench` (`maestro` finder);
    - `LegatoLiveTest` (`-Dostinato.legatotest.arms=maestro`). That file was already untracked before this work.
- `MaestroTest`: 10 tests, one of them a gated benchmark. All pass. **Verified**
  - In a pillar field Maestro's objective (move cost + turn charge) is never above A*'s.
  - A* here is not exactly move-cost optimal either: Maestro found a path that was also cheaper in move cost
    (194.62 vs 195.58). **Measured**
- Live A/B: `LegatoLiveTest` with `arms=maestro` runs A stock, B Legato, C Maestro and D Maestro + Legato.
  `legatoSearch` and `kinematicLine` are off in all four arms. Results: see "Results".
- Decision rule for the live run, written before it started (same form as Cadenza's):
  - Run: `LegatoCourse`, 4 legs (open, wall, pillars, corridor), reps 5, Latin-square order, Peaceful, timeout
    1500 ticks.
  - Each arm is compared with A on the paired median ticks ratio, per leg.
  - **better**: at least as many REACHED laps as A **and** a ratio ≤ 0.9 on at least 2 of the 4 legs.
  - **worse**: fewer REACHED laps than A, or a ratio ≥ 1.1 on at least 2 legs.
  - Otherwise **neutral**.
  - Maestro is classified by C vs A. D vs A is the combination. D vs B (Maestro added on top of Legato) is reported
    but decides nothing. `turned` (degrees per lap) is reported as Maestro's own target metric, also deciding
    nothing.
- Combined arms, rule written before the run:
  - Run: `CadenzaLiveTest` with `-Dostinato.cadenzatest.arms=combined`, the 12 standard scenarios, reps 4.
  - Arms: 1 stock, 2 Cadenza, 3 Maestro, 4 Cadenza + Maestro, 5 Cadenza + Maestro + Legato smoothing.
  - Each arm is classified against arm 1 per family, with the Cadenza rule.
  - Maestro's increment is arm 4 against arm 2, with the same rule.
  - The Maestro arms count only if the end-of-run counter shows Maestro searches.

## Results

### Cadenza live A/B (main run) — **Measured**

Source: `build/cadenza-live-ab.log`, analysed with `python build/cadenza-ab.py build/cadenza-live-ab.log`. Setup:
reps 4, seed 71006, Latin-square order, 144/144 laps, 0 deaths or falls, no stuck keys. Arms: A stock, B Cadenza,
C Cadenza + Legato smoothing. `presto`, `legatoSearch` and `kinematicLine` were off in all arms.

Columns:
- done: laps completed.
- med: median ticks.
- ratio: median paired ticks ratio vs A on reps where both arms finished, with a bootstrap 95% CI.
- cens.: the censored ratio, where a timeout counts as 1201 ticks.

| scenario | A done / med | B done / med | B ratio [CI] | C done / med | C ratio [CI] |
|---|---|---|---|---|---|
| EXPOSED | 4/4 · 48 | 4/4 · 35 | 0.73 [0.42, 0.73] | 4/4 · 35 | 0.73 [0.42, 0.73] |
| WALL | 4/4 · 51 | 4/4 · 38 | 0.75 [0.73, 0.75] | 4/4 · 38 | 0.75 [0.73, 0.75] |
| ABOVE | **0/4** | 4/4 · 37 | cens. 0.03 | 4/4 · 37 | cens. 0.03 |
| BELOW | 4/4 · 54 | 4/4 · 37 | 0.69 [0.69, 0.69] | 4/4 · 37 | 0.69 [0.69, 0.69] |
| MULTI | 4/4 · 131 | 4/4 · 100 | 0.76 [0.70, 0.85] | 4/4 · 100 | 0.75 [0.70, 0.85] |
| CLUSTER | 4/4 · 172 | 4/4 · 92 | 0.53 [0.52, 0.63] | 4/4 · 92 | 0.53 [0.52, 0.63] |
| OCCLUDED | 4/4 · 74 | 4/4 · 55 | 0.75 [0.62, 1.57] | 4/4 · 55 | 0.75 [0.62, 0.82] |
| HAZARD | 4/4 · 55 | 4/4 · 35 | 0.64 [0.64, 0.64] | 4/4 · 35 | 0.64 [0.64, 0.64] |
| CHEST | **0/4** | 4/4 · 53 | cens. 0.04 | 4/4 · 52 | cens. 0.04 |
| CHEST_HIGH | 4/4 · 60 | 4/4 · 26 | 0.43 [0.43, 0.43] | 4/4 · 26 | 0.43 [0.43, 0.44] |
| PLACE_ROW | 4/4 · 51 | 4/4 · 62 | 1.23 [1.17, 1.30] | 4/4 · 66 | 1.30 [1.22, 1.48] |
| PLACE_HIGH | 4/4 · 64 | 4/4 · 64 | 1.00 [1.00, 1.00] | 4/4 · 64 | 1.00 [1.00, 1.00] |

- With 4 reps the CIs are coarse, and some are degenerate: the laps are nearly deterministic, so bootstraps
  collapse.
- A failed ABOVE in all 4 laps and CHEST in all 4. The explanation given at the time (it stands under the ore, or
  on top of the chest) is **Contradicted** by the rerun: the cause was the turn-cap reach check. After the fix A
  completes both, 4/4.
- Classification under the pre-registered rules:
  - mining: B **better**, C **better** (32 vs 28 completions; ≤ 0.9 on 8/8 scenarios);
  - container: B **better**, C **better** (8 vs 4 completions; ≤ 0.9 on 2/2);
  - placement: B **worse**, C **worse** (8 vs 8 completions; ≥ 1.1 on 1/2, which counts as half).
- B vs C: no measurable difference. On paths this short, Legato smoothing changes nothing.

### Placement follow-up (post-hoc, not part of the decision) — **Measured**

- Cause, from the per-lap columns:
  - B/C idled about 10 ticks longer than A, with 1–2 POSE_UNUSABLE per lap.
  - This is the same turn-cap defect as the chest: Builder's `searchForPlacables` judges reach after one capped
    turn.
- Fix (since removed; see "Turn cap: reach after the turn"): in Builder's Cadenza branch, face
  `CadenzaSession.plannedTarget(feet)` while the cell is on trial (with a
  unit test).
- Rerun (`build/cadenza-live-place2.log`, reps 4): PLACE_ROW B 1.14 [0.73, 1.26], C 1.14 [0.71, 1.21]; PLACE_HIGH
  1.00. By the same rule this is still **worse**.
- Residual: the diagonal cells leave the row 120–170° off the arrival heading, against about 40° for A's stock goal.
  At 32°/tick that turn costs ticks, and Cadenza charges pitch but not yaw, because the arrival heading is unknown
  when candidates are scored.
- Consequence: Builder placement positioning now needs a second switch, `cadenzaBuilder` (default off). With
  `cadenza` on alone, only the mining and container integrations are active, which are the ones classified better.
  The live harness turns both switches on.


### Maestro offline benchmark — **Measured**

Source: `MaestroTest` gated benchmark (`OSTINATO_MAESTRO_BENCH=1`), report `build/pathbench/maestro.md`; the
unpruned run is kept as `build/pathbench/maestro-v1-unpruned.md`. 10 s primary timeout, 4 queries per row.
Timings are wall-clock on a shared desktop, so only large differences mean anything.

- Unpruned (all 5 terrains, 2 reps): failures A* 4/60, Maestro 17/60. Turn charge lower with Maestro (HILLS 9.9 →
  5.2, CAVES 24.1 → 18.2 ticks per path); 1 fallback in 245 searches.
- Pruned (HILLS, WATER, BREAK_PLACE only, 1 rep): failures A* 4/36, Maestro 8/36. 57.2 M states created,
  486 M dominated states skipped; 1 fallback in 115 searches.

| row | Maestro unpruned: fails · median ms | Maestro pruned: fails · median ms | A* (pruned run) |
|---|---|---|---|
| HILLS MEDIUM | 1/4 · 5555 | 0/4 · 896 | 0/4 · 370 |
| HILLS LONG | 4/4 · cap | 0/4 · 7958 | 0/4 · 3416 |
| WATER MEDIUM | 0/4 · 1355 | 0/4 · 363 | 0/4 · 80 |
| WATER LONG | 4/4 · cap | 0/4 · 5442 | 0/4 · 1197 |
| BREAK_PLACE SHORT | 0/4 · 568 | 0/4 · 130 | 0/4 · 36 |
| BREAK_PLACE MEDIUM | 4/4 · cap | 4/4 · cap | 0/4 · 6684 |
| BREAK_PLACE LONG | 4/4 · cap | 4/4 · cap | 4/4 · cap |

- Pruning removes Maestro's failures on long HILLS and WATER routes. Maestro is still about 2–4.5× slower than A*
  and fails BREAK_PLACE MEDIUM, where A* already needs about 6.7 s. In live play the A* fallback covers this,
  at the cost of the time spent before falling back.

### Cadenza live A/B rerun after the turn-cap fix — **Measured**

Source: `build/cadenza-live-ab2.log` (same command, reps 4, seed 71006, 144/144 laps, 0 deaths or falls). This
run supersedes the main run above for the classification, because the reach check changed in every arm.

| scenario | A done / med | B done / med | B ratio [CI] | C done / med | C ratio [CI] |
|---|---|---|---|---|---|
| EXPOSED | 4/4 · 62 | 4/4 · 35 | 0.60 [0.46, 0.73] | 4/4 · 35 | 0.61 [0.46, 0.73] |
| WALL | 4/4 · 51 | 4/4 · 38 | 0.75 [0.56, 0.75] | 4/4 · 38 | 0.75 [0.56, 0.75] |
| ABOVE | 4/4 · 54 | 4/4 · 37 | 0.69 [0.66, 0.70] | 4/4 · 37 | 0.69 [0.66, 0.70] |
| BELOW | 4/4 · 54 | 4/4 · 37 | 0.69 [0.69, 0.69] | 4/4 · 37 | 0.69 [0.69, 0.69] |
| MULTI | 4/4 · 131 | 4/4 · 99 | 0.76 [0.56, 0.82] | 4/4 · 110 | 0.83 [0.67, 0.90] |
| CLUSTER | 4/4 · 165 | **3/4** · 92 | 0.54 [0.53, 0.58] | 4/4 · 92 | 0.56 [0.53, 0.63] |
| OCCLUDED | 4/4 · 67 | 4/4 · 55 | 0.82 [0.56, 1.46] | 4/4 · 55 | 0.82 [0.56, 0.82] |
| HAZARD | 4/4 · 55 | 4/4 · 35 | 0.64 [0.64, 0.64] | 4/4 · 35 | 0.64 [0.64, 0.64] |
| CHEST | 4/4 · 69 | 4/4 · 53 | 0.77 [0.76, 0.79] | 4/4 · 54 | 0.79 [0.76, 0.82] |
| CHEST_HIGH | 4/4 · 60 | 4/4 · 27 | 0.45 [0.43, 0.45] | 4/4 · 26 | 0.43 [0.43, 0.43] |
| PLACE_ROW | 4/4 · 51 | 4/4 · 56 | 1.14 [0.86, 1.27] | 4/4 · 61 | 1.22 [0.98, 1.33] |
| PLACE_HIGH | 4/4 · 64 | 4/4 · 64 | 1.00 [1.00, 1.00] | 4/4 · 64 | 1.00 [1.00, 1.00] |

- The fix repaired stock A as well: ABOVE 0/4 → 4/4 and CHEST 0/4 → 4/4.
- Classification under the pre-registered rules:
  - mining: B **worse** (31 vs 32 completions, even though ≤ 0.9 on 8/8 scenarios); C **better** (32 vs 32;
    ≤ 0.9 on 8/8);
  - container: B **better**, C **better** (8 vs 8; ≤ 0.9 on 2/2), now with a real ratio instead of a censored one;
  - placement: B **worse**, C **worse** (8 vs 8; ≥ 1.1 on 1/2). `cadenzaBuilder` stays off.
- The B CLUSTER failure (rep 2):
  - Cadenza confirmed 3 of 4 ores, then gave up on `(13,201,10)` after its click budget (60 + break ticks)
    ran out, and blacklisted it.
  - The stock mine fallback then wandered (dug 11, ended 2 blocks below the arena) until the timeout.
  - Cause: **Unknown**.
  - A post-hoc CLUSTER-only rerun (`build/cadenza-cluster-repro.log`, reps 8, seed 71007, debug on) did not
    reproduce it: B 8/8 and C 8/8, every lap 92 ticks.
  - So across both runs, 1 of 24 Cadenza CLUSTER laps failed. A debug-only log now records eye, look, selected
    block and reach whenever Cadenza gives up on an ore (`MineProcess.cadenzaMine`).
  - The verdict stays **worse** for B: the rule is not relaxed after seeing the result.
- B vs C: no measurable difference except MULTI (C 110 vs B 99 ticks).

### Maestro live A/B — **Measured**

Source: `build/maestro-live-ab.log` (`LegatoLiveTest`, `arms=maestro`, reps 5, seed 71006, 80/80 laps REACHED, 0 keys
stuck). Columns are the median ticks and the paired median ratio against A; turned is the median degrees per lap.

| leg | A ticks / turned | B (Legato) | C (Maestro) | D (Maestro + Legato) |
|---|---|---|---|---|
| open | 291 / 102 | 284 · 0.976 / 143 | 292 · 1.003 / 140 | 284 · 0.976 / 142 |
| wall | 302 / 68 | 294 · 0.974 / 293 | 302 · 1.000 / 61 | 295 · 0.977 / 285 |
| pillars | 294 / 259 | 290 · 0.986 / 365 | 294 · 1.000 / 254 | 290 · 0.986 / 346 |
| corridor | 293 / 115 | 286 · 0.976 / 267 | 293 · 1.000 / 132 | 286 · 0.976 / 248 |

- Classification under the pre-registered rule:
  - Maestro (C vs A): **neutral** (20 vs 20 reached; ≤ 0.9 on 0/4, ≥ 1.1 on 0/4).
  - The combination (D vs A): **neutral** (0.976–0.986).
  - D vs B is about 1.00 everywhere.
- Turning is not reduced live: C turned more than A on open and corridor and slightly less on wall and pillars.
- Validity caveat:
  - This run did not log Maestro's search counter. That C used Maestro rests on the setting and on
    `createPathfinder` (hierarchical and decision matrix are both off by default), not on a measurement: **Unknown**.
  - The counter is now logged by both harnesses (`maestro searches=... fallbacks=...`); see the combined run.

### Combined arms 1–5 — **Measured**

Source: `build/combined-live.log`. Setup: reps 4, seed 71006, 240/240 laps, 0 deaths or falls, 0 stuck keys.
Analysis: `python build/cadenza-ab.py build/combined-live.log 1`, plus base `2` for Maestro's increment.

- Maestro really ran: `maestro searches=271 fallbacks=38 capped=0 exceptions=0`. Arms 3–5 are therefore valid.
  About 14% of Maestro searches fell back to A*.
- Ratios against arm 1, median per scenario:
  - Mining, all 8 scenarios: arms 2/4/5 run 0.63–0.83; arm 3 runs 0.99–1.01.
  - Container: arms 2/4/5 run 0.43–0.79; arm 3 runs 1.00–1.01.
  - PLACE_ROW: arm 2 1.25, arm 3 1.00, arm 4 1.24, arm 5 1.12.
  - PLACE_HIGH: 1.00 for every arm.
- No CLUSTER failure this time (Cadenza arms: 12/12).

| family | 2 Cadenza | 3 Maestro | 4 Cadenza + Maestro | 5 + Legato | 4 vs 2 (Maestro's increment) |
|---|---|---|---|---|---|
| mining | better | neutral | better | better | neutral |
| container | better | neutral | better | better | neutral |
| placement | worse | neutral | worse | worse | neutral |

## Final classification

- **Cadenza (mining, container): better.**
  - Better in the main run, in the rerun after the turn-cap fix (C better; B worse only by the single unexplained
    CLUSTER timeout) and in the combined run.
  - Typical saving: 17–57% of the ticks.
  - `cadenza` stays default **off**, as an experimental feature. It is ready to enable.
- **Cadenza for Builder placement: worse** in every run (PLACE_ROW 1.12–1.30, PLACE_HIGH 1.00). It stays behind
  `cadenzaBuilder` (default off).
  - Cause: Cadenza does not model the yaw turn at arrival.
- **Maestro: neutral** live (Legato course and the combined arms), with real searches. Offline it lowers the turn
  charge but is 2–4.5× slower than A*. Default **off**. Hierarchical or incremental Maestro was not built, because
  profiling showed no live benefit to scale up.
- **Legato smoothing on top of Cadenza or Maestro: neutral** on these short paths.
- **Turn-cap reach fix** (`peekSettledRotation`): a defect fix in the shared reach check, always on. It repaired
  stock ABOVE and CHEST (0/4 → 4/4).
- **Entity-aware reach: contradicted and reverted**; see "Entities in the line of sight".

## Limitations

- Builder break goals and Cadenza for `GetToBlock` without right-click are not covered.
- Short horizon is unvalidated, so it stays off.
- Cadenza's ray test is a sampled face check, not the client's exact `RotationUtils.reachable`. A disagreement
  shows up as POSE_UNUSABLE and is recovered from by replanning.

- One B CLUSTER timeout in 1 of 36 Cadenza CLUSTER laps across all runs has an **Unknown** cause. The debug log
  added to `MineProcess.cadenzaMine` should capture it if it recurs.
- Live scenarios are small arenas with 4 reps; the CIs are coarse.

## Next step

- Placement: score the arrival yaw (path heading into the cell against the direction to the target) in
  Cadenza's terminal cost, then rerun PLACE_ROW against the same rule.
- Maestro: investigate the 14% A* fallback rate. Find a live scenario where turning dominates before investing
  more.
- Nothing has been committed or pushed.
