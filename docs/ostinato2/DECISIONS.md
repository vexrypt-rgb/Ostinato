# Ostinato 2.0 — decisions

Phase 00 recorded the baseline. Phase 01 added the input arbiter. D7 supersedes the headless tick-driver assignment. D8 is the Phase 02 comparison on the combined tree. D9 is the Phase 03 lattice experiment. D5's sentence that PPE is absent applied to `feature/secondo-v1` before the merge.

## D1. Baseline commit is `origin/main`, not the dirty 1.16.1 checkout

The workspace folder `Ostinato` is `swarm/s2s-identity-1161` with local edits, including `PathExecutor.java`. Auditing that tree would mix an old port with unrelated dirt. `origin/main` `67e12b96e` is Minecraft 26.3 and its tree matches `2f4176049`, which is where the tests ran.

## D2. `PathExecutor` is not a headless tick harness

It is constructed only from `PathingBehavior` and it drives a live player through `InputOverrideHandler`. Phase 01 must not start by calling `PathExecutor.onTick` off the client, and must not add a fake player inside that class during the first step. That would be an architecture change.

## D3. Headless plan/tick seam (superseded as the Phase 01 task by D7)

The smallest safe seam is the pair that already runs with no client:

- Plan: `PathBench.run`'s call, `AStarPathFinder.calculate0` over `CalculationContext(SyntheticWorld, HeadlessToolSet, ...)`.
- Tick: `PlayerSim.tick` / `tickImpulse` / `tickWater` against a `PlayerSim.World` built from that same synthetic world.

A new test-only driver should sit next to `PathBench`. It consumes the `IPath` and steps `PlayerSim`. It does not go through `createPathfinder`, `calculate`, `TravelStack`, or `Movement.update`.

`TravelStack.Layer` is the live driver plug inside `PathExecutor.tickOnce`. It stays client-bound. Do not add a layer until a headless tick loop has numbers.

`SecondoFeatures.invoke` is a deliberate noop and is not on the pathing hot path. `SecondoTest` confirmed the PPE type is absent and `invoke` does nothing. Do not use that method as the navigation hook.

## D4. Do not treat `docs/pathbench/baseline.md` as this commit's baseline

`stockAStarMatchesGoldenCosts` passed, and the SHORT fingerprint matches `golden.txt`. The 2026-10-17 markdown table does not match those node counts. Later comparisons use `docs/ostinato2/raw/phase00-short-raw.csv` plus `golden.txt`.

## D5. Version split stays visible

**Superseded by D12** (physics-263): `PlayerSim` now models 26.3. The text below is kept as it was decided.

`PlayerSim` is documented as 1.16 movement. The game is 26.3. PPE on `feat/predictive-physics` claims 26.3 server rules and is not in this tree. Phase 00 left `PlayerSim` as the only existing tick body. Phase 01 did not use it as the integration point (D7). Any later sim numbers still have to be labeled as that 1.16 model.

## D6. What Phase 00 did not decide

No change to A*, movement costs, travel-stack order, cancellation, or process priority. Those mismatches are recorded in `BASELINE.md` so a later phase can pick them up with measurements.

## D7. Phase 01 is input ownership, behind a default-off flag

The user replaced the headless tick-driver assignment. D3 still describes the headless plan/tick split. It is not what Phase 01 built.

Phase 01 adds `InputArbiter` and connects only `FallController`. `Settings.inputArbitration` defaults to false, so the shipped path still writes fall keys through `InputOverrideHandler`. Turning the setting on submits one complete fall chord per tick and rejects a stale epoch. Other controllers stay on the old map.

`claim` bumps that owner's epoch. Emergency beats a normal claim, then higher priority, then the smaller owner id. A loser who keeps a resource keeps their epoch. A loser left with nothing goes stale. `requestCancel` does not drop keys. `confirmRelease` and `fail` do, and they record `RELEASED` and `FAILED` separately.

No change to A*, costs, travel-stack order, process priority, or `PathExecutor`.

## D8. Phase 02 compares through contracts and does not rewrite physics

On `combine/main-maze-ppe`, PPE is in the tree. The equivalent pair is still `PlayerSim` (semantics `PlayerSim-1.16-land-tick`): `PhysicsPathfinder.plan` replayed through `PlayerSim.tick`, and a trivial sprint on the same settled sim, same flat world, same goal, same tick budget 200 and node budget 1500.

PPE is called through `Predictor` and labeled `NOT_EQUIVALENT`. Its tick is the 26.3 living model, its floor is y=64 rather than the PlayerSim floor at y=-1, and its input is one chord held for 40 ticks. Stock A* is labeled `NOT_EQUIVALENT` and is not executed: `calculate0` cost is not a PlayerSim tick, and `PathExecutor` is not headless (D2).

