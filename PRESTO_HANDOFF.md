# Presto handoff

Living handoff for Presto (predictive movement intelligence). Update after every milestone.

## Repository state

- Worktree `C:\Users\redfa\Documents\MinecraftDev\Ostinato-ppe`, branch `feat/predictive-physics`, HEAD `1aed5cc2` (= origin/main at audit).
- PPE, Grace Note, Threat, Scherzo and Bombard are **uncommitted** in this worktree (not on GitHub). Presto builds on them. Nothing committed or pushed (owner instruction for this task).
- The main `Ostinato` checkout is on an unrelated 1.16.1 swarm branch with its own uncommitted edit; untouched.
- Target: Minecraft 26.3 / Java 25 (main). Older versions live on separate branches; Presto is not ported.

## Stage 0 decision record

Evidence labels: Verified (source/executed), Reported, Contradicted, Unknown, Measured.

1. **Existing capabilities to reuse (Verified, source).**
   - Default live driver is `KinematicController` (`kinematicTravel` default true; `TravelStack` auto = kinematic → physics → sim → Baritone). It already does predictive jump timing (`DELAYS` ground-tick jump candidates on gap stretches, `JUMP_GAIN` hysteresis against needless hops), momentum scoring (speed credit, `BUMP` sprint-loss penalty), landing checks (`landsSquare`, `edgeHolds`, `settles`, `hardLanding` > 3.01 blocks), a margin test for long jumps (`holdsWithMargin`), air strafe correction, terrain lookahead of 12 moves (`buildLine`: long-jump detection, place/break brake, narrow landings), and hazard checks. Its simulator is `PlayerSim` (1.16-era formulas plus effects/attributes read by `ClientWorld.readPlayer`; declines when gravity is not plain).
   - `SimTrace` measures PlayerSim one-step and open-loop error, but only as a diagnostic (`kinematicTrace`) in MovementJump/ChainJump/ClimbJump. Nothing gates behaviour on prediction error.
   - PPE (`IPredictivePhysics`): 26.3-faithful `LivingSim` with held input, inline `predict`, first-landing report (tick, position, fall distance, damage, lethal, fluid), confidence and assumption flags, snapshot staleness.
   - Grace Note: `PathingBehavior.requestEmergencyHold` returns from `tickPath` before `PathExecutor.onTick`, so no driver (or Presto inside one) runs during a hold.
   - Scherzo/combat: `CombatMovement` uses its own `KinematicController.jumpHelps`, not `drive()`.
2. **Missing predictive capabilities.** (a) Runtime reliability monitoring with fallback. (b) A check of PlayerSim takeoffs against the version-correct (26.3) PPE model. (c) Observed-vs-predicted landing audit. (d) Presto telemetry/config.
3. **Real execution integration point.** `KinematicController.drive()` only: observation after `readPlayer`, gating in plan selection, PPE check just before `Input.JUMP` is pressed, prediction recorded at the main key-press exit. No new input writer: keys still go through the controller's own `InputOverrideHandler` calls.
4. **Physics support.** PPE trajectory + landing (26.3). PlayerSim one-step for self-consistency. Measurements avoid yaw (the real camera lags the commanded yaw): vertical error, ground-contact mismatch, horizontal speed magnitude.
5. **Ownership/cancellation.** Presto never presses keys of its own; it only removes options (a jump, a long-jump stretch) from the existing controller, which then falls back to its walk plan or to Baritone (-1). Context (world, player entity, path object, consecutive driven ticks) invalidates pending predictions. Grace Note holding or mid-episode → Presto stands down.
6. **Performance risks.** PPE inline calls on the client thread (terrain capture). Bounded to one call per gap-jump takeoff tick, SCREENING fidelity, 5 ms cooperative deadline, horizon ≤ 40. Reliability bookkeeping is O(1) per driven tick with no allocation.
7. **Order.** Reliability monitor → gating → PPE takeoff check → landing audit → telemetry/settings → tests → live A/B harness.
8. **Testing.** Unit tests (pure Java) for the monitor and verdict logic; a cross-model test of PlayerSim against PPE on gap/ceiling geometry; compile + targeted regression suites; live A/B harness in a fresh disposable world (baseline vs Presto, interleaved laps).

## Status

### Milestones 1-4: implementation (done, compiles)

Files:
- `src/main/java/baritone/pathing/kinematic/Presto.java` (new): reliability monitor, takeoff verdicts, landing audit, telemetry. Pure Java.
- `src/main/java/baritone/pathing/kinematic/KinematicController.java`: Presto hooks in `drive()` and rollouts (see below).
- `src/api/java/baritone/api/Settings.java`: `presto` (false), `prestoPhysicsCheck` (true), `prestoDebug` (false).
- `src/launch/resources/assets/baritone/ostinato/setting-descriptions.json` (regenerated), `src/main/java/baritone/gui/model/SettingCategorizer.java` (MOVEMENT).
- `src/main/java/baritone/pathing/kinematic/PrestoLiveTest.java` (new) + `harness/TestHarness.java` install line.
- Tests: `src/test/java/baritone/pathing/kinematic/PrestoTest.java`, `src/test/java/baritone/physics/PrestoCrossModelTest.java`.

