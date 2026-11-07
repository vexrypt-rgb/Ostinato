# Cadenza v2.1 — Builder Execution Diagnosis & Stable Mining Goals: Handoff

Status: **complete** (§10), uncommitted, for review. Evidence labels: **Verified** (read in the code, or a test),
**Measured** (live run, log named), **Reported** (from an earlier handoff, not re-checked), **Contradicted**,
**Unknown**.

## 1. Branch, HEAD, worktree, preserved work

- Worktree `C:\Users\redfa\Documents\MinecraftDev\Ostinato-ppe`, branch `feat/predictive-physics`, HEAD `1aed5cc2`.
  **Verified.**
- At the start of v2.1 there were 97 uncommitted paths (modified tracked files and untracked files: PPE, Presto,
  Legato, Maestro, Cadenza v1/v2, Grace Note, Bombard, Threat, their handoffs). None was reset, stashed, cleaned,
  committed or pushed. **Verified** (`git status --short`).
- Snapshot of the files v2.1 edits, taken before the first edit: scratchpad `pre-v21/pre-v21.tar` (BuilderProcess,
  MineProcess, `pathing/cadenza` main and test, Settings, PathExecutor, PathingBehavior, `build/cadenza-v2-ab.py`).
- The v2 TWO_POSE aim-margin fix (`Cadenza.AIM_TOLERANCE_DEG` = 2°, `Cadenza.steady`, `MineProcess.steady`) is kept,
  unchanged, in planner and executor. No margin, give-up timer, retry, timeout or entity reach rule was changed.
  **Verified.**
- Maestro, Legato, Presto, PPE: not modified. Fugue: not created.

## 2. Audit (Stage 0)

Read: `CADENZA_V2_HANDOFF.md`, `MAESTRO_CADENZA_HANDOFF.md`, and the code below. All **Verified** unless marked.

- **Builder placement.** `BuilderProcess.onTick`: `searchForPlacables` scans ±5 blocks sideways and dy −5..+1 from
  the feet for a placeable target (`possibleToPlace`, sneaking-eye rays at `peekSettledRotation`; v2 `leastTurn`
  picks the least turn). With Cadenza on: confirm the current interaction when its cell is no longer replaceable,
  `tick()` the give-up budget, then ALIGN/CLICK if something is placeable, safe, on ground and the place delay is 0
  (`cadenza.attempt(placed cell, 40)`), else `nothingReachable`. The goal is `assemble` →
  `cadenzaPlacementGoal` (targets in the search box, `extras` = the old per-target goals sorted by feet distance)
  wrapped in `JankyGoalComposite(primary, GoalComposite(toBreak))`. Command: always
  `FORCE_REVALIDATE_GOAL_AND_PATH`.
- **Terminal scoring.** `CadenzaGoal`: a cell's terminal cost is its cheapest target's (v1) or the turn model's
  (v2 `cadenzaTurnAware`). Unchanged in v2.1.
- **Placement execution and alignment.** ALIGN turns to `toPlace.rot`; CLICK when the crosshair is on the placed-
  against face. Unchanged.
- **Mining goals.** `MineProcess.updateGoal`: `prune(context, locs, filter, mineMaxOreLocationsCount, blacklist,
  droppedItemsScan())` → stock `GoalComposite(coalesce(loc)…)` in `prune`'s order, or Cadenza `cadenzaGoal`: up to
  `cadenzaMaxTargets` (8) nearest ores become targets; every other location (farther ores, ores with no cell,
  **dropped items**) becomes an `extras` goal (`GoalTwoBlocks`/`GoalThreeBlocks`), in feet-distance order. Command:
  `REVALIDATE_GOAL_AND_PATH` (`FORCE_…` under `legitMine`). `mineScanDroppedItems` defaults to true.
- **Goal equality and hashing.** `GoalComposite`: ordered `Arrays.equals`/`Arrays.hashCode`. `JankyGoalComposite`:
  `Objects.equals` on primary and fallback. `CadenzaGoal.equals`: options, **ordered** `Arrays.equals(extras)`,
  the terminal map, and (v2) the results as a set unless STRICT batch; `hashCode` =
  `terminal.hashCode()*31 + Arrays.hashCode(extras)` (ordered).
- **Where goal equality matters.** `PathingControlManager.forceRevalidate`: soft-cancel when the path's dest is not
  in the new goal and the new goal is not `equals` the path's (Builder; MINE under legitMine).
  `PathingBehavior`: the dead-node memory (`deadGoal`), the per-start timeout escalation (`plannedStartsGoal`) and
  `retargetMovingGoal` keep their state only while the goal stays `equals`; `RouteReuse` reuses only for an equal
  goal. Under plain `REVALIDATE` a changed goal is set without a cancel.
- **Replanning triggers.** REVALIDATE cancels only with `cancelOnGoalInvalidation` and a path that used to end in the
  goal and no longer does; FORCE also on the `!equals` rule above.
- **CadenzaCache.** Keyed by (kind, pos, reach, eye, drop), TTL 1000 ms; an entry is reused while **the target's own
  block state** is unchanged. Its doc: "a stale set can at worst send the bot to a cell it then replans from".
- **CadenzaSession.** `attempt` / `confirm` / `invalidate` / `fail` / `tick`; `nothingReachable` marks a cell bad
  after 10 ticks (30 s). One per process.
- **Harness.** `CadenzaLiveTest` (`-Dostinato.cadenzatest.*`), arms `v2` (A stock, B `cadenza`+`cadenzaBuilder` =
  v1 + aim fix, C B + `cadenzaTurnAware`, D C + `cadenzaBatch`), Latin square `(k+rep+scenario) mod 4`, Peaceful,
  death/fall detection, seed 71006.
- **Settings.** `cadenza`, `cadenzaBuilder`, `cadenzaTurnAware`, `cadenzaBatch` default off; unchanged.

## 3. Instrumentation added (harness only)

`CadenzaLiveTest`, per lap row (all behind the existing test harness; nothing in production paths):

