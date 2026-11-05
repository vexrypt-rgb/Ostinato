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

- **Tier-L source:** `Ostinato-bastion-263/fabric/run/client/simtrace/`, 2026-10-14, 26.3 client. These are historical traces: no client was run in this review. `PlayerSim` was diffed against that commit and is unchanged apart from a documented bit-identical refactor (REVIEW F-H2).
- **Tier-S evidence:** `CrossModelModesTest`. PlayerSim and PPE agree within 7e-6 blocks over 20–30 ticks in every mode (`raw/review/phase04-crossmodel-summary.txt`). That bounds disagreement between the models. It is not error against the game, and the near-zero agreement suggests the two share a derivation, so it is weak independent evidence.
- **Flag off:** with the setting off, both consumers run their old check. Tests compare the off path against the old rule: `PredictionToleranceTest.flagOffSimTraceIsTheOldFixedThreshold` (every row field, `firstDiverged` and the summary string) and `flagOffDriftIsTheOldSummedAxisCheck` (100,000 random pairs). With the flag off, mode classification is skipped entirely.
- **Flag on is a behavior change:**
  - `PhysicsTravel` replans on SPRINT ground ticks only past 0.2235 Euclidean, where it used to replan past 0.1 summed over the axes.
  - Whether that helps or hurts is a Tier-L question (laps on a course), and it was not run. The flag must stay off until it is.
- **Not done:** per-mode bounds for PPE as a predictor (PPE has live validation only for TNT), and version-specific constants (Phase 09).