Integration in `KinematicController.drive()` (the only driver touched; nothing outside it presses keys):
1. After `readPlayer`: if `presto` on and Grace Note is not holding or mid-episode → `begin/observe`; else `drop` + `disarm`.
2. Untrusted + long-jump stretch + on ground + Baritone's parkour can take every jump of it (`baritoneJumps`: ≤3 gap, off a full block, not soul sand) → return -1 (Baritone's `MovementParkour`).
3. Untrusted on open ground: a hop chosen only for speed (`jumpWorthIt` gain) is replaced by the walk plan (`hopsHeld`). Needed hops are kept.
4. Gap takeoff tick with `prestoPhysicsCheck`: PPE TRAJECTORY of the captured player, W+sprint+jump held toward PlayerSim's landing, horizon ≤40, SCREENING, 5 ms deadline. Veto (NO_LANDING/DAMAGE/LEVEL) → defer to the best later-takeoff plan if it holds with margin (≤6 ticks) → else hand to Baritone if it can jump it (cooldown 20) → else keep the controller's own jump (`overridden`, same as Presto off).
5. At the key-press exit: one PlayerSim tick with the modelled camera yaw records the expectation for the next tick. A jump with no engine check (hop, short gap) arms a PlayerSim-only landing audit.
6. Landing audit runs every client tick from `PathingBehavior.onTick` (`KinematicController.audit`), whoever drives: a failed jump usually ends with the path dropped and the driver no longer called (found live, run 1). A landing more than 0.5 below/above both predictions, or still airborne 20 ticks past both, is a LANDING miss and distrusts at once, as a server correction does.

### Milestone 5: tests

- Compile: Verified (`compileJava` green on JDK 21 Gradle, JDK 25 toolchain).
- `PrestoTest` 20/20 pass (trust/hysteresis, correction, escalation 40→80→…→320 and reset, context resets, verdicts, landing audit incl. overdue, path change, sim-only audits).
- `PrestoCrossModelTest` 4/4 pass. **Measured** (unit, hand-built terrain, PPE SCREENING): sprint takeoff at z=0.622, v=0.153 b/t:
  - 2-gap: PlayerSim land tick 12 y 64, PPE tick 12 y 64.0 → AGREE.
  - 3-gap: tick 12 y 64 both → AGREE.
  - 6-gap: both tick 20 into the pit y 58, PPE damage 4 → DAMAGE (veto).
  - 3-gap under a ceiling 2 blocks over the feet: both tick 14 y 58 → DAMAGE.
- Settings catalog / GUI suites pass with the 3 new settings.
- Full suite (`gradlew test`, before the live-driven changes): 194 suites, 1241 tests, 0 failures, 14 skipped. After them: `baritone.pathing.*`, `baritone.physics.*`, `baritone.gui.*`, `baritone.gracenote.*`, `baritone.behavior.*` green.

### Milestone 6: live A/B (real 26.3 client, fresh disposable world, `PrestoLiveTest`)

Course (z=0, 1 wide, over a pit 3 down): 1-gap, 2-gap, 3-gap, step up, 3-gap raised, step down, 3-gap under a ceiling 3 over the feet, 4-gap, goal x=57. `allowParkour` on, no break/place, empty inventory. Laps alternate off/on; jolt laps have the server pull the player back 1.5 blocks at x>7.2.

Run 1 (Measured, 8+8 laps, 2+2 jolt):

| arm | laps | reached | fell | mean ticks (reached) |
|---|---|---|---|---|
| baseline | 8 | 3 | 5 | 254.0 |
| Presto | 8 | 4 | 4 | 259.0 |
| baseline jolt | 2 | 1 | 1 | 231 |
| Presto jolt | 2 | 1 | 1 | 294 |

Presto totals: 40 takeoff checks, 38 AGREE, 2 UNAVAILABLE (one EXPIRED first call, one with the landing past the rollout), 0 vetoes. 36 audited landings, 0 misses, PPE landing tick error 0.00, height error 0.000. One-tick monitor: 1169 observations, 3 SPEED misses (max 0.093 b/t), max vertical error 0.0000. PPE check avg 1.12 ms, max 5.4 ms (first, warm-up). Hooks 7.4 µs/driven tick. Jolt lap 19: CORRECTION → distrusted → 34 ticks of long-jump stretches given to Baritone → re-trusted → goal reached.

Run 2 (Measured, traced, 4+4 laps, 2+2 jolt): baseline 2/4 reached, Presto 3/4; jolt baseline 0/2, Presto 1/2. 22 checks, 21 AGREE, 1 UNAVAILABLE (EXPIRED at the first call: 5 ms deadline vs warm-up); 21 landings, 0 misses; PPE avg 1.39 ms, max 6.5 ms.