- `cats=`: **exclusive** per-tick categories, driver (P pathing / C clicking / I neither) × Builder phase
  (placement laps: NONE nothing placeable, BLOCKED placeable but not allowed yet, ALIGN, CLICK, BREAK, OTHER) ×
  `+calc` (a path calculation in progress). Every tick lands in exactly one, so they sum to the lap ticks:
  36/36 laps reconcile (`build/v21-place-cmp.py`). **Verified.**
- `goalChanges` (the goal not `equals` the previous tick's), `repos` (pathing right after placing), `calcTicks`.
- For mining (§7): `orderOnly` / `setChanges` (a goal change whose sub-goals are the same set, recursively, vs a
  different set), `gold` (items held at the end), `pickups` (items gained during the lap), `groundItems` (item
  entities left in the arena), `scan` (`mineScanDroppedItems`), and arms `v21mine`.
- Existing: nav/click/idle, travelled, turned, calcs, cancels, firstClick, arrivals, arrYaw, alignTurn, poseUnusable,
  and the per-change trace (`-Dostinato.cadenzatest.trace=true`).

Path calculation time per search and navigation requests are covered by `calcs`, `calcTicks` and `+calc`; Cadenza's
own time is `cadenzaMs`.

## 4. Builder: where the time goes (Measured)

Run: `build/v21-place-trace.log` (pre-fix sources = v2), PLACE_ROW, PLACE_ROW_BACK, PLACE_SPREAD, reps 3, arms v2,
trace on. 36/36 DONE. "stall" = ticks in `I.NONE` without a calculation: idle, nothing placeable from where it
stands, and no path being computed.

| Scenario | Arm | Ticks (median; each) | stall | calcs | cancels | goal changes | repos |
|---|---|---|---|---|---|---|---|
| PLACE_ROW | A | 52 (84, 52, 46) | 0 | 3 | 10 | 2 | 2 |
| PLACE_ROW | B | 61 (61, 70, 61) | 14 | 5 | 13 | 4 | 2 |
| PLACE_ROW | C | 54 (54, 53, 63) | 7 | 3 | 10 | 2 | 1 |
| PLACE_ROW | D | 57 (54, 57, 59) | 7 | 4 | 11 | 3 | 1 |
| PLACE_ROW_BACK | A | 52 (52, 56, 50) | 0 | 4 | 10 | 2 | 2 |
| PLACE_ROW_BACK | B | 54 (54, 54, 55) | 7 | 3 | 10 | 3 | 1 |
| PLACE_ROW_BACK | C | 54 (54, 58, 54) | 2 | 3 | 11 | 2 | 1 |
| PLACE_ROW_BACK | D | 58 (58, 56, 61) | 3 | 4 | 12 | 3 | 2 |
| PLACE_SPREAD | A | 51 | 0 | 3 | 9 | 2 | 2 |
| PLACE_SPREAD | B | 50 | 1 | 3 | 8 | 2 | 2 |
| PLACE_SPREAD | C | 50 | 1 | 3 | 8 | 2 | 2 |
| PLACE_SPREAD | D | 43 | 0 | 2 | 10 | 1 | 1 |

- Stock never stalls: every idle tick with nothing placeable has a calculation running. Cadenza stalls 6–17 ticks on
  PLACE_ROW, 2–7 on PLACE_ROW_BACK, ≤1 on PLACE_SPREAD (targets far apart). **Measured.**
- Navigation ticks and the first click (tick ~25) are about the same in every arm; the alignment turn is not where
  the remaining loss is. **Measured.**
- **Timeline** (trace, lap 2, C PLACE_ROW rep 0): t=25 places (12,F,10) from (8,F,10); t=26 a new goal, path to
  (10,F,10), AT_GOAL t=34; **t=35–41 idle**, nothing placeable, no calculation; t=41 the goal's value changes
  (hash 195193b5) with nothing in the world changed since t=25 — the memo's 1 s TTL running out; re-path to
  (11,F,9), arrives t=47; t=50–54 places 14 and 13. Same in B (lap 1: waits at (9,F,10) t=33–40, re-paths to
  (10,F,8); again t=55–60 before 14) and in PLACE_ROW_BACK C (lap 5: walks to the stale cell (10,F,10) at t=38, the
  goal refreshes at t=41). **Measured.**

**Root cause (Verified, code + trace).** `CadenzaCache` reuses a target's candidate set for 1000 ms unless *that
target's own block* changed. Placing (12,F,10) does not change (13,F,10) or (14,F,10), so their cached cells — chosen
west of 12, placing against faces 12 now covers or seeing past where 12 now stands — stay in the goal for up to ~20
ticks. The bot walks to such a cell, finds nothing placeable (`NONE`), and is "in goal", so nothing re-plans until
the TTL drops the set. The cache doc's premise ("at worst a cell it then replans from") fails here: in goal, nothing
replans. Stock has no memo, so it re-plans on the next tick. This is a v1 defect (B shows it most), not a turn-model
or alignment defect.

Ruled out: alignment (arrival yaw error and align ticks are small and similar; first click equal), navigation speed
(equal nav ticks), path calculation time (stock idles with `+calc`, Cadenza without), `nothingReachable` (its 10-tick
limit is longer than most stalls; poseUnusable fired once).

## 5. Builder fix

Narrowest layer: the memo. A block the process itself changed is the event that makes nearby candidate sets stale.

- `CadenzaCache.invalidateNear(BlockPos changed)`: drop every entry whose target is within its reach +
  `NEAR_MARGIN` (2) of the changed block (a cell can only use, or be blocked by, a block within that distance).
- `CadenzaSession.confirm()` calls it with the confirmed target (the cell just placed, or broken) before clearing it.
  Callers: Builder (placed), MineProcess (broken), GetToBlock (container opened: no world change; the drop just
  costs a recompute of the nearby sets).
- Nothing else: no turn penalty, no movement change, no retry, no timeout, no search change. Stock never reaches
  this code (`cadenza` off). The cache key now holds x, y, z instead of a packed long (same identity).

**Diagnostic rerun after the fix (Measured, `build/v21-place-trace-post.log`, the same command; 36/36 DONE,
36/36 reconcile).** Not the pre-registered evidence (§8–9); n = 3.

| Scenario | Arm | Ticks pre → post (median) | stall pre → post | cancels pre → post |
|---|---|---|---|---|
| PLACE_ROW | A stock | 52 → 51 | 0 → 0 | 10 → 11 |
| PLACE_ROW | B v1 | 61 → 44 | 14 → 0 | 13 → 9 |
| PLACE_ROW | C turn-aware | 54 → 47 | 7 → 0 | 10 → 11 |
| PLACE_ROW | D batch | 57 → 45 | 7 → 0 | 11 → 11 |
| PLACE_ROW_BACK | A | 52 → 52 | 0 → 0 | 10 → 11 |
| PLACE_ROW_BACK | B | 54 → 49 | 7 → 0 | 10 → 10 |
| PLACE_ROW_BACK | C | 54 → 49 | 2 → 0 | 11 → 10 |
| PLACE_ROW_BACK | D | 58 → 52 | 3 → 0 | 12 → 13 |
| PLACE_SPREAD | A / B / C / D | 51 / 50 / 50 / 43 → the same | ≤1 → ≤1 | unchanged |

The stall is gone in every Cadenza arm; stock is unchanged (it never reaches the code). Goal changes per lap drop
to stock's 2. PLACE_SPREAD, where the targets are too far apart to occlude each other, is tick-for-tick identical,
as expected for a fix that only fires near a changed block.

## 6. Tests

`src/test/java/baritone/pathing/cadenza/CadenzaV21Test.java`, 17 tests (9 Builder/memo below, 8 mining-goal in
§7.3), all passing; with CadenzaTest 29 and CadenzaV2Test 21: 67/67 (`build/v21-cadenza-tests.log`). Full unit suite
after both fixes (`build/v21-full-suite.log`): 219 suites, 1441 tests, 16 skipped, 0 failures, 0 errors (v2
baseline 1424/16; +17 = the new class). **Verified.**

- `placingABlockChangesItsNeighboursCandidates`: the PLACE_ROW geometry — placing 12 changes 13's candidates.
- `withoutAConfirmTheMemoKeepsTheStaleSet`: pins the defect (no confirm → the same stale set within the TTL).
- `confirmingAPlacementRefreshesTheTargetsNearIt`: after confirm, 13 and 14's sets equal a fresh computation, and
  the goal equals (and hashes as) one built from scratch on the same world.
- `confirmingABreakRefreshesTheTargetsNearIt`: the same for a broken block (mining).
- `farTargetsKeepTheirSets`: a target 15 blocks away keeps its cached set.
- `invalidateNearIsBoundedByReachPlusMargin`.
- `lifecycleWithoutAnInteractionKeepsTheMemo`: confirm/fail without an interaction change nothing; budget give-up
  still hands the target back.
- `repeatedTargetIsOneInteraction`, `candidatesAreStableAcrossSessions`.

## 7. Mining goals (Part II)

### 7.1 What the code does with dropped items (Verified)

- `droppedItemsScan()` lists `entity.blockPosition()` of every matching `ItemEntity` in the client level, plus the
  Louca "anticipated drops" (the selected ore block for `mineDropLoiterDurationMSThanksLouca` = 250 ms after it is
  looked at). It is the *observed* block position of the entity: an item that bounces into the next block is a new
  coordinate; there is no logical item identity.
- `prune` drops a drop location within 3 blocks of a still-present minable ore, and sorts every location by
  distance from the player — **stock's `GoalComposite` is itself in player-distance order**, so its equality is
  order-sensitive too.
- `rescan` (every `mineGoalUpdateInterval` = 5 ticks, on the executor) adds the drops to `knownOreLocations`;
  `updateGoal` keeps a drop location only while an item is still there.
- Cadenza: drops never become targets; they ride along as `extras` goals (`GoalTwoBlocks`/`GoalThreeBlocks` at the
  item's block) at terminal cost 0, in feet-distance order. `CadenzaGoal.equals`/`hashCode` compared them **in
  order** (`Arrays.equals`/`Arrays.hashCode`).

### 7.2 Controlled experiment (Measured)

`build/v21-multi-drops.log`, MULTI, reps 5, traced, arms `v21mine` (Latin square; this build already has the §5
memo fix). Completion = all three gold blocks broken; pickups are not part of completion, so items are reported
apart: `gold` = held at the end (the third block's drop cannot be collected before the lap ends, so 2 is all
of them), `ground` = item entities left in the arena.

| Arm | scan | done | ticks (median; each) | travelled | calcs | cancels | goal changes (order-only / set) | gold | ground |
|---|---|---|---|---|---|---|---|---|---|
| A stock | on | 5/5 | 132 (154, 133, 132, 131, 130) | 21.5 | 3 | 0 | 2 / 2 | 2 every lap | 0 |
| B stock | off | 5/5 | 117 (124, 115, 117, 117, 115) | 21.3 | 3 | 0 | 2 / 2 | 0, 2, 2, 1, 2 | 2, 0, 0, 1, 0 |
| C Cadenza | on | 5/5 | 102 (100, 102, 98, 102, 102) | 16.3 | 5 | 0 | 0 / 4 | 2 every lap | 0 |
| D Cadenza | off | 5/5 | 82 (82 ×5) | 10.0 | 3 | 0 | 0 / 2 | 0 every lap | 2 every lap |

Path destinations per lap (`build/v21-multi-paths.py`; `*` = a Cadenza candidate cell):

- C, every rep: `10,200,14* > 10,200,14 > 11,200,14 > 14,200,11* > 14,200,11 > 15,200,10 > 12,200,7*`. It mines
  the first block from a candidate cell, then walks into the broken block's cell (the drop), and the same for the
  second block.
- D, every rep: `7,200,12* > 9,200,10* > 11,200,10*`. Candidate cells only, no pickups.
- A: `11,200,14 > 15,200,10 > 11,200,6`, plus 6–10 ticks standing "in goal" on a drop cell with no path, waiting for
  the item's pickup delay. B is the same without the waits.

Answers to the brief's questions:

| Question | Answer |
|---|---|
| Does item motion change the goal coordinates? | **Yes** (Verified, code: block position of the entity; Measured: `known` 2–6 within a lap). These are real set changes, not reorderings. |
| Does the order change while the set is the same? | **Yes in stock** (2 order-only changes per lap, `GoalComposite`). **Not in Cadenza MULTI** (0); one ore target and ≤2 extras leave little to reorder. |
| Is equality order-sensitive? | **Yes**, both `GoalComposite` and `CadenzaGoal` (Verified). |
| Does it cause cancels or recalcs? | **No** on the miner: 0 cancels in all 20 laps. `REVALIDATE` does not cancel on inequality; calcs are 3 (5 with Cadenza + scan, the pickup legs). It *would* soft-cancel a partial path under `FORCE_REVALIDATE` (Builder always, miner with `legitMine`) and resets the planner's dead-node memory and timeout escalation (Verified, code). |
| Do pickups explain the long routes? | **Yes.** Scan off removes the pickup legs: Cadenza 102 → 82 ticks, 16.3 → 10.0 blocks, the extra destinations disappear. But it leaves both collectable items on the ground every lap. |
| Without Cadenza? | **Yes, the same**: stock 132 → 117 ticks with scan off, and it loses items (3 of 10 left on the ground). |

So scan-off is **not** faster at the same task: it skips required pickups. With items collected (scan on), Cadenza
is 102 vs stock 132 ticks, collecting the same 2 items in every lap.

v2's MULTI long mode (137–150 ticks, 3/5 laps in arm D batch, 2/4 in a traced arm B run) did not occur in this run's
non-batch Cadenza arm (0/5; v2's own A/B arm B also had 0/5). v2 found its long-lap path ends at (11,200,15) and
(16,200,11): drop cells one block off the broken blocks — the same pickup legs, with the item bounced. See §7.4 for
the batch control.

### 7.4 Batch control: v2's long mode (Measured)

`build/v21-multi-drops-batch.log`: the same, with C and D also on `cadenzaTurnAware` + `cadenzaBatch` (v2's arm D,
which had the long mode in 3/5 laps). This build has both v2.1 fixes; the `reorders` counter counts ticks where
the goal stayed equal but its extras came in another order.

| Arm | scan | ticks (each) | travelled | cancels | gold | ground | reorders |
|---|---|---|---|---|---|---|---|
| A stock | on | 164, 137, 135, 137, 133 | 21.9 | 0 | 2 every lap | 0 | – |
| B stock | off | 120, 115, 119, 119, 120 | 21.0 | 0 | 1, 2, 0, 1, 1 | 1, 0, 2, 1, 1 | – |
| C batch | on | 111, **138**, 121, 107, 108 | 17.9 | 0 | 2 every lap | 0 | 1 lap with 1 |
| D batch | off | 80, 77, 91, 78, 79 | 9.6 | 0 | 0 | 2 every lap | 0 |

- The long lap (C, 138 ticks, 25 blocks) has one extra destination: `… 15,200,10 > 10,200,6 > 12,200,14*`. (10,200,6)
  is the cell next to the broken block (11,200,6): the drop bounced one block and the bot walked to it. That is the
  same shape as v2's long laps, which ended at (11,200,15) and (16,200,11). **Measured.**
- With scan off, no lap is long (77–91), and every item is left behind.
- **Conclusion:** MULTI's long routes are pickup legs to items that have bounced off the broken block. Goal-ordering
  instability does not cause them: there were no cancels in any arm, and a reorder happened on 1 tick in 20 laps.
  Collecting the items is the task's behaviour (scan is on by default), so they are not removed. **Measured.**

### 7.3 Fix: order-insensitive extras (narrowest layer)

The demonstrated defect in Cadenza's own layer is that its goal identity depended on the order its caller listed
an unordered union in. That order is the feet distance, so it changes as the bot walks. It did not show as cancels
on the miner (REVALIDATE). It does matter under FORCE_REVALIDATE (Builder, `legitMine`) and for the planner's memory
of an unchanged goal.

- `CadenzaGoal`: a `extrasSet` (a `HashSet` of the extras, built once per goal; tolerates a null as the array did).
  `equals` compares it; `hashCode` = `terminal.hashCode() * 31 + extrasSet.hashCode()`, consistent with `equals`.
  Nothing else in the goal depends on the order (in goal, heuristic and terminal are a min/union; test below).
- The targets keep v2's rule: compared as a set unless STRICT batch, whose order is part of the task.
- Not changed: `GoalComposite` (upstream; stock behaviour is kept, and its order-only changes were measured harmless),
  `droppedItemsScan`, `prune`, the pickup behaviour, and which items are collected. No entity tracking was added.
  Logical identity vs observed position: a goal's identity is now its set of observed cells. An item that really
  moved is a real change and still changes the goal (test). No item identity was invented.
- Task ownership: MineProcess still builds and owns the goal; no new owner, no Fugue.

Tests (`CadenzaV21Test`, all passing; Verified): `extrasInAnotherOrderAreTheSameGoal` (v1 and turn-aware options,
equal hash, one element in a HashSet), `rebuiltFromTheSameWorldIsTheSameGoal`,
`differentExtrasOfTheSameSizeAreDifferentGoals`, `aMovedRemovedOrNewItemChangesTheGoal` (moved one block, picked
up, new drop, another goal kind at the same cell), `aRepeatedExtraIsTheSameUnion`,
`inGoalHeuristicAndTerminalDoNotDependOnOrder` (exhaustive over a 24×3×24 box),
`strictBatchTargetsStayOrdered`, `aReorderAloneWouldNotCancelAPartialPath` (the `forceRevalidate` condition, as a
proxy: a reorder alone no longer cancels, a different set still re-plans). TWO_POSE: v2's
`grazingCellsAlongAWallAreNotCandidates` and `steadyNeedsTheMarginBothWays` still pass.

## 8. Pre-registration (v2.1 final runs)

Written before either final run. Common to both: `CadenzaLiveTest`, Peaceful, death/fall detection, seed 71006,
start (2.5, 200, 10.5), the same inventory, Latin square `(k + rep + scenario) mod 4`, lap timeout 1200 ticks.
Presto, Legato search, Legato, Maestro, kinematicLine and `cadenzaBatch` off in every arm. A final run is repeated
only for a harness defect, and any repeat is recorded here. Metric: lap ticks to DONE, paired by rep; per scenario
the median paired ratio, a bootstrap 95% CI and wins/n. A non-DONE lap counts as a non-completion and, in the
censored ratio, as 1200 ticks; both ratios are reported.

### 8.1 Builder (`-Dostinato.cadenzatest.arms=v21place`)

| Arm | Settings |
|---|---|
| A | stock |
| B | v2 pre-fix: `cadenza` + `cadenzaBuilder` + `cadenzaTurnAware`, the v2.1 memo invalidation off (`CadenzaSession.invalidateOnConfirm = false`, harness-only switch) |
| C | v2.1: B with the invalidation on (the shipped code path) |
| D | v1 + fix: `cadenza` + `cadenzaBuilder`, no turn model, invalidation on |

Scenarios: PLACE_ROW, PLACE_ROW_BACK, PLACE_SPREAD (the "good" scenario: no regression in v2), PLACE_TRENCH (v2 C/A
1.05). n = 8 reps per scenario per arm (two Latin-square cycles), 128 laps, one run (`build/v21-place-ab.log`).
Secondary: stall ticks, calcs, cancels, goal changes, stray blocks, held blocks, cadenzaMs.

Classification of the v2.1 Builder (arm C; rule as v2: BETTER = completions ≥ and median ratio ≤ 0.9 on at least
half the scenarios; WORSE = fewer completions or ratio ≥ 1.1 on at least half):

- **A improvement**: C BETTER than A, completions ≥ A, and no scenario C/A ≥ 1.1.
- **B regression removed**: not A; PLACE_ROW C/A ≤ 1.05 and PLACE_ROW_BACK C/A ≤ 1.05; no scenario C/A ≥ 1.1;
  completions ≥ A.
- **C none**: neither, and completions not lower than A.
- **D reliability regression**: fewer completions than A or B, or any FAILED/TIMEOUT/DIED/FELL lap, or more stray
  blocks than A.
- Also reported, not deciding: C/B (the fix alone) and D/A (v1 + fix).
- No default changes on this run alone: `cadenzaBuilder` stays off whatever the class.

Arm B's switches, harness-only (package-private statics, always at their shipped value outside the harness, reset at
the end of a run): `CadenzaSession.invalidateOnConfirm = false` (no §5 memo drop) and `CadenzaGoal.orderedExtras =
true` (v2's ordered extras comparison). So B runs v2's code paths exactly.

### 8.2 Mining validation (`-Dostinato.cadenzatest.arms=v21mine -Dostinato.cadenzatest.v21mine.prefix=true`)

| Arm | Settings |
|---|---|
| A | stock (`mineScanDroppedItems` on, its default) |
| B | v2 pre-fix Cadenza: `cadenza` (v1 + the aim fix, no turn model, no batch; v2's arm B, the mining config v2 classed BETTER than stock), both v2.1 fixes off, scan on |
| C | v2.1 Cadenza: B with both fixes on, scan on |
| D | C with `mineScanDroppedItems` off (control: no drops) |

Scenarios: MULTI, TWO_POSE, EXPOSED, OCCLUDED. n = 8 reps per scenario per arm, 128 laps, one run
(`build/v21-mine-ab.log`). Primary: lap ticks. Reported apart, per arm: items held at the end (`gold`), item
entities left (`ground`), pickups, blocks mined (= 3 − `left` on MULTI), give-ups (`failed`), dug, cancels, calcs,
goal changes, order-only, reorders, poseUnusable, cadenzaMs.

**Incomplete collection.** An arm with fewer items collected than its comparison arm is not called faster on that
scenario, whatever its ticks: the comparison is reported as "not comparable (skips pickups)". D is a control and is
never compared for speed against an arm that collects.

Classification of the v2.1 mining-goal change (C against B; C against A by the v2 rule is also reported):

- **A improvement**: C BETTER than B over the four scenarios, completions and items ≥ B's and ≥ A's.
- **B defect removed**: not A; no scenario C/B ≥ 1.1; completions and items ≥ B's; and either MULTI C/B ≤ 0.95
  or fewer MULTI laps of ≥ 125 ticks (v2's long mode) in C than in B.
- **C none**: neither of the above, with completions and items not lower than B's.
- **D reliability regression**: fewer completions or items than B, or more give-ups/timeouts than B, or any
  TWO_POSE lap not DONE.
- Preserving Cadenza's mining advantage is checked as: C not WORSE than A by the v2 rule, with items ≥ A's.

### 8.3 Post-hoc diagnostic: drained laps (NOT pre-registered; added after §8.2's result)

**Why.** §8.2 found Cadenza (B and C) ending TWO_POSE laps with one gold item still on the ground (15/16 laps); stock
had none left. The harness ends a MINE lap at the last block break and then cancels. But `MineProcess.rescan`
keeps the miner running on the dropped items alone until none are known (`MineProcess.java:408-419`, Verified). So
an item on the ground at the cut is one the task would still have collected, not one it skipped.

**How.** Harness-only flag `-Dostinato.cadenzatest.drain=true` (off by default). A MINE lap is DONE when the blocks
are gone *and* the miner is no longer active, capped at 300 ticks after the blocks. The row adds `blocksDone`
(ticks to the last break) and `drainCapped`. Arms are as in §8.2 (`v21mine` + `prefix`). Scenarios: MULTI and
TWO_POSE (the two with drops; EXPOSED/OCCLUDED leave no items in any arm), n = 8, 64 laps,
`build/v21-mine-drain.log`. Read with the same script; the primary is ticks to the miner finished.

This decides nothing that §8.2 classified. It only answers whether Cadenza's mining speed-up survives once every
pickup is counted.

**First attempt invalid (harness defect, recorded): `build/v21-mine-drain-mineractive.log`.**
- "Miner no longer active" never happens: `exploreForBlocks` defaults to true (`Settings.java:1323`), so with no ore
  left the miner explores.
- Result: TWO_POSE hit the 300-tick cap in every lap, with `dug` = 6 (stone), and MULTI ran about 200 ticks after
  its blocks with about 90 calcs. The measured time was exploring, not pickups.
- By the end every collecting arm held all its items (MULTI 3, TWO_POSE 4 per lap). So all arms do collect every
  drop eventually (Measured).
- **Corrected end:** blocks gone and no gold item entity in the arena, capped at 300 ticks. Re-run to the same log
  name, `build/v21-mine-drain.log`.

## 9. Results

### 9.1 Builder final A/B (`build/v21-place-ab.log`, as pre-registered §8.1, no repeats) — Measured

128/128 laps DONE. No FAILED, TIMEOUT, DIED or FELL. 0 stray blocks in every arm, and 61 blocks held at the end
in every lap. Analysis: `python build/cadenza-v21-ab.py place build/v21-place-ab.log`.

Median lap ticks (n = 8 each). `stall` is the median in-goal idle ticks with no calculation (the §4 defect).

| Scenario | A stock | B v2 pre-fix | C v2.1 | D v1+fix | stall A/B/C/D |
|---|---|---|---|---|---|
| PLACE_ROW | 52 | 55 | **46** | 45.5 | 0 / 8 / 0 / 0 |
| PLACE_ROW_BACK | 50.5 | 55 | **48.5** | 47.5 | 0 / 2.5 / 0 / 0 |
| PLACE_SPREAD | 51 | 50 | **50** | 50 | 0 / 1 / 1 / 1 |
| PLACE_TRENCH | 52 | 63 | **54** | 54 | 0 / 8 / 0 / 0 |

Paired ratios: median, bootstrap 95% CI, wins/n. All pairs are 8v8 DONE, so the censored ratio is identical.

| Scenario | C/A | C/B (fix alone) | D/A | B/A (v2 regression, reproduced) |
|---|---|---|---|---|
| PLACE_ROW | 0.902 [0.83, 0.96] 7/8 | 0.800 [0.75, 0.89] 7/8 | 0.902 [0.85, 0.94] 7/8 | 1.046 [1.02, 1.15] 0/8 |
| PLACE_ROW_BACK | 0.979 [0.91, 1.00] 6/8 | 0.871 [0.85, 0.91] 7/8 | 0.941 [0.89, 0.98] 7/8 | 1.088 [1.04, 1.10] 0/8 |
| PLACE_SPREAD | 0.980 [0.98, 0.98] 8/8 | 1.000 0/8 (identical) | 0.980 8/8 | 0.980 8/8 |
| PLACE_TRENCH | 1.038 [0.90, 1.04] 2/8 | 0.857 [0.84, 0.86] 8/8 | 1.019 [0.90, 1.04] 2/8 | 1.212 [1.05, 1.21] 0/8 |

Family verdicts (v2 rule):
- C vs A: NEUTRAL (32 vs 32 completions; no scenario ≤ 0.9 or ≥ 1.1; worst 1.038).
- C vs B: BETTER (≤ 0.9 on 3/4, worst 1.000).
- D vs A: NEUTRAL (worst 1.019).
- B vs A: NEUTRAL, but ≥ 1.1 on PLACE_TRENCH (1.212). This confirms v2's regression is real and reproducible.

**Builder class (§8.1): B, regression removed.**
- PLACE_ROW C/A is 0.902 and PLACE_ROW_BACK C/A is 0.979, both ≤ 1.05.
- No scenario is ≥ 1.1. Completions are 32 = 32, there are no non-DONE laps, and stray blocks are 0 = 0.
- It is not A: C is not ≤ 0.9 on half the scenarios. PLACE_ROW, at 0.902, misses the cut by 0.002.

Notes:
- **The fix accounts for the whole v2 → v2.1 difference.** B and C differ only in the memo invalidation; the
  `orderedExtras` switch is a no-op for Builder, which has no extras. B's stall ticks (8 on PLACE_ROW and
  PLACE_TRENCH) are 0 in C. The C/B gain (0.80–0.87) matches the stall removed plus the follow-on re-plan it
  caused (B goalChanges 3–4 vs 2).
- **PLACE_TRENCH residual, C/A 1.038.** Stock is bimodal (49/52 ×6/60 ×2) and C is flat at 53–54. C wins the 2
  laps where stock takes 60 and loses about 2 ticks on the rest. This is below the ≥ 1.1 threshold and is not
  further diagnosed (§10 Unknowns).
- **Turn model.** C (turn-aware) vs D (v1 + fix) are within 1 tick on every scenario. The turn model neither
  helps nor hurts Builder once the stale memo is gone.
- **Overhead.** cadenzaMs median is 3 ms per lap in C/D (5 on TRENCH), vs 2 ms in B. The likely reason is that
  B reuses stale sets instead of recomputing them; this is Unknown, not measured per call. Calcs and cancels are equal to or below stock.

### 9.2 Mining validation (`build/v21-mine-ab.log`, as pre-registered §8.2, no repeats) — Measured

128/128 laps DONE. 0 give-ups (`failed`), 0 poseUnusable, 0 cancels in every arm. Analysis:
`python build/cadenza-v21-ab.py mine build/v21-mine-ab.log`.

Median lap ticks (n = 8). Items = gold held at the lap end, summed over the 8 laps; ground = item entities left.

| Scenario | A stock | B v2 pre-fix | C v2.1 | D v2.1 scan off | items A/B/C/D | ground A/B/C/D |
|---|---|---|---|---|---|---|
| EXPOSED | 48 | 35 | 35 | 35 | 0/0/0/0 | 0/0/0/0 |
| OCCLUDED | 67 | 55 | 55 | 55 | 0/0/0/0 | 0/0/0/0 |
| MULTI | 130 (one 170) | 101 (99–111) | **99 (98–101)** | 82 | 14/14/15/0 | 2/2/1/16 |
| TWO_POSE | 168.5 | 155.5 | 152.5 | 130.5 | **24**/16/17/0 | 0/8/7/18 |

Paired ratios (median [95% CI] wins/n):

| Scenario | C/B (fixes) | C/A | B/A |
|---|---|---|---|
| EXPOSED | 1.000 (identical) | 0.729, 8/8 | 0.729 |
| OCCLUDED | 1.000 (identical) | 0.821, 8/8 | 0.821 |
| MULTI | 0.985 [0.97, 1.00] 6/8 | 0.759 [0.75, 0.77] 8/8 | 0.771 |
| TWO_POSE | 0.974 [0.93, 1.01] 5/8 | 0.893 [0.88, 0.93] 8/8, **not comparable** (items 17 vs 24) | 0.904, **not comparable** (16 vs 24) |

Family verdicts:
- C vs B: NEUTRAL (32 vs 32 completions; nothing ≤ 0.9 or ≥ 1.1; worst 1.000; items 32 vs 30).
- C vs A and B vs A: **NOT COMPARABLE (skips pickups)**. Both would be BETTER on ticks (C ≤ 0.9 on 4/4), but they end
  TWO_POSE laps with fewer items (32 and 30 vs 38).

**Mining-goal class (§8.2): C, none.**
- C/B MULTI is 0.985, above the 0.95 needed.
- MULTI laps ≥ 125 ticks: C 0, B 0. v2's long mode did not occur in this run's non-batch B, so there was nothing
  to remove.
- Completions and items are ≥ B's (32 = 32; 32 ≥ 30). Give-ups 0 = 0. TWO_POSE is 8/8 DONE in every arm.
- So it is not D.

**The pre-registered "Cadenza advantage kept" check is FALSE as stated (Measured).** C is not WORSE than A, but its
items (32) are fewer than A's (38). The whole deficit is TWO_POSE, and it predates v2.1: B, which is v2's
mining config, has it too (16 vs 24).
- On TWO_POSE, stock walks into the last pair (ending at z ≈ 16.8) and picks up the third drop before the
  lap is cut.
- Cadenza breaks that pair from z ≈ 14.7 and ends with 2/3 in 15/16 laps.
- On MULTI, items are equal (15 vs 14), so Cadenza's 0.76 is comparable and stands.
- §8.3 (drained laps) measures whether TWO_POSE's speed-up survives once the miner finishes collecting.

Other secondaries:
- Order-only goal changes: A 2 (MULTI) and 3 (TWO_POSE) per lap; B 0 and 1.5; C 0 and 0. The C `reorders` (equal
  goal, extras in another order: 1.5/lap on TWO_POSE) are exactly the ticks that pre-fix code treated as a goal
  change.
- MULTI goalChanges: C 6.5 vs B 5.5. These are set changes (items appearing and disappearing), not reorders.
- None of these caused a cancel in any arm: the miner is REVALIDATE (§2).
- cadenzaMs median per lap: MULTI 5 (C) vs 6.5 (B); TWO_POSE 5.5 vs 6.5; ≤ 1 elsewhere. Calcs equal.

### 9.3 Drained laps (post-hoc diagnostic §8.3, `build/v21-mine-drain.log`) — Measured, not pre-registered

64/64 laps DONE. Every collecting arm (A, B, C) ended every lap with **0 gold on the ground**, and none hit the
300-tick cap. Arm D (scan off) cannot collect by design: it hit the cap and is shown only as a control.

| Scenario | A stock | B v2 pre-fix | C v2.1 | items A/B/C | C/A (all items) | C/B |
|---|---|---|---|---|---|---|
| MULTI | 130.5 (two laps 173, 184) | 102 | **99.5** (two laps 129, 131) | 16/16/18 | 0.765 [0.57, 0.90] 7/8 | 0.980 [0.96, 1.15] 5/8 |
| TWO_POSE | 172 | 166 | **163.5** | 24/32/32 | 0.951 [0.92, 1.03] 6/8 | 0.952 [0.87, 1.10] 5/8 |

- **Cadenza's mining advantage survives once every pickup is counted.** C vs A: BETTER by the v2 rule (items 50 vs
  40; worst 0.951). On TWO_POSE it shrinks from 0.893 (§9.2, not comparable) to 0.951 (comparable). About 11 ticks
  of the undrained gap was the third pickup that the cut skipped.
- **When the blocks finish** (`blocksDone`, the ticks to the last break): TWO_POSE C 146–171 vs A 166–174, MULTI C
  98–101 vs A 124–184. C's two 129/131 MULTI laps are 30-tick pickup legs after the blocks (blocksDone 99/98): the
  bounced-drop shape of §7.4. B had none in this run. It had them in v2. That is sampling, not a v2.1 change
  (n = 8, CI on C/B spans 1).
- Items held at the end: A holds 3/lap on TWO_POSE, B and C 4. Ground was 0 in all three, so A's last drop was
  removed from the world but not yet counted in the inventory when the lap ended. **Unknown** (one-tick pickup
  sync, not checked).
- **C vs B: NEUTRAL** (0.980, 0.952; items 18 vs 16, 32 vs 32). The v2.1 mining-goal fix neither helps nor
  hurts lap time here, consistent with §9.2.

## 10. Classification, defaults, conclusions

### 10.1 Classes

| Change | Class | Basis |
|---|---|---|
| **Builder** (v2.1 memo invalidation; arm C vs stock A) | **B, regression removed** | §9.1: PLACE_ROW 0.902, PLACE_ROW_BACK 0.979, worst 1.038; 128/128 DONE; 0 stray; C/B 0.80–0.87 |
| **Mining goal** (order-insensitive extras; C vs B) | **C, none** (no measurable lap-time effect; a correctness fix) | §9.2: C/B NEUTRAL, worst 1.000; items 32 ≥ 30; 0 give-ups; TWO_POSE 8/8 |

- **Cadenza's mining advantage over stock:** kept. It is comparable only with all pickups counted (§9.3: C vs A
  BETTER, 0.765 / 0.951). The pre-registered undrained check was FALSE on items. That was a property of the lap cut,
  not of the task, and v2's config (B) has it identically.
- **v2's PLACE_ROW, PLACE_ROW_BACK and PLACE_TRENCH regression is reproduced** (§9.1 B/A 1.046 / 1.088 / 1.212,
  0/8 wins each). The memo invalidation removes it.
- **The turn model on Builder is neutral** once the memo is fixed (C vs D within 1 tick on every scenario).

### 10.2 Root causes (summary)

- **Builder (Verified + Measured, §4):** `CadenzaCache` kept a target's candidate set for up to 1000 ms while only
  that target's own block was unchanged. After the bot placed block 12, the sets for 13 and 14 were stale (12 now
  supports or occludes them). The goal said "in goal" at a cell from which nothing was placeable, so the bot stood
  idle with no path calculation (stall 2.5–8 ticks/lap median in v2; up to 17).