No edit to `PlayerSim`, `PhysicsPathfinder`, `PathExecutor`, `PathingBehavior`, `AStarPathFinder`, `InputOverrideHandler`, or `TravelStack`. Robustness margins are null. Phase 03 had not started when D8 was written. D9 records it.

## Assignment that was superseded

The text below was the Phase 00 proposal for Phase 01. It was not implemented.

**Superseded — headless tick driver (test only). Not started.**

Work in a worktree of `origin/main` `67e12b96e` (tree `2f4176049`). Do not touch `MinecraftDev\Ostinato` (`swarm/s2s-identity-1161`).

1. Add a test-only harness beside `PathBench` that, for each stock-A* SHORT query, plans with the same `calculate0` call `PathBench.run` uses, then steps `PlayerSim` one tick at a time along that path.
2. Do not modify `PathExecutor`, `PathingBehavior`, `AStarPathFinder`, `AbstractNodeCostSearch`, `InputOverrideHandler`, `TravelStack`, `Moves`, or `SecondoFeatures`.
3. Do not construct a Minecraft client, do not load a world save, and do not call PPE.
4. Record, per query: plan `ok`, cost, nodes, plan ms (already defined), then simulated outcome (`reached` / `stuck` / `diverged`), simulated ticks, and end position error versus the goal cell. Write that under `docs/ostinato2/raw/` with the command.
5. State in the result that the body is the 1.16 `PlayerSim`, not 26.3.
6. Stop when that table exists for the 20 SHORT queries, or when a concrete blocker (for example the path's movement inputs cannot be replayed without `Movement.update`) is written down. Do not widen into parkour settings, MEDIUM routes, or a live client to force a pass.

## D9. Phase 03 keeps the lattice off the production path and does not continue it

The prototype lives in `baritone.lattice` and plans through `PlayerSimPhysics`. Primitives are walk, turn by 45 degrees, sprint, one-block ascent, and short sprint-jumps. Each one stores preconditions, tick inputs, predicted states, a tick cost, and a robustness margin. Search is bounded beam (width 24) and weighted A* (weight 1.5), with lazy expansion and state deduplication. `Settings.latticeExperiment` defaults to false and is not read by the benchmark or by production pathing.

The comparison is the same PlayerSim world, settled body, goal, radius 0.5, and node budget 1500 as `PhysicsPathfinder`. The lattice also stops at 400 travel ticks. That cap did not bind. Physics expansion counts stay UNKNOWN because `PhysicsPathfinder` does not expose them. Prediction divergence is the fresh-replay position error against the stored primitive states.

Rule, applied to `docs/ostinato2/raw/phase03-summary.txt`: REJECT if divergence is above 0.25, or if the lattice solves under half the dev cases and trails physics by at least 3. PREFER SIMULATED A* if physics solves more dev cases and the lattice uniquely solves none. CONTINUE if the chosen lattice method solves at least as many dev cases as physics, the shared median travel ticks are within 1.15x, and median expansions are within 3x. Otherwise REVISE. A held-out drop of more than 0.25 below the dev rate would also force REVISE.

Result: **REVISE**. Divergence is 0. Dev success is 12/12 for beam, weighted A*, and physics. Beam is the chosen method. Shared median ticks are 18.0 versus 17.5 (ratio 1.029). The expansion clause cannot pass. Held-out ran and did not drop (beam 16/16, physics 14/16). Physics missed `gap-2-far` and `corner-long`. That is not CONTINUE, not PREFER SIMULATED A*, and not REJECT.

No edit to `PlayerSim`, `PhysicsPathfinder`, `PathExecutor`, `PathingBehavior`, `AStarPathFinder`, `InputOverrideHandler`, or `TravelStack`. Phase 04 was not started.

---

The entries below were added by the Claude review on branch `ostinato2/claude-review` (local only, not pushed). Grok's entries above are unchanged. Where the review changes the status of one of them, the change is recorded here, not by editing his text.

## D9 status (review). PROVISIONAL; the rule now reads CONTINUE at planner tier

- D9's REVISE came from a clause that could not be evaluated: physics expansions were `-1`. The review exposed `PhysicsPathfinder.lastExpansions()`, a counter only that does not change the search result (`review-fix(phase-03)`, `b1df1fc41`).
- Rerun with 20 trials (`docs/ostinato2/raw/review/phase03-summary.txt`), applying the same rule:
  - shared median expansions on dev solves: beam 102 vs physics 76.5 (1.33x, under the 3x bound);
  - shared median ticks 18.0 vs 17.5 (1.029x);
  - dev 12/12 each; held-out beam 16/16 vs physics 14/16.
  - Result: **CONTINUE**. **VERIFIED, Tier-S.**
- The decision is **PROVISIONAL** and is not a reason to build on the lattice yet:
  - The success difference is not significant: held-out Fisher two-sided p = 0.4839; dev p = 1.0.
  - The bootstrap 95% CIs of the beam/physics median travel-tick ratio are dev [0.944, 1.286] and held [0.920, 1.241]. Both include 1.0 and both reach past the 1.15 bound.
  - "Divergence 0" replays the same model, so it is self-consistency, not prediction accuracy.
  - Current navigation (stock A*) solves 28/28 of the same worlds at planner tier, with or without parkour, with p99 ≤ 3.0 ms. The lattice shows no planner-tier success advantage over it. Stock travel ticks are an A* cost estimate, NOT_EQUIVALENT to simulated ticks (`raw/review/phase03-stock-summary.txt`).
- **Needed before this is final:** an execution-tier comparison (stock `PathExecutor` vs lattice plans driving the same body) on worlds where stock A* struggles, and Tier-L runs. Both are owner decisions; neither was done here.

## D10. Benchmark version is Minecraft 26.3

**Partly superseded by D12:** 26.3 stays the benchmark version, but `PlayerSim` is no longer a 1.16 model and the predictors' constants were changed to 26.3's. The bullets below are kept as decided.

- The build, the fabric jar, PPE and the live SIMTRACE traces used for the Phase 04 bounds are all 26.3.
- `PlayerSim` is a 1.16 movement model (D5). It is run on 26.3, and the 26.3 live traces measure how well it predicts that client.
- Headless PlayerSim numbers in Phases 02–04 are therefore "1.16 model, judged against a 26.3 client where Tier-L exists".
- Physics constants stay as they are. Version adapters wait for Phase 09.

## D11. Phase 04 canonical physics: one new type, existing types declared, no new snapshot

Evidence rule: a canonical interface needs at least two existing consumers. Nothing is promoted on design grounds alone.

| Concept | Canonical type | Existing consumers | Decision |
|---|---|---|---|
| Player state | `baritone.contract.PlayerState` (Phase 02) | `ComparisonHarness`, `LatticeSearch`, `LatticeBench`, `Trajectory`, `Scenario` | Declared canonical. No change. |
| Physics transition | `baritone.contract.PhysicsTransition` (Phase 02) | Implemented only by `PlayerSimPhysics`. `ComparisonHarness`, `LatticeSearch` and `LatticeBench` use the concrete class, not the interface. | **Not promoted.** The interface has zero consumers. Retyping the consumers would be churn without evidence. |
| World snapshot | `PlayerSim.World` (existing) | `PlayerSim`, `ClientWorld`, `SimTrace`, `PhysicsPathfinder`, `PhysicsTravel`, the lattice worlds, the kinematic movers | Declared the de facto snapshot. `WorldAccess` (Phase 02) holds only a semantics string, so it is not a snapshot. No new type. |
| Prediction uncertainty | **new** `baritone.contract.PredictionTolerance` | `SimTrace` (was the fixed `DIVERGED = 0.05`) and `PhysicsTravel` (was the fixed `DRIFT = 0.1`) | Added behind the `predictionTolerance` setting, default **false**. |

`PredictionTolerance` details:

- **Metric:** one-step Euclidean position error in blocks. That is the real next state against the state predicted from the last real state plus the applied controls (SimTrace's "one step").
- **Modes:** WALK, SPRINT, SPRINT_JUMP, FALL, FLUID, CLIMB. `classify` picks the mode from the pre-tick state and the controls: fluid first, then climbable, then ground (sprint or walk), then air (moving means sprint-jump, idle means fall). Vines use the same climbable flag as ladders.
- **Bounds** are p99 one-step error, yaw lag included, because the consumers see it too:

  | Mode | Bound | Tier | Basis |
  |---|---|---|---|
  | SPRINT | 0.2235 | **L** | 26.3 live, jump/chain ground ticks, n=4008 |
  | SPRINT_JUMP | 0.0983 | **L** | 26.3 live, jump/chain airborne ticks, n=9477 |
  | CLIMB | 0.2374 | **L** (climb-jump only) | 26.3 live climb-jump runs, n=739. Ladder climbing proper was not traced. |
  | WALK | 0.2235 | **S** | no live walk trace; SPRINT bound borrowed |
  | FALL | 0.0983 | **S** | no live fall trace; SPRINT_JUMP bound borrowed |
  | FLUID | 0.05 | **S** | no live fluid trace; old DIVERGED kept |

- **Tier-L source:** `Ostinato-bastion-263/fabric/run/client/simtrace/`, 2026-10-14, 26.3 client. These are historical traces: no client was run in this review. `PlayerSim` was diffed against that commit and is unchanged apart from a documented bit-identical refactor (REVIEW F-H2). **After D12 these bounds describe the old 1.16 model, not the current one.**
- **Tier-S evidence:** `CrossModelModesTest`. PlayerSim and PPE agree within 7e-6 blocks over 20–30 ticks in every mode (`raw/review/phase04-crossmodel-summary.txt`). That bounds disagreement between the models. It is not error against the game, and the near-zero agreement suggests the two share a derivation, so it is weak independent evidence.
- **Flag off:** with the setting off, both consumers run their old check. Tests compare the off path against the old rule: `PredictionToleranceTest.flagOffSimTraceIsTheOldFixedThreshold` (every row field, `firstDiverged` and the summary string) and `flagOffDriftIsTheOldSummedAxisCheck` (100,000 random pairs). With the flag off, mode classification is skipped entirely.
- **Flag on is a behavior change:**
  - `PhysicsTravel` replans on SPRINT ground ticks only past 0.2235 Euclidean, where it used to replan past 0.1 summed over the axes.
  - Whether that helps or hurts is a Tier-L question (laps on a course), and it was not run. The flag must stay off until it is.
- **Not done:** per-mode bounds for PPE as a predictor (PPE has live validation only for TNT), and version-specific constants (Phase 09).

## D12. Physics predictors follow 26.3 (physics-263)

Owner request, after Phase 04: "make sure any physics predictor like player sim uses 26.3 physics and not 1.16.1 physics". This replaces D5 and the "constants stay as they are" bullet of D10. 26.3 is still the benchmark version. Every rule below was read from the decompiled 26.3 client jar (`LocalPlayer`, `LivingEntity`, `Entity`, `Player`, `ItemEntity`, `Mth`, block classes), not from PPE or Grok's notes.

**`PlayerSim` (Java) and `native/src/sim.rs` (Rust mirror), rewritten to 26.3:**

- Input: KeyboardInput's normalised key vector, then `modifyInput` (0.98, item-use 0.2, sneak 0.3, square-movement correction), in floats. A plain diagonal is now full length (1.16 held 0.98 on each axis).
- Speed: the speed cutoff is horizontal as a whole (`vx²+vz² < 9e-6`), vertical on its own (0.003). The sprint speed is `(float)(0.1f · (1 + (double)0.3f))` = 0.13000001f, as the attribute system works it out. Air control is 0.025999999f / 0.02f.
- Sprint: kept through a wall met within 8° of the input (`minorHorizontalCollision`). 1.16 stopped on any wall.
- Friction comes from the supporting block (`mainSupportingBlockPos`, found with vanilla's nearest-centre rule and its retry shifted back by the move), not from the block under the centre. All drags are 26.3's floats (`0.91f`, `0.98f`, `computeModifiedFriction`).
- Trig uses `Mth`'s 65536-entry sine table, built with StrictMath. `PlayerSim263Test.sineTableIsMths` compares all 65536 entries of sin and cos with vanilla `Mth`: no entry differs.
- Jump: `max(jumpPower, vy)` with float power, and a sprint kick through the table.
- `Entity.move` gives the position applied only past `1e-7`, `Mth.equal` collision flags, the step-up candidate heights (float, deduplicated, sorted; the first that goes further wins), and `restituteMovementAfterCollisions`.
- Bounce: slime 1.0 and beds 0.75 through a new `World.bounce`, with the gravity share taken off the landing tick. A landing at or under 0.08 does not bounce, so standing on slime is still.
- After the move: the slime `stepOn` and the honey slide, swept per axis leg. Water order is 26.3's (sprint and swim rules, sneak sink 0.04f, fluid-falling adjustment, hop-out with `vy = 0.3f`).
- Native marshalling: the state grows to 21 doubles (minor collision, supported, onGroundNoBlocks, supporting cell). Snapshots carry 4 factors per cell, with bounce added.

**Other predictors:**

| Predictor | Change |
|---|---|
| PPE `LivingSim` | The sprint toggle used `speed *= 1.3f` in floats, which gives 0.13f. Vanilla gives 0.13000001f. It now uses the attribute arithmetic both ways. This was the source of the 1.85-ulp PlayerSim/PPE gap. |
| `InputDecoder` (spectate) | Client key impulses go through `PlayerSim.keyInput(f, s, using, sneaking)`. The old `sqrt(0.5) · 0.98` diagonal matched neither 1.16 nor 26.3. The restart velocity uses the float drags, and a grounded player keeps vanilla's standing `vy = -0.0784`, not 0. Bot (server-side) keys are unchanged. |
| `ItemThrow` | The throw velocity uses vanilla's float math and the Mth table; the drags are `0.98f` and `0.98f · 0.6f`. The random spread is still left out, as before. |
| Inline fall predictors: `AirDrift`, `MovementFall`, `MovementDescend`, `MovementParkour`, `ExtensionController`, `CombatMace`, `CombatShield`, `OpponentModel` | `(vy - 0.08) · 0.98` became `· 0.98f`, and `0.91` became `0.91f` where present. |
| `LadderClutch` | Already used `0.98f`. Only its comment changed ("1.21.4" → 26.3). |
| `contract.PlayerSimPhysics.SEMANTICS` | `PlayerSim-26.3-land-tick`. The contract golden `flat-sprint.txt` was updated to match; its numbers did not change. |

**Left as they are, on purpose:**

- `TickSim`, `SurfaceSpeed`, and the `MovementJump` coast and stop estimates are closed-form cost estimators.
- Their structure already matches 26.3: 0.546 ground drag, 0.91 air, `(vy-0.08)·0.98`, 0.02/0.026 air control, `0.21600002/f³`. They differ only by float rounding in the 7th digit.
- Changing them would only shift planner costs by about 1e-7 and perturb A* tie-breaks. That is not a physics change.

**Accepted approximations** (documented in the code):

- Fine voxel-grid heights are not separate step candidates; it was argued that they don't change outcomes.
- The fence/wall `onPos` special case is ignored.
- Fluid height, jumping from the ground in water, the swim pose's box, sneaking edges and `SUPPRESSES_BOUNCE` are not modelled, except in the client-backed worlds, which read `SUPPRESSES_BOUNCE`.
- Worlds that can't list a single cell (`cellBoxes`) have no supporting block, so friction falls back to under the centre. `SnapshotWorld` (spectate) has no bed bounce.

**Test expectation changes (intended physics changes, not regressions):**

- `PlayerSimTest`: three expectations moved to the float drags (`0.2·0.91f`, `0.2·0.4f·0.91f`, `(v-0.08)·0.98f`) at 1e-12 tolerance.
- `NativeParityTest`: its random world gained bed bounce cells, without changing the random sequence.
- `CrossModelModesTest` now asserts agreement with PPE within 0.5 float ulp of PPE's float output, instead of only writing the table.

**Evidence:**

- `NativeParityTest` with `-Postinato_native_require`: 24/24, full state compared bitwise, beds included (Tier U).
- `PlayerSim263Test`, 8 tests: key pipeline, the sine table against vanilla `Mth`, sprint kept along a shallow wall and lost sideways, supporting-block friction on an ice edge, slime standing, bed = 0.75 of slime, step-up headroom (Tier U).
- `CrossModelModesTest`: see BENCHMARKS (Tier S).

**What this does not show:**

- No live client was run, so there is no Tier-L evidence for the new model.
- The D11 Tier-L tolerance bounds (SPRINT 0.2235, SPRINT_JUMP 0.0983, CLIMB 0.2374) were measured with the 1.16 `PlayerSim`, and the historical trace keeps only residuals, so it cannot be replayed through the new model. For the current model those bounds are UNKNOWN until a 26.3 client re-run (blocker B1 grows).
- `predictionTolerance` stays default-off, so no production default changed.
- TenorClef (1.16.1) is a separate code line. This branch builds only 26.3, but porting this `PlayerSim` to a 1.16.1 bot would now be wrong for that version.

## D13. Phase 05: simulation-derived A* is kept behind `simEdgeSearch` (default off); legacy A* stays the planner

The task: improve A* with simulated walking, turning and jumping edges, keep legacy A* behind a flag, and adopt only
on no reliability regression plus a reproducible gain in planning accuracy, route quality or execution efficiency.
The audit (`PHASE05_AUDIT.md`) came first. The gate was pre-registered in BENCHMARKS before any live data.

**What was built** (all new code behind `simEdgeSearch=false`; legacy A* and the executor unchanged):

- `SimEdge` prices TRAVERSE, DIAGONAL and ASCEND links by running `PlayerSim` under a controller that copies the
  executor: 32°/tick yaw cap, sprint rule, MovementAscend's jump rule. Each edge starts from the previous edge's
  predicted end body (position, velocity, yaw, sprint, ground, jump cooldown). Edges it cannot simulate keep legacy
  costs and make the body unknown, and an unknown body is priced as legacy's steady state.
- `SimEdgePathFinder` searches over the state (cell, motion class), with 66 classes per cell (rest, 16 heading
  sectors × walk/sprint × ground/air, unknown) and one representative body per state. Approximate dominance pruning is
  bounded by `MOMENTUM_BOUND`. It falls back to `AStarPathFinder` on an empty result or an exception, and is capped by
  `simEdgeMaxStates`.
- `simEdgeHorizon` (32) limits simulation to edges near the search start. Without it, 20/60 headless queries timed
  out.
- Duration is the edge cost. Drift (summed `PredictionTolerance` bounds), air ticks and food are measured per edge
  but not added to the cost.
- Paths are rebuilt from the same `Movement` classes, so execution is legacy's in every arm.

**Gate result: NO IMPROVEMENT. Legacy behaviour retained.**

| criterion | seed 71006 | seed 71007 |
|---|---|---|
| Reliability (reached, faults, stuck keys, exceptions) | holds: 96/96, none, none, 0 | holds: 96/96, none, none, 0 |
| 1. Lap ticks ≤ 0.97 on ≥ 2 legs, none ≥ 1.03 | **fails**: 1.03–1.09 slower on slalom, ridges, steps; bumps 0.995 | **fails**: ridges 1.07–1.10, slalom 1.03; bumps 0.992 |
| 2. Sim-model prediction error ≥ 25% lower than legacy | **fails**: 0.032 vs 0.020, CI of difference below 0 | **fails**: paired difference −0.005, CI below 0 |
| Route quality (Tier S, sim-model price) | 0.98–1.00 of A*'s, measured only by the sim model itself | — |

**Why it did not help:**

1. **Plan time is execution time here.** The first sim-edge plan waits about 13 ticks against 2–3 for A*, and on these
   78-block legs that alone is a 3–4% loss. With the first plan's wait removed, ridges stay 2.5–7% slower on both
   seeds. Slalom and bumps are about even, and steps is even on one seed and 2–3% faster on the other. Headless, the search is 1.5–3× slower than A* even with the horizon.
2. **The sim does not model the live executor.** With `kinematicTravel` on, as in every arm, much of the lap is
   driven by the kinematic driver, not by `MovementAscend`/`MovementTraverse`. Both models over-predict the jump legs
   (ridges +21% legacy, +23% sim), and the sim is the further off. A search that optimises an executor it doesn't
   model can choose routes that are worse live: more turning on most legs, and slower ridges. This is audit risk R2.
3. **Little to gain on this action set.** Under its own model the sim search finds routes only 0–2% cheaper than A*'s
   (headless and course). Walking, turning and one-block jumps are already priced well by the legacy closed forms
   (legacy first-segment error 2% median).

**Baseline fixes:** none were found. No legacy or executor code changed, so there is nothing to split out.

**Kept:** the code, tests, course and analysis script stay on the branch, default off, as the measured negative
result and as a base if the executor's own controller (the kinematic driver) is ever simulated instead. **Not
done:** adding drift, risk or food to the cost; simulating descends, falls or parkour; reconstruction that hands the
sim's end bodies to the executor. Each is a Phase 06+ owner decision, not part of this phase.

## D14. Phase 04 live validation: Tier-L 26.3 bounds adopted; `predictionTolerance` stays off; gate PROVISIONAL

The task: close the Phase 04 gaps (STATUS B1–B3) with live 26.3 evidence for the D12 model, and assess B4–B6 only for
their effect on the gate and on Phase 05. The criteria were pre-registered in BENCHMARKS ("Phase 04 live validation",
commit `03dca814b`) before any data. Phase 05 code was not touched.

**Added (instrumentation, all off unless its `-D` property is set):**

- `contract.ToleranceTraceTest` (`-Dostinato.tolerancetrace`): a scripted live trace of all six modes, predicted the
  way `PhysicsTravel` predicts.
- `utils.TickClock` (`-Dostinato.tickClock`) and a RETURN inject on `Minecraft.tick` in `MixinMinecraft`: client tick
  time.
- `LegatoLiveTest` arms `tolerance` and `-Dostinato.legatotest.stack`, with per-lap tick quantiles and
  `tolerance-ticks.csv`.
- `PhysicsTravel.plans` and `driftReplans`: counters only, no behaviour change.
- Scripts: `tolerance-trace.py` and `tolerance-ab.py` (pre-registered), and `tolerance-ab-diag.py` (diagnostic only).

**Changed (flag-on only):** `PredictionTolerance.MEASURED` now holds the six dev-session p99s, all Tier L, basis
"live 26.3 trace, D12 model". The D11 values (1.16 model) are gone from the table. `PredictionToleranceTest` keeps
them only as a labelled fixture for the per-mode judging test. `predictionTolerance` is still default **false**.

| mode | D11 bound (1.16 model) | D14 bound (D12 model, live 26.3) | held-out share over |
|---|---|---|---|
| WALK | 0.2235 (S, borrowed) | 0.003547 | 0.82% |
| SPRINT | 0.2235 (L, old model) | 0.004708 | 1.54% |
| SPRINT_JUMP | 0.0983 (L, old model) | 0.000452 | 1.01% |
| FALL | 0.0983 (S, borrowed) | 0.000001 (resolution floor) | 0% |
| FLUID | 0.05 (S, old DIVERGED) | 0.000346 (`tickWater`) | 0.84% |
| CLIMB | 0.2374 (L, climb-jump only) | 0.001103 (ladders and vines) | 1.07% |

**Findings:**

1. **The D12 model is exact one tick ahead, given the yaw the client applied.** With that yaw, p99 is 0 in every
   mode in both sessions. The residual is the applied yaw differing from the commanded yaw (p50 0.56°, p99 1.02°).
   This is Tier-L evidence for D12 that the cross-model test (Tier S) could not give.
2. **`SimTrace` judges water ticks with a land prediction.** It calls `tick()` on every tick (`SimTrace.java:109`).
   On FLUID ticks that gives a p99 of 0.385, against 0.000346 for `tickWater`. With the flag on, its FLUID verdicts
   are therefore wrong. `SimTrace` is diagnostic only (it drives nothing), so this was recorded, not fixed: a fix
   would change trace semantics, which is out of scope.
3. **Tight bounds make the flag-on drift check a pure cost.** The ground and air bounds are 20–200× tighter than
   the old 0.1 summed-axis check. With them, `PhysicsTravel` replans about 17 times a lap instead of about 0.6. Over 192 laps
   (seeds 81011, 81012) this gave:
   - the same reliability (all reached; no faults, stuck keys, deaths or cancels);
   - the same lap ticks (paired ratios 0.962–1.015);
   - a median +26 ms of `PhysicsTravel` time per lap and +146° of turning per lap;
   - tick-time p95 +0.59 ms (CI −0.01 .. +1.22 ms, inconclusive against 0.5 ms).
4. **Coverage limits:**
   - Scripted turns are ≤ 30°/tick, so turns over the 32° aim cap are not sampled. `PhysicsTravel` turns in place for
     those.
   - The traces cover one course (flat, 1-high bumps, a 1- and 3-deep still pool, a ladder wall and a vine wall). They
     do not cover slime, honey, ice, soul sand, flowing water, lava or sneaking.
   - A one-step bound says nothing about multi-tick open-loop drift, which is what `SimEdge` accumulates.

**Decision:**

- Keep `predictionTolerance` default off.
- Keep the Tier-L table: it is the validated description of the current model.
- Do not enable the flag on this evidence. It buys nothing measurable on this course and costs replans.
- A drift check that uses these bounds with a margin (k × bound, or a few ticks of persistence) would be a new design
  needing its own pre-registered A/B. It was not tried: tuning it here would be fitting to this data.

**Gate: PROVISIONAL.** B1 is met (6/6), there is no unacceptable error, and B3 shows no regression, but acceptable
overhead (B2) is not shown. See BENCHMARKS "Phase 04 gate classification".

## D15. Phase 05B: SimEdge's loss is first-plan latency; a horizon of 8 removes most of it but beats nothing; class C, SimEdge stays disabled

The task: diagnose why Phase 05's SimEdge (D13) did not improve performance, then pre-register and run one bounded
experiment, with no redesign of A* and no Phase 06. The diagnosis and the experiment's criteria were written to
BENCHMARKS ("Phase 05B", commit `5b26423b5`) before any experiment data. Evidence is in BENCHMARKS "Phase 05B" and
`docs/ostinato2/raw/phase05b/`.

**Added (diagnostics, all off unless their flag is set):**

- Commit `e071cc7d9`:
  - `-Dostinato.simedge.profile`: per-search `SIMEDGEPROF` and per-plan `PLANPROF` lines, with sim and legacy-move time
    by edge kind, expansions, duplicate sims and allocation.
  - `-Dostinato.legatotest.trace`: per-tick `simedge-trace.csv` and per-path `simedge-paths.csv`, plus a `planUs`
    field on every lap row.
  - Env-gated tests: `SimEdgeProfileTest` (`OSTINATO_SIMEDGE_PROFILE`) and `SimEdgeDivergenceTest`
    (`OSTINATO_SIMEDGE_TRACE`), an offline multi-tick replay of a live trace.
  - Scripts: `scripts/simedge-prof.py`.
- Commit `b78f7d56b`: harness arms `-Dostinato.legatotest.arms=simedge5b` (A legacy, B SimEdge horizon 32, C legacy,
  D SimEdge horizon 8). Lap rows log `simEdge` and `horizon`; `SimEdgeProfileTest` reads `OSTINATO_SIMEDGE_HORIZON`.
- `scripts/simedge5b-ab.py`: the pre-registered analysis and classification.

**Not changed:**
- planner code;
- the physics;
- the controller;
- production defaults (`simEdgeSearch` false, `simEdgeHorizon` 32);
- the benchmark criteria (Phase 05 measure 1 is the M1 rule as written);
- the harness's same-start timeout escalation. It is documented as a confound and measured: at most about 1.5 ticks
  on B, none on D.

**Diagnosis findings:**

1. **The bottleneck is first-plan latency.**
   - SimEdge expands 12–16 motion states per cell inside its 32-block horizon, with about 7 edge sims and about 100
     legacy move evaluations per expansion. Half the time is simulation, a fifth is legacy move evaluation.
   - Only 2% of sims are exact repeats, so caching would not help.
   - On 3 of 4 legs the first plan exceeds the 500 ms primary budget. The lap starts about 12–18 ticks late, which is
     70–100% of the Phase 05 loss.
2. **The controller mismatch is not the bottleneck.**
   - Timing per edge from the executor's real entry state is accurate (traverse mean error 0.10 ticks over 1236
     edges).
   - Open-loop drift with recorded inputs is about 0.01 m at 10 ticks and about 0.1 m at 40 (median).
   - With SimEdge's own assumed steering, it is 0.07 m and 0.17 m. p90 is 2–4× worse at 10–20 ticks, because the
     kinematic driver cuts corners where SimEdge aims at cell centres.
   - Chained over many edges, ascends are where the model is weakest (−4 ticks median).
   - Line and Descend, which the BARITONE driver runs, are not simulated at all.
3. **SimEdge's alternatives are physically followable but worth little.**
   - The executor drives SimEdge's routes at the predicted speed.
   - The route difference is near the start (accelerate straight from rest, then the diagonal) and is worth 0–3
     ticks per lap.

**Experiment result (2 seeds × 96 laps):**

- Horizon 8 cuts SimEdge's first-plan wait from 12–13 ticks to 4–5, against A*'s 2.
- No arm lost a lap.
- Paired lap-ticks medians:

  | pair | range |
  |---|---|
  | D/A | 0.978–1.015 |
  | D/C | 0.978–1.020 |
  | D/B | 0.931–1.012 |

- M1 (≤ 0.97 against the legacy baseline on ≥ 2 legs) is not met in either seed.
- M2 (against the existing SimEdge) is met in seed 71006 only (slalom 0.962, steps 0.961). In 71007 only ridges
  (0.931) passes.
- The mechanism holds in both seeds (wait D − A ≤ 3), so the latency explanation is confirmed. But removing the
  latency leaves almost nothing: driving D/A without the wait is 0.97–0.98 on steps and 0.997–1.006 elsewhere.

**Decision:**

- **Class C (no improvement).** `simEdgeSearch` stays default false, and `simEdgeHorizon` keeps its default of 32.
- No further SimEdge optimization in Phase 05B, as pre-registered.
- **Keep SimEdge as disabled research code** (pre-registered archive rule: steps D/A ≤ 0.99 in both seeds, 0.988 and
  0.978). Do not delete it; do not enable it.
- Finding for anyone who enables it anyway: horizon 8 dominates horizon 32 on this course. D/B ≤ 0.981 on slalom,
  ridges and steps in both seeds, and only bumps is slightly worse (1.003 and 1.012).

**Recommended next action (owner decision):**

- Do not fund another latency fix for SimEdge on this course. Even zero wait would gain about 2–3% on steps only,
  short of the ≥ 2-leg gate.
- SimEdge can only pay off where momentum decides the route: gaps, sprint jumps, long ascent chains. That would need
  a new pre-registered scenario family and its own phase, not a retuning of this one.
- If no such scenario is wanted, archive SimEdge.