**Every fall in both runs is a pre-existing handover defect, with Presto off and on** (traced, run 2):
- 4-gap (x=52.70, the box against the landing block's face, ~4 cm low): the kinematic driver declined the 4-gap (no plan holds with margin from a standing run-up), so Baritone's `MovementParkour` flew a dist-5 jump the planner only allows because the kinematic driver is on (`allowParkourFourGap = kinematicTravel()`). It backs up to x≈47.4, takes off on the last ground tick (x=49.32) and falls short about half the time. When the kinematic driver flies it (momentum hop off the platform, takeoff x=49.43 at 0.205 b/t), PlayerSim and the engine both predicted 53.30/53.31 at tick 12 and it landed.
- x≈7.2 side fall (z=1.30): after Baritone's `MovementParkour` turned around for the 1-gap run-up, the kinematic driver took over with a steady +0.07 b/t sideways velocity while the camera yaw read straight down the line, took off at z=1.01 and came down beside the one-wide block. Cause Unknown (camera vs movement yaw with `freeLook`, or the turn-around momentum); the one-tick monitor saw no miss there (it ignores direction by design).
- Neither is Presto's to fix (pathfinder / Baritone movement / look ownership); see recommendations.

Run 3 (Measured, 8+8 laps, 2+2 jolt, with the every-tick audit, overdue rule and sim-only audits):

| arm | laps | reached | fell | mean ticks (reached) |
|---|---|---|---|---|
| baseline | 8 | 4 | 4 | 260.3 |
| Presto | 8 | 3 | 5 | 276.0 |
| baseline jolt | 2 | 1 | 1 | 243 |
| Presto jolt | 2 | 1 | 1 | 242 |

Presto totals: 878 observations, 5 SPEED misses, 1 LANDING miss (1 distrust, 1 retrust); one-tick horizontal error avg 0.0071, max 0.245 (27 > 0.05, 11 > 0.15; not gated on). 28 checks: 25 AGREE, 2 UNAVAILABLE (EXPIRED warm-up; PARTIAL), 1 DAMAGE. 31 audited landings, 1 miss, 0 overdue; PPE tick error 0.20, PlayerSim 0.16, max PPE height error 3.0 (the lap 3 miss: predicted on the block, came down in the pit). PPE check avg 1.82 ms, max 18.3 ms (over the 5 ms cooperative deadline once). Hooks 6.16 µs/driven tick.

- Lap 3 (Presto): the 2-gap side fall (x 9.26, z 1.33, the x≈7.2 handover defect again). Its jump was checked AGREE: both models were wrong about the real flight. The audit caught it (LANDING miss → distrust). Lap 5 then gave 45 ticks of long-jump stretches to Baritone and reached the goal slower (313 ticks).
- Lap 19 (Presto, jolt): **false veto (Contradicted: the check's assumption)**. `PRESTO check t=4892 pos=20.700,201.000,0.308 v=-0.0000,0.0333 sim=9@21.261,202.000,0.649 ppe=17@22.476,198.000,2.670 OK/EXACT DAMAGE`. A step-up (an ascend inside a long-jump stretch, no gap) from a standstill: the engine held one yaw toward PlayerSim's landing, flew diagonally 2.3 blocks to the side off the one-wide walkway and landed in the pit. PlayerSim steers; the engine does not. The fallback gave the stretch to Baritone, which then fell at the 4-gap (the pre-existing defect above).

Fix (after run 3, Verified compile + unit):
- The check runs only when the straight line from the feet to PlayerSim's landing crosses a gap (`KinematicController.crossesGap`: a column with no collision from one block under the lower feet height up to the higher, sampled every 0.25). Step-ups and step-downs inside a long-jump stretch are not checked (`noGap` counter); their landings are still audited (sim-only).
- An engine landing more than `Presto.OFF_LINE` = 0.75 to the side of the takeoff→landing line is no opinion (UNAVAILABLE, `offLine` counter): the engine answered another jump than the planned one. A landing short along the line still vetoes. `PrestoTest` 22/22 (2 new: `offLineDistance`, `engineLandingOffTheLineIsNoOpinion`, the latter on run 3's numbers).


## Recovery milestone (2026-10-31)

Verified current worktree/branch/HEAD match the repository state above. All existing dirty files and new PPE, Grace Note, Scherzo, Bombard, Threat and Presto files are preserved. No checkout/reset/stash/commit/push. Initial `gradlew test --console=plain` succeeded, but tests were UP-TO-DATE (cached evidence, not a new execution).

Recovered `fabric/run/client/prestotest-result-run4.txt`, omitted above: Reported recorded live results after the gap/off-line fix: baseline 5/8 reached, 258.2 mean ticks; Presto 3/8, 286.3; jolt baseline 1/2, Presto 0/2. 20 checks, 19 AGREE, 1 UNAVAILABLE, 9 noGap; 31 audits, 1 miss; PPE mean 1699.7 us, max 9109.4 us; hooks 6.49 us/tick. These files exist and were inspected; this continuation has not reproduced them. They do NOT establish improved movement.

Source audit found unfinished safety work: the every-tick audit did not validate body/world or emergency/combat ownership; disabling without drive did not clear pending audits; corrections were inferred only during consecutive driven ticks; no explicit runtime version guard. Work next: close lifecycle boundaries, add regression tests, rerun full suite and fresh live A/B. Preserve the existing controller and shared physics.


## Safety milestone (2026-10-31, validation in progress)

Implemented in the existing live path: runtime 26.3 gate; standing-body/step/drag/friction/bounce compatibility guard; audit identity/ownership validation before recording; invalidation on disabled mode, death, world event, emergency hold and explicit path cancellation; packet correction signal consumed on the client thread before landing audit (also catches corrections under one block and while not driven). Pvp-active ticks conservatively disable Presto, preserving Scherzo authority. Existing baseline control is unchanged when these gates decline Presto.

PPE AGREE now stages its landing and arms the audit only at the actual key-commit exit. Reused one float buffer removes the new per-driven-tick yaw-model allocation. New pure-Java tests cover lifecycle, correction, version, unsupported body attributes and malformed results. An opt-in PrestoBench measures bookkeeping only.

Cancellation now deliberately ends the audit; the previous handoff's claim that every audit outlives explicit cancellation is superseded. Ordinary executor/path replacement can still retain diagnostic flight evidence if identity and ownership remain valid. No pending recommendation is retained. Fall/cancel laps in the live harness will therefore not count a subsequent pit touchdown as a Presto audit. This is an intentional safety boundary, not evidence of better landing accuracy.

Verified local 26.3 decompiled LivingEntity: jump-strength/block factor/Jump Boost and sprint impulse 0.2; gravity 0.08; ground speed 0.21600002 / friction cubed; horizontal/vertical air drag 0.91/0.98 with modern modifiers. BodyCapture reads those attributes and PPE LivingSim uses them. PlayerSim does not read drag/friction modifiers, scale/pose/step/bounce; Presto now declines those contexts instead of treating its legacy model as compatible. Shared physics remains untouched.


## Capability classification from recovered code

These classifications concern the feasible implementation in this architecture, not universal Minecraft correctness. Source inspection is **Verified**; deterministic execution, live execution and measurements are recorded separately below.

| Capability | Classification | Evidence / boundary |
| --- | --- | --- |
| Predictive jump preparation/timing | Duplicated by existing Ostinato functionality; Presto check integrated | Verified `KinematicController` DELAYS/rollout/holdsWithMargin; Presto can defer up to six ticks or decline to existing execution. JumpSearch/JumpReach/JumpWorthIt tests cover primitives. |
| Momentum preservation | Duplicated by existing Ostinato functionality | Verified speed/progress scoring, sprint-loss penalty, sprint input and place braking; JumpWorthIt/PlayerSim coverage. No new momentum controller. |
| Movement chaining | Duplicated by existing Ostinato functionality | Verified rollout repeated jump/settles, ChainTemplates tests and executor-owned transitions. No Presto completion override. |
| Predictive landing/correction | Partially implemented | Verified PPE first-landing veto and diagnostic audit; existing air-strafe/arrival control remains in charge. PPE holds one input/yaw, so steering-flight parity and arbitrary corrective maneuvers remain blocked by the model contract. |
| Terrain/obstacle anticipation | Duplicated by existing Ostinato functionality, with integrated gap-check filter | Verified bounded 12-move lookahead, collision rollouts, hazard/ceiling/margin/landing checks. Cross-model gap and ceiling tests. New terrain rescanning/pathfinding is unnecessary. |
| Reliability/fallback | Implemented; regression and live verification below | Verified hysteresis, bounded deferrals, conservative unusable-result gate, correction and lifecycle invalidation. No-landing veto now requires a horizon reaching the simulated landing. |
| Grace Note/Scherzo compatibility | Implemented; deterministic boundary tests, runtime scenarios not fully exercised | Verified emergency hold invalidates immediately, every-tick audit checks ownership, Pvp-active disables Presto. Combat continues to use jumpHelps, not drive. No additional input writer. |
| Version-aware physics | Implemented for 26.3 only | Verified release gate and body/attribute guard; PPE capture/physics inspected against local 26.3 source. Ports and modded/server physics remain unsupported. |
| Low overhead | Computational bounds implemented; measurements below | One inline PPE check per candidate takeoff, <=40 ticks, SCREENING, 5 ms cooperative deadline, <=6 deferrals. O(1) monitoring, reused yaw scratch. Deadline includes cooperative work, not a hard frame-time guarantee. |
| Measurable movement improvements | Missing / not demonstrated | Historical A/B results are inconsistent and often worse. Remains disabled by default. No claim of faster travel, higher completion or lower CPU. |

Live path: `PathingBehavior.onTick` -> ownership/context audit -> `PathingControlManager.preTick` -> `tickPath` -> `PathExecutor.onTick` -> `KinematicController.tick/drive` -> existing InputOverrideHandler. `takeoffCheck` calls the existing PPE capture/predict service inline. Emergency hold stops the executor. Presto never advances a movement itself; existing `syncPosition` and PathExecutor retain progression, including their historical narrow/air handover limitations.

Continuation edits: `Presto.java`, `KinematicController.java`, `PathingBehavior.java`, `PrestoTest.java`, new `PrestoBench.java`, and this handoff. All other recovered files, including settings/catalog, live harness, cross-model tests and shared physics, are preserved.

Known blockers: the legacy controller and Baritone disagree on some four-gap/run-up handovers; side drift can make real input diverge from the held-yaw model. Broadly repairing those behaviors requires a controller/look/parkour investigation with matched runtime traces, not another Presto controller. PPE's cooperative terrain capture can exceed its nominal deadline. No claim is made for unsupported versions, modded movement, arbitrary combat/emergency maneuvers or network conditions.


## Validation milestone (2026-10-31)

**Verified, fresh execution:** `gradlew.bat test --console=plain` after all source edits: BUILD SUCCESSFUL, 195 suites, 1,255 tests, 0 failures/errors, 15 skipped (3m34s). PrestoTest 30/30, cross-model 4/4, JumpWorthIt 3/3, ChainTemplates 2/2, and the 600-case actual-Minecraft collision oracle passed. Full suite includes Grace Note, Scherzo, PPE, settings/catalog and existing movement regressions. The skips are optional/manual/environment-gated tests; this is not exhaustive live coverage. Summary retained under `docs/presto-validation/test-summary.txt`.

**Measured, microbenchmark:** `gradlew.bat test -Postinato_bench --tests baritone.pathing.kinematic.PrestoBench --console=plain` passed. Five warm-up rounds, nine measured rounds of 500,000 bookkeeping ticks: min 14.9 ns/tick, median 15.3, max 22.1. Measures pure audit-context/begin/observe/expect only, excluding Minecraft attribute reads, controller rollouts, world capture, PPE and rendering. Not a disabled-mode or whole-client speedup measurement.

Fresh live validation now running with both harness gates, seed 71006, four normal baseline/Presto pairs and two jolt pairs; fresh disposable world only. Await results before making runtime claims.


Live validation found a harness confound: `next()` left Presto enabled during between-lap setup teleports. With explicit packet correction monitoring, those teleports distrust the model outside the measured lap and contaminate subsequent enabled trials. Fix: disable Presto before resetting/teleporting the player; each lap already reenables its arm after settling. Mid-lap jolt corrections remain enabled. `PrestoLiveTest.java` is now also a continuation-modified file. Preserve the first run as diagnostic evidence; rerun the comparison after this harness-only change. Trust history from actual trials still persists by design; laps are interleaved, not statistically independent replicates.


## Live milestone and final audit fix (2026-10-31)

**Measured, corrected setup harness, before early-miss fix:** 4 normal laps each: baseline 3 reached / 1 fell, reached-only mean 252 ticks; Presto 1 reached / 3 fell, mean 279. Two jolt laps each: baseline 1 reached / 1 fell, mean 253; Presto 2 reached / 0 fell, mean 306. Exactly two Presto corrections, two distrusts and one retrust; 77 declined ticks. Six checks all AGREE; eight audited landings, no recorded misses. PPE check mean 2210.4 us, max 4599.6 us; bookkeeping hooks 8.37 us/driven tick. Evidence: `docs/presto-validation/live-before-early-miss.txt`. This demonstrates live correction/fallback integration, not an overall improvement.

The run reproduced a side fall even after AGREE (lap 7: simulated landing z=0.590, PPE z=1.097, actual fall z=1.36). Cancellation before touchdown intentionally discards the audit, so the old touchdown-only rule missed the observable failure. Final fix: when already descending below both predicted support heights by >0.5 after both touchdown deadlines, record LANDING immediately. This precedes path/harness cancellation; no cancelled prediction is retained. A rising approach to a raised landing is explicitly excluded. Two new regressions cover early failure and the rising case; the overdue test now holds the player above support until its deadline so it independently tests the overdue branch. No threshold was relaxed. Final full suite and another live comparison are running.


## Final status (2026-10-31)

**Verified, fresh execution on the final sources** (classes compiled 03:05:37, after the last source edit at 03:05:08; source hashes in `docs/presto-validation/source-sha256.txt` still match the worktree): full suite BUILD SUCCESSFUL, 195 suites, 1,257 tests, 0 failures/errors, 15 skipped (`docs/presto-validation/final-full-suite.txt`). PrestoTest 32/32 (both early-miss regressions included), cross-model 4/4.

**Measured, final live run on the final code** (03:09–03:12, `docs/presto-validation/live-client.log`, `fabric/run/client/prestotest-result.txt`), 4+4 normal laps, 2+2 jolt:

| arm | laps | reached | fell | mean ticks (reached) |
|---|---|---|---|---|
| baseline | 4 | 2 | 2 | 249.0 |
| Presto | 4 | 3 | 1 | 265.7 |
| baseline jolt | 2 | 2 | 0 | 228.0 |
| Presto jolt | 2 | 1 | 1 | 286.0 |

8 checks: 7 AGREE, 1 UNAVAILABLE, 0 vetoes, 3 noGap, 0 offLine. 2 corrections (the jolts), 2 distrusts, 1 retrust, 70 declined ticks. 12 audited landings, 1 miss. PPE check avg 2.36 ms, max 5.93 ms. Hooks 8.38 µs/driven tick. Every fall (laps 0, 5, 6, 9) is the x≈7.2 side fall (z 1.30–1.54), Presto off and on; lap 9 fell right after its jolt correction.

**Measured, pooled** (runs 1, 3, 4 and final: same course, harness-confound-free, untraced):

| arm | laps | reached | mean ticks (reached) |
|---|---|---|---|
| baseline | 28 | 14 | 256.6 |
| Presto | 28 | 13 | 270.8 |
| baseline jolt | 8 | 5 | 231.8 |
| Presto jolt | 8 | 3 | 274.0 |

Completion: no difference. Reached-lap time: Presto +14 ticks (+5.5%); Mann–Whitney U=122.5, z=1.53, p≈0.13 (normal approximation; laps are interleaved, not independent). Not significant, but consistent in sign across runs 1, 3, 4 and final. Cause Unknown: most Presto laps had no veto, deferral or decline, so a behavioural cause is not established. Not pursued further.

**Conclusion.** Presto is integrated, bounded, tested and safe by construction (removes options only, never writes input). Measured movement improvement: none demonstrated. Keep `presto` default false. The dominant failures on this course are pre-existing (the 4-gap Baritone parkour handover and the x≈7.2 side drift) and outside Presto's ownership.


**Verified final full-suite rerun:** after the early-miss rule and both regressions, `gradlew.bat test --console=plain` passed in 3m29s: 195 suites, 1,257 tests, zero failures/errors, 15 skipped. PrestoTest 32 tests; cross-model 4. Final output/counts and source SHA-256 manifest retained under `docs/presto-validation/`. Final disposable-world run starting from exactly these sources; awaiting results.


## Second recovery: Claude's Presto v4 continuation (2026-10-31)

The user requested continuing again after Claude's newer work. The previous "final/awaiting" notes and source hashes describe the earlier v3 safety pass, not the current tree. Branch/HEAD unchanged; no commit/push/reset/stash. Recovered additional dirty movement fixes in LookBehavior, KinematicController, SimTravel, TakeoffController, MovementHelper, MovementChainJump, MovementParkour and PhysicsTravel; additional untracked PPE capture-deadline/warm-up changes; seven movement regression classes plus PPE budget/warm-up tests. Preserve all of these.

Verified source: silent-look gating now starts from the last sent rotation; plain takeoff search models yaw slew and shifted landing margins; kinematic/physics/ordinary walks wait through reversals; four-gap fallback checks run-up phase; SimTravel recognizes already-landed plans. Shared PPE adds per-section capture deadline checks and synthetic off-thread warm-up; Grace Note waits for that warm-up before its own standing forecast.

Reported existing XML: 204 suites, 1,283 tests, zero failures/errors, 15 skipped, generated after the latest v4 edits. A fresh full-suite baseline is now running. Recovered live v4 logs (trace1, fix1..fix8 and latest) in `fabric/run/client/`; latest is 24 traced laps, NOT our earlier 12-lap run. Latest recorded normal results: baseline 10/10, Presto 9/10; jolt 1/2 each. This is inherited runtime evidence, not a new run of this continuation. Latest v4 takeoff avg 1.136 ms, max 5.521 ms; hooks 6.96 us/driven tick; PPE active-tick max 11.773 ms. No incremental Presto advantage established. `docs/presto-validation/v4/recovered-source-sha256.txt` pins this recovered tree.

Next: audit unfinished handover/ownership boundaries and tests, preserve working v4 fixes, correct demonstrated defects, then validate the resulting tree and update this handoff.


### v4 completion milestone: SimTravel terminal handover

Verified two remaining edge cases in Claude's new landed-plan path. `tick()` only released the previous keys for a negative return, but `landed()` can successfully advance and PathExecutor then returns for that tick; old forward/sprint/jump therefore survived the successful handover. It now releases whenever the prior plan held keys and this tick did not write a new plan step. No new input owner.

The advancement loop used `movements.size()-1`, excluding the path's final destination (paths have n+1 positions for n movements). The bounded four-node scan now uses positions, includes the terminal destination only when grounded, and retains intermediate airborne progression for chaining. Added two regressions for final touchdown, no premature airborne completion, current-node/no-progress and the four-node bound. Existing v4 landing-geometry tests are preserved.

Other v4 mechanics remain unchanged. The capture deadline is cooperative: its nominal deadline plus a section copy is not a total-frame guarantee because region setup, entity collection, allocations and scheduling also cost time. Warm-up reduces cold predictor work but does not eliminate all main-thread capture costs. Keep Presto off by default until incremental benefit is demonstrated.


## Presto v4: movement reliability, budget, A/B (2026-10-31)

Repo vexrypt-rgb/Ostinato, worktree `Ostinato-ppe`, branch `feat/predictive-physics`, HEAD 1aed5cc2. **All v4 work is uncommitted.** Presto stays default off.

**Root causes (Verified from traces + code; each has a regression test):**

| # | Defect | Layer | Evidence | Fix | Test |
|---|---|---|---|---|---|
| 1 | Silent-look human-input gate measured from the parked camera, so the sent yaw stuck 120° short; the post-1-gap walk drifted off the side | LookBehavior | fix1 laps 0/1/3/5/7/9/11 | gate from the last sent rotation (`gateFrom`) | SilentLookGateTest |
| 2 | Kinematic jump mid-reversal; the rollout yaw model is unreliable near 180° | KinematicController | fix2 laps 3, 15 | decline while >90° off the path (`turningAround`) | KinematicTurnTest |
| 3 | SimTravel claimed a 0-tick "already there" with stale W+sprint held | SimTravel | fix2 lap 0 | release keys when stepping aside; on landing, advance the path or drop | SimTravelAtGoalTest |
| 4 | SimTravel replanned toward a passed landing centre and snapped the look round | SimTravel | fix3 lap 3 | `onLanding` + `landed()`. Dropping without advancing (first version) regressed to 0/16 (fix4): a Jump that never saw its own take-off walked back into the gap | SimTravelAtGoalTest |
| 5 | PhysicsTravel pressed its plan while the move yaw (32°/tick) was still turning round, so it walked sideways off the walkway | PhysicsTravel | fix4 laps 2, 5 | turn in place while >45° off (`offPlan`) | PhysicsTurnGateTest |
| 6 | Short-aim rollout ("line right beside the player", ~25° off-axis) skipped the "barely lands" margin check; 4-gap fell 0.05 short | KinematicController | fix5 lap 9 (Presto), fix6 lap 0 (baseline) | `holdsWithMargin(..., shortAim, ...)` | live only (fix7–9: 0 4-gap falls in 72 laps) |
| 7 | ChainJump started its plan facing back along the walk to the run-up, so the jump went into the gap behind | MovementChainJump | fix6 lap 4 | turn on the spot first | live only (fix8–9) |
| 8 | Baritone walk (`moveTowards`) held forward through turn-arounds, z 0.45→1.09 | MovementHelper | fix7 lap 22 t=7303–7308 | W only within 60° of the peeked move yaw (corners unaffected) | WalkGateTest (live: fix9 only) |

Earlier this session (pre-fix1): TakeoffController slew and MovementParkour 4-gap handover (TakeoffSlewTest, ParkourFourGapTest).

**Budget (M3):**
- **Measured (PpeBudgetTest).** The worst accepted request (1,200 ticks, colliding every tick) costs p50 1.3–1.5 ms. A deadline cut overruns by p50 8–9 µs, max 35–253 µs. Cancellation stops at the next 8-tick slice. A takeoff request costs 29–31 ms cold, 71–102 µs warm p50.
- TerrainCapture now honours the request deadline (`captureCuts`; 2 cuts each in fix8 and fix9).
- **Grace Note warm-up.** It was a no-deadline cold forecast on the main thread: 83–92 ms (fix4–5). A deadline alone didn't help (86.7 ms in fix6: cold first-run cost, not horizon). It now waits for the off-thread `PredictorWarmUp` (synthetic terrain, no world access; trajectory + fall shapes): **12.9 / 8.7 / 14.1 ms** (fix7/8/9).
- **PPE telemetry, fix9.** Compute max 578 µs (fix6: 58.6 ms). Main-thread tick p99 11.8 ms (fix6: 70.2 ms). One remaining tick at ~10–12 ms is first-touch capture.
- No 5 ms hard guarantee is claimed: capture plus class loading can exceed it on first use.
- Disabled mode: the hook is one branch, hookNs = 0 on every off lap. On: 6.7–8.8 µs per driven tick.

**A/B (M6, Measured; 16- or 24-lap runs alternating off/on; files in `docs/presto-validation/v4/`, script `abstats.py`):**
- Before the v4 fixes (trace1): baseline 1/6, Presto 2/6.
- **fix5–9 pooled.** Normal laps: baseline 50/52 (96%, 95% CI 87–99%), Presto 49/52 (94%, 84–98%). Jolt laps: 9/12 vs 10/12. Reached-lap ticks 262.3 vs 263.2 (MWU p 0.63). Adjacent pairs n=42: Presto−baseline −0.4 ticks (95% CI −12.2 to +11.3).
- **fix7–9.** Normal laps: 36/36 vs 35/36. Pairs n=30: −7.1 (CI −22 to +8).
- Presto jolt laps are slower: 306 vs 275 ticks, p 0.12 (not significant). The cause is the distrust → "presto untrusted" declines → Baritone parkour.
- Conclusion: the reliability gain is a baseline-movement gain. Presto shows no completion or time benefit and no measurable harm on normal laps.
- PPE agreed with the controller sim on 169/176 checks in fix5–9 (the other 7 UNAVAILABLE, never DISAGREE), with 0 vetoes.

**M4 (blocker, documented).** Presto only removes options: veto, defer, decline, hold a hop. On this course PPE and PlayerSim agree (maxPpeYErr 0.000, landing x within 0.05–0.1). So a takeoff check has nothing to veto that the controller doesn't already avoid. The one Presto-on 4-gap fall (fix5 lap 9) was AGREE at a 0.05-block margin, below the live sim-vs-real horizontal error (max 0.25), and was fixed at the controller layer (#6). The positive optimizations delivered are performance-only: the deadline-bounded capture, the warm-up, and the Grace Note stall fix. A positive movement benefit needs a course where PlayerSim and vanilla diverge (blocks PlayerSim does not model), so that PPE can catch what the sim misses.

**Remaining failures:**
- **Harness jolt** teleports the server player 1.5 back once the client passes x 7.2. Server lag puts it at x 5.1–5.7, on the 1-gap's edge or over the gap. This explains every jolt fall from fix7 on (fix7 l22, fix8 l21, fix9 l20, l23). Harness artifact: move the trigger to x > 9.2 (lands on 7.2–7.7) in the next comparison series.
- **fix9 lap 11 (Presto).** MovementJump 40>44 (KTRACE `r=-1`) sprinted off block 40 without a jump, under the ceiling. Outside the trace windows; cause Unknown. Widen the trace to x 30–44.
- **fix5 lap 12** (baseline jolt) side fall at x 21.6: Unknown, outside the trace windows.

**Next action:**
1. Widen the trace windows and move the jolt trigger.
2. Rerun 3×24 laps on the final code (the walk gate is live-verified by one run only).
3. Investigate the `r=-1` Jump 40>44 walk-off.
4. Then commit; awaiting the owner's authorization.


## Presto v4.1: final validation and movement stabilization (2026-10-31)

Worktree `Ostinato-ppe`, branch `feat/predictive-physics`, HEAD `1aed5cc2`. **Still uncommitted, nothing pushed.** Presto stays default off. Evidence: `docs/presto-validation/v4.1/` (results, `abstats41.py`, `ab-final.txt`, `ab-prefix-runs.txt`, `ppe-telemetry-final.txt`, `source-sha256.txt`, `full-suite.txt`).

**Files changed in v4.1** (on top of the v4 tree, which is preserved):

| File | Change |
|---|---|
| `SimTravel.java` | stale-plan rule (`stale`), turn-in-place before a fresh plan (`PhysicsTravel.offPlan`), `releases` seam, trace fields |
| `PrestoCourse.java`, `PrestoLiveTest.java` | grounded safe jolt at x 34.5 (`safeJoltX`); stress mode kept (`STRESS_JOLT_X` 7.2); trace of sim plans and look-target callers |
| `LookBehavior.java` | `traceCallers`/`traceTargetBy` (harness only, off by default) |
| `PathExecutor.java` | `sim()` getter for the trace |
| tests | `PrestoCourseTest` (rewritten), `SimTravelStalePlanTest`, `SimTravelTurnFirstTest` (new), `SimTravelAtGoalTest` (+`heldKeysAreReleasedOnHandover`) |
| docs | `docs/LEGATO_READINESS.md` (new), this section |

**Harness fix (§3).** The x 9.2 trigger tried first in v4.1 jolted the player in mid-jump: run 1 laps 20-22 fell at x≈9.3 (`aMidJumpPullBackComesDownAtTheGap` replays it). A jolt is now applied only with both server and client player on the ground, onto walkway verified on both the course model (`PrestoCourse.supported`, same floor level) and the live world (`worldSafe`), once per lap, recording the trigger tick, both positions, the destination, arrival and settle. Since then (runs 2, 3, final 1-3) 19 jolts were applied, all grounded and all settled supported. The 20th jolt lap (run 3 lap 20) fell at x 21.56 before reaching the trigger (#10 below). `-Dostinato.prestotest.jolt=stress` keeps the old unsafe trigger as a separate scenario; it was not used in the comparison.

**Root causes found and fixed (Verified from traces; deterministic regression replays the recorded state):**

| # | Failure | Root cause | Fix | Test | Live |
|---|---|---|---|---|---|
| 9 | Jump 40>44 walk-off, x 42.25 (run 2 laps 1, 13, 18; run 1 lap 13; very likely v4 fix9 lap 11) | Stalled at the step up (x 20.7), the kinematic controller returned -1 for one tick; SimTravel solved `J........` for Ascend 20>21 at yaw ≈-104 and pressed one step. The rest stayed stored. At Jump 40>44 (which the controller always declines, "line") SimTravel resumed it: `drift()` only checks from step 2, so the old yaw and no jump; off the walkway at 41.4 | a plan resumes only on the tick right after its last pressed step and only for the destination it was solved for (`SimTravel.stale`) | `SimTravelStalePlanTest` (3) | final 1-3: the step-up claim recurred in 12 laps; every one re-solved fresh at x 40.29-40.39 (`..J` or `.J`, step 0) and reached |
| 10 | Side fall x≈21.6 (§5; run 3 lap 20, baseline, before its jolt; v4 fix5 lap 12) | Stalled at the step-up face, the controller turned to recentre (move yaw 180). SimTravel then solved toward -90 and pressed W + jump at once; the move yaw slews 32°/tick, the jump went sideways (z -0.3) | SimTravel turns in place while the move yaw is >45° off a fresh plan, as PhysicsTravel/ChainJump do | `SimTravelTurnFirstTest.jumpingTheStepWhileTurningGoesOffTheSide` | gate fired 12 ticks in 3 laps of final 2-3, no side fall |
| 11 | Post-jolt side fall x≈33 (run 3 laps 21, 22, Presto) | Jolted back inside Jump 31>36, SimTravel's drift check rested it 10 ticks; Baritone's Jump turned west toward its run-up; SimTravel then pressed W + sprint east with the move yaw 180° off; the walk swung off the side (z 1.5 / -0.48) | same gate | `SimTravelTurnFirstTest` (pressing vs gated) | final 2 laps 20 (baseline) and 21 (Presto): identical situation, 4 gated ticks, z stayed 0.45-0.55, reached |

Not explained (§4): the step-up stall itself occurs in both arms. Its precondition, a late off-centre 3-gap take-off (x≈13.8), occurred in 3/12 baseline and 5/12 Presto laps in run 2; only two Presto laps then chose a jump at x 17.8 and bounced off the step. Presto took no action in those laps (deferred, hopsHeld, declined, overridden all 0). No Presto mechanism is established; treated as chance. The fixes above make the stall recoverable in either arm.

**Regression coverage (§6).** New: stale plan (3), turn-first for SimTravel (4), jolt safety (7), release on handover (1); earlier v4.1: `ShortAimMarginTest` (5: short-aim landing margin, margin shifts), `ChainJumpTurnFirstTest` (4). Existing and not duplicated: rotation limit/turn caps (`SilentLookGateTest`, `TakeoffSlewTest`, `JumpSearchTest`), direction alignment (`PhysicsTurnGateTest`, `KinematicTurnTest`, `WalkGateTest`), path advancement (`SimTravelAtGoalTest`), Presto on/off, cancellation, stale/partial/expired predictions, ownership (`PrestoTest` 32), PPE budget and deadline (`PpeBudgetTest`, `PpeSchedulerTest`). **Gap:** Presto's deferral budget (`MAX_DEFER`, inline in `KinematicController.drive`) has no unit test; it was not refactored during the A/B. Its live counter stayed 0.

**Full suite (§10, Verified, fresh execution on the final source; the sources still match `source-sha256.txt`):** `gradlew.bat test` BUILD SUCCESSFUL, 209 suites, 1,309 tests, 0 failures/errors, 15 skipped (the same environment-gated skips as v4). The v4 count was 204 suites / 1,283 tests; nothing was removed or weakened. The new code allocates nothing per tick beyond what SimTravel already did on its own ticks (a `Rotation` for the look, as PhysicsTravel). No prediction moved onto the main thread. Ownership is unchanged: SimTravel still writes keys only through `InputOverrideHandler` on ticks it claims. PPE and version guards are untouched. Summary: `full-suite.txt`.

**A/B (§7, Measured).** Three 24-lap runs on the final source (hashes in `source-sha256.txt`), same seed 71006, same course, ABBA order, safe jolt, trace on. Pre-fix v4.1 runs are reported separately in `ab-prefix-runs.txt` and not pooled: run 1 (old jolt) baseline 10/10, Presto 9/10; run 2 (stale plan) 10/10 vs 7/10; run 3 (no turn gate) normal 10/10 vs 10/10, jolt 1/2 vs 0/2.

| final runs | baseline | Presto |
|---|---|---|
| normal laps reached | 30/30 (95% CI 89-100%) | 30/30 (89-100%) |
| jolt laps reached | 6/6 | 6/6 |
| normal mean ticks (= end-to-end per completion, no failures) | 260.4 (sd 23.5) | 249.9 (sd 16.2) |
| jolt mean ticks | 282.8 | 293.3 |
| per run, normal, Presto−baseline paired mean | | −16.7, −12.5, −2.1 |
| server corrections | 6 (the jolts) | 6 (the jolts) |
| Presto interventions (defer, fallback, override, declined ticks, held hops) on normal laps | — | all 0 |
| on jolt laps | — | 6 distrusts, 5 retrusts, 51 declined ticks, 1 held hop |
| driver changes (normal) | 472 | 421 |
| PPE check (main thread) | — | 0.75 ms mean; compute p99 655-889 µs, max 889 µs; PPE tick max 8.1-9.6 ms (first-use capture) |
| hook overhead | 0 (off) | 6.8 µs per hooked tick |
| kinematic controller (trace on) | 461 µs/tick | 471 µs/tick |

Paired normal laps (n=30): Presto−baseline −10.4 ticks, t 95% CI −20.5 to −0.3; sign test 18 faster / 8 slower / 4 tied, p 0.076; McNemar 1.0 (no discordant completions). Jolt pairs (n=6): +10.5 (CI −1.8 to +22.8), Presto slower 4/0, p 0.125: the distrust → decline path, as in v4.

**Decision (§8): B, no demonstrated improvement.** Completion is identical (36/36 each). The paired time difference on normal laps just excludes zero under a t-interval, but not under the sign test. No correction was made for the several comparisons, laps are interleaved rather than independent, and the sign has flipped across earlier series (v3 pooled: Presto +14; v4 fix7-9: −7.1, CI −22 to +8). Most decisively, Presto changed no decision on any normal lap (every check AGREE or UNAVAILABLE, zero deferrals, vetoes, declines or held hops), so there is no mechanism by which it could have made those laps faster; the difference is attributed to variation. On jolt laps Presto is consistently a little slower (distrust hands stretches to Baritone). `presto` stays **default false**. All reliability gains in v4/v4.1 are baseline-movement fixes that apply with Presto on or off.

**Remaining limitations.**
- The step-up stall at x 20.7 (controller pressing into the face with `delay=3`, then recentring) still happens in a few laps per run; SimTravel now recovers it safely. Its cause is not traced.
- Presto has nothing to veto on this course (PPE and PlayerSim agree); a benefit, if any, needs terrain PlayerSim doesn't model.
- PPE first-use capture can exceed the nominal 5 ms deadline (tick max 8-10 ms per run).
- The harness has one straight course; no diagonal, combat, network or modded coverage.

**Legato readiness (§9):** `docs/LEGATO_READINESS.md`, covering contracts (including the two new ones from v4.1: open-loop plans expire when another driver ticks, and every driver turns before pressing), integration points (preferably the kinematic controller's polyline), and architecture limits. Nothing implemented.

**Next action:** owner review; commit only on authorization.