- **Mining (Verified, §7):** `CadenzaGoal.equals`/`hashCode` compared `extras`, an unordered union, as an ordered
  array. Its order is feet distance, which changes as the bot walks. So the same goal compared unequal. This was
  harmless on the miner (REVALIDATE: 0 cancels in every run), but wrong for FORCE_REVALIDATE users (Builder,
  `legitMine`) and for the planner's equal-goal memory.
- **MULTI's long routes:** not goal instability. They are pickup legs to drops that bounced off the broken block
  (§7.2, §7.4, §9.3). They are required by the task (scan on by default), so they are kept.

### 10.3 Exact changes (vs the pre-v2.1 snapshot; nothing else touched)

| File | Change |
|---|---|
| `src/main/java/baritone/pathing/cadenza/CadenzaCache.java` | `Key` holds x, y, z (was a packed long, same identity); `NEAR_MARGIN = 2.0`; `invalidateNear(BlockPos)` (drops entries within reach + 2); `size()` (tests) |
| `src/main/java/baritone/pathing/cadenza/CadenzaSession.java` | `confirm()` calls `cache.invalidateNear(current)`; `cache` package-private; harness-only `static boolean invalidateOnConfirm = true` |
| `src/main/java/baritone/pathing/cadenza/CadenzaGoal.java` | `extrasSet` (a HashSet); `equals`/`hashCode` on it (consistent); `extras()` accessor; harness-only `static boolean orderedExtras = false` |
| `src/main/java/baritone/pathing/cadenza/CadenzaLiveTest.java` (harness) | `cats`, `goalChanges`, `repos`, `calcTicks`, `orderOnly`, `setChanges`, `reorders`, `gold`, `pickups`, `groundItems`, `scan`; arm sets `v21mine` (+ `.batch`, `.prefix`) and `v21place`; post-hoc `drain` |
| `src/test/java/baritone/pathing/cadenza/CadenzaV21Test.java` | new, 17 tests (§6, §7.3) |
| `build/cadenza-v21-ab.py`, `v21-place-cmp.py`, `v21-mine-cmp.py`, `v21-multi-paths.py` | analysis scripts (build output dir) |

