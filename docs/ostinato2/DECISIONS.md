# Ostinato 2.0 — decisions

Phase 00 recorded the baseline. Phase 01 added the input arbiter. D7 supersedes the headless tick-driver assignment. D8 is the Phase 02 comparison on the combined tree. D5's sentence that PPE is absent applied to `feature/secondo-v1` before the merge.

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

No edit to `PlayerSim`, `PhysicsPathfinder`, `PathExecutor`, `PathingBehavior`, `AStarPathFinder`, `InputOverrideHandler`, or `TravelStack`. Robustness margins are null. Phase 03 was not started.

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

## Phase 02

Not started. This file does not open it.