Not changed:
- BuilderProcess, MineProcess, GetToBlockProcess, Settings (all defaults), PathExecutor, PathingBehavior,
  GoalComposite, `droppedItemsScan`, `prune`, the TWO_POSE 2° margin (planner and executor), and every give-up
  timer.
- Maestro, Legato, Presto and PPE. No Fugue.

The two harness switches are package-private statics, at their shipped values unless the harness sets them, and
reset at the end of every run. **Verified.**

### 10.4 Tests

- **Cadenza:** 67/67 (CadenzaTest 29, CadenzaV2Test 21, CadenzaV21Test 17), re-run after the last harness edit
  (`build/v21-cadenza-tests-final.log`).
- **Full suite after both fixes:** 219 suites, 1441 tests, 16 skipped, 0 failures, 0 errors
  (`build/v21-full-suite.log`). **Verified.**

### 10.5 Overhead and reliability

- **cadenzaMs per lap** (median): Builder 3 (5 on TRENCH) vs v2's 2. Mining 5–7 on MULTI/TWO_POSE vs v2's
  6–8.5, and ≤ 1 elsewhere. Calcs equal to v2's, cancels equal or fewer. **Measured.**
- **Live laps:** 128 + 128 pre-registered + 64 drained, all DONE in every collecting arm. 0 FAILED, TIMEOUT, DIED
  or FELL. 0 stray blocks, 0 give-ups, 0 poseUnusable. **Measured.**

### 10.6 Defaults (unchanged)

`cadenza`, `cadenzaBuilder`, `cadenzaTurnAware` and `cadenzaBatch` all stay **off**. Maestro, Legato search and
Presto stay off.
- `cadenzaBuilder` stays off as required. The evidence would support it only as "no worse than stock" (class B, not
  A).
- The turn model has no Builder benefit, so there is no case for `cadenzaTurnAware`.

### 10.7 Unknowns

- **PLACE_TRENCH C/A 1.038.** Stock is bimodal (52 ×6, 60 ×2) and C is flat at 53–54. Why C loses about 2 ticks
  on stock's good laps was not diagnosed (below the 1.1 threshold).
- **Whether the order-insensitive equality has a measurable effect under FORCE_REVALIDATE** (`legitMine`,
  Builder with drops). No live scenario exercises it: Builder has no extras, and legitMine was not run. The fix is
  pinned by tests only (`aReorderAloneWouldNotCancelAPartialPath`).
- **A holding 3 vs 4 at the end of a drained TWO_POSE lap with 0 on the ground** (inventory sync, one tick).
- **Builder cadenzaMs C/D 3 vs B 2.** Assumed to be the recomputes the stale memo skipped. Not measured per call.
- **The memo's hit rate after the fix** was not measured (only cadenzaMs).

### 10.8 Next project (suggested, not started)

1. **Item-aware mining laps as the standard.** Make the drained end (§8.3, blocks gone and no gold on the ground)
   the default MINE criterion of the harness, so no future A/B is cut before the task's own pickups.
2. **Bounced-drop pickup legs** (§7.4, §9.3; C's 30-tick legs). Measure whether a cell that both mines the last ore
   and covers its likely drop cell is worth it. That would be a terminal-cost question inside Cadenza, not entity
   tracking.
3. **PLACE_TRENCH residual** (§10.7) with the `cats` instrumentation, before any Builder default is reconsidered.

Status: **complete**, uncommitted, for review.
