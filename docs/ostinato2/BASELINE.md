# Ostinato 2.0 — baseline

Labels: **VERIFIED** (read in source, or produced by a command in this session), **REPORTED** (repo docs or comments, not re-run), **UNKNOWN** (not measured and not proven).

## Identity

| Fact | Value | Label |
|---|---|---|
| Remote | `https://github.com/vexrypt-rgb/Ostinato.git`, `origin/HEAD` = `origin/main` | VERIFIED |
| Audited commit | `67e12b96ec1dbe63833302ce3a45513abcd50de4` | VERIFIED |
| Same tree | `2f4176049` (Secondo v1). Empty diff against `origin/main` | VERIFIED |
| Minecraft | `gradle.properties` `minecraft_version=26.3` | VERIFIED |
| Java | `java_version=25`. This machine: Temurin `25.0.4.1` | VERIFIED |
| Mod version field | `mod_version=1.24.0` | VERIFIED |
| Build | Gradle 9.4.1, Unimined 1.4.2. `settings.gradle` includes each loader in `available_loaders` (`fabric`). `build.gradle` test heap defaults to 3g | VERIFIED |
| Loaders present as directories | `fabric`, `forge`, `neoforge` | VERIFIED |
| Native | `native/` Rust crate, ABI 3, Java FFM in `baritone.natives.NativeLib`. This run used `-x nativeHost`. Load of the dll was not checked | VERIFIED / UNKNOWN |
| Audit worktree dirt before docs | `Ostinato-secondo`: untracked `SECONDO_STEER.txt` only | VERIFIED |
| Other checkout, not audited | `MinecraftDev\Ostinato` is `swarm/s2s-identity-1161` at `3d0b16747`, dirty (`PathExecutor.java`, deleted dist jar, untracked `mapping/`, `polish/`). Not 26.3 | VERIFIED |

`docs/pathbench/baseline.md` (commit `df7c174ec`, 2026-10-17) is **REPORTED** and does not describe this commit's expansions. Its FLAT SHORT median is 771 nodes. `golden.txt` and this session both record `FLAT/SHORT/0` as `ok 300.365091 132`.

## Call path

Public goal, on the game thread:

1. `#goto` coordinates call `CustomGoalProcess.setGoalAndPath` (`GotoCommand` lines 46–51). A block name goes to `GetToBlockProcess` instead. `#goal` only sets the goal (`GoalCommand`). `#path` calls `path()` (`PathCommand`).
2. `CustomGoalProcess` states: `GOAL_SET` returns `CANCEL_AND_SET_GOAL`; `PATH_REQUESTED` returns `FORCE_REVALIDATE_GOAL_AND_PATH` once; `EXECUTING` returns `SET_GOAL_AND_PATH` (`CustomGoalProcess` lines 98–123).
3. `PathingBehavior.onTick` on `TickEvent.Type.IN` calls `PathingControlManager.preTick` then `tickPath` (`PathingBehavior` lines 167–181). `Type.OUT` calls `secretInternalSegmentCancel` and `cancelEverything`.
4. `executeProcesses` sorts active processes by `priority()` descending and takes the first command that is not `DEFER` (`PathingControlManager` lines 185–217). A non-temporary winner calls `onLostControl` on the rest.
5. `secretInternalSetGoalAndPath` builds a thread-safe `CalculationContext` and `findPathInNewThread` (`PathingBehavior` lines 346–374, 639–699).
6. `createPathfinder` may simplify an unloaded `IGoalRenderPos` to `GoalXZ`, builds `Favoring` from the player context, and may override weight or hierarchy from `PathDecisionMatrix.choose`. Otherwise it constructs `AStarPathFinder` with `farSegmentWeight` (`PathingBehavior` lines 842–874).
7. The executor thread calls `AbstractNodeCostSearch.calculate`. That may cut a stored route via `RouteReuse`, else `calculate0`. It then cuts at loaded chunks and at a static goal cutoff, and can hold a timed-out path at an irreversible drop (`AbstractNodeCostSearch` lines 204–259).
8. A path becomes a `PathExecutor` only in that callback (`PathingBehavior` line 701). Splice and cutoff construct further executors from an existing one (`PathExecutor` lines 1041, 1060). No test constructs one. **VERIFIED**.

Search:

- `AStarPathFinder` expands `Moves` (`AStarPathFinder` around line 185). Neighbors are movement costs, not key presses.
- Movement catalog includes traverse, ascend, descend, diagonal, pillar, downward, fall, swim, parkour, jump, chain jump, climb jump, bubble lift, god bridge, slime, line, tas (`Moves.java`, `movement/movements/`).
- Headless bench does **not** use `calculate` or `createPathfinder`. `PathBench.run` calls `finder.create(...).calculate0(10s, 10s)` with `Favoring` unused and `CalculationContext(SyntheticWorld, HeadlessToolSet, ...)` (`PathBench` lines 237–260, `CalculationContext` lines 184–189).

Execution, still on the game thread, `PathExecutor.tickOnce`:

1. `TravelStack.order(travelStack, kinematicTravel, simTravel)` (`PathExecutor` lines 235–238, `TravelStack` lines 52–87).
2. Order tried: engaged takeoff, then kinematic `KinematicController`, then `kinematicFamily` (fall, swim, clutch, ladder, mine, pillar, extension, takeoff, boat), then `PhysicsTravel.tick(..., stackAsked=true)`, then `SimTravel`, else `Movement.update`.
3. `Movement.update` clears keys and writes `InputOverrideHandler.setInputForceState` (`Movement` lines 139–167). Look goes to `LookBehavior.updateTarget` (`LookBehavior` line 70).
4. `InputOverrideHandler.onTick` installs `PlayerMovementInput` while a move key, analog stick, or path is active, and ticks break/place helpers (`InputOverrideHandler` lines 156–185). `physicalInput` rate-limits edges in `realizePhysicalKeys`.

Completion:

- `CustomGoalProcess` drops control when the goal contains both `playerFeet` and `pathStart`, and may disconnect or notify (`CustomGoalProcess` lines 112–122).
- `PathingBehavior` fires `PathEvent.AT_GOAL` when the executor finishes or fails and the feet are in the goal, then clears keys (`PathingBehavior` lines 234–248).
- Both can disconnect. Two reporters. **VERIFIED**.

## Controllers, cancellation, mismatches

Process priority, higher wins (`IBaritoneProcess.DEFAULT_PRIORITY` is -1):

| Process | Priority | Source |
|---|---|---|
| InventoryPauser | 5.1 | `InventoryPauserProcess` |
| Backfill | 5 | `BackfillProcess` |
| Air | 4 | `AirProcess` |
| Bastion, Boat | 3 | those processes |
| Pvp, Pve | 2 | those processes |
| Elytra | 0 | `ElytraProcess` |
| Secondo | -0.5 | `SecondoProcess` lines 135–137 |
| Custom goal, mine, follow, builder, and any process that does not override | -1 | default |

Cancellation:

- `PathExecutor.cancel` clears keys, stops breaking, sets `pathPosition` past the end, and sets `failed` (lines 1019–1024).
- `cancelEverything` cancels the segment only when `isSafeToCancel`, and always calls `onLostControl` on processes. A non-temporary process that stays active throws (lines 424–430, `PathingControlManager` lines 63–73).
- A search `cancel()` does not dispatch `CALC_FAILED` (`PathingBehavior` lines 220 and 729–731).
- Changing the non-temporary process in control calls `cancelSegmentIfSafe` (`PathingControlManager` lines 96–99).

Safe-to-cancel while a driver holds the tick is true only for `KINEMATIC` or `SIM` and `onGround` (`PathExecutor` lines 298–304). `PHYSICS`, swim, fall, ladder, mine, pillar, extend, and boat return not safe. `pausableNow` is true for kinematic, physics, or sim on the ground (lines 170–171) and is a separate pause path. **VERIFIED** asymmetry.

Patience: look-ahead kinematic, physics, and sim give up after 100 ticks on one position (`DRIVER_PATIENCE`) and rest 60 (`M01`). Family drivers are exempt. `watchProgress` cancels any driver after 400 ticks plus the cached movement cost, capped (`M05`, lines 965–1006), and marks `JumpFailures` and `RouteReuse.forget`.

Planner versus executor:

- Live search is `calculate` (reuse, chunk cutoff, drop hold). The bench is `calculate0` only. **VERIFIED**.
- Live favoring uses the player. The bench passes a null baritone into `Favoring` via `new AStarPathFinder(..., new Favoring(null, c), c)`. **VERIFIED**.
- `travelStack=auto` always includes physics after kinematic, and `PhysicsTravel.tick(..., true)` does not consult `physicsTravel` (`TravelStack` lines 75–84, `PhysicsTravel` lines 66–77). **VERIFIED**.
- `PathBench.Engine.PHYSICS` only sets `physicsTravel`. The comment states physics has no search of its own (`PathBench` lines 71–97). **VERIFIED**.
- Kinematic, physics, and sim return before `Movement.update`, so the planned movement class is not what presses keys on those ticks. **VERIFIED**.
- `SimDrillStage` states it checks plans, not execution, and that live failure of a headless plan points at the executor (class comment). **VERIFIED** as a source comment. The stage was not run.

## PPE, sim, world, Rust, version

PPE is **not on this branch**. `SecondoFeatures` lists `baritone.api.physics.IPredictivePhysics` as landed on the PPE branch and absent here (lines 19–32). `invoke` always returns false (lines 139–145). `SecondoTest.plannedApisAreAbsentOnThisBranchAndInvokeIsANoop` passed this session. **VERIFIED**.

On `Ostinato-ppe` `feat/predictive-physics` `69052a387` (merge-base with `origin/main` is `1aed5cc21`, so it does not contain the Secondo merge): `IPredictivePhysics` says it ticks bodies with vanilla 26.3 server rules and that every method is for the client thread (`IPredictivePhysics.java` lines 8–13). `PredictivePhysics` captures terrain on that thread. `KinematicController.takeoffCheck` calls `predict` for Presto. **VERIFIED** on that worktree only. Not part of the measured baseline.

`PlayerSim` is a pure-Java tick model. Its class comment says it is an allocation-light copy of vanilla **1.16** `LivingEntity.travel` plus `Entity.move`: walk, sprint, jump, step, ladders, still water, soul sand, honey. No lava, currents, or sneaking. Effects: Speed, Slowness, Jump Boost (`PlayerSim` lines 7–11). `tick` / `tickImpulse` / `tickWater` are one tick each. **VERIFIED**.

World access:

- Live planning: `CalculationContext(IBaritone, boolean)` reads `LocalPlayer`, `ClientLevel`, inventory, food, boats (`CalculationContext` lines 264–284) and a `BlockStateInterface`.
- Headless planning: `SyntheticWorld extends BlockStateInterface` plus `HeadlessToolSet`. No player. Health is hardcoded 20.
- Live kinematic collision: `ClientWorld implements PlayerSim.World` and reads the client world (`ClientWorld` lines 16–24). It refuses to roll `PlayerSim` forward when levitation, slow falling, or a changed gravity attribute is present (lines 38–40). **VERIFIED**.

Rust (`native/src/lib.rs`): twins of hot searches, FFM, ABI 3, exports include sim, jump, physics plan, tas, momentum hop. Java refuses a different ABI (`NativeLib` lines 141–146). Parity tests exist (`NativeParityTest`, `NativePlannerParityTest`) and were not run. **UNKNOWN** whether this machine's dll matches.

## Headless PathExecutor

**No.** `onTick` reads `ctx.player()`, feet, chunk presence, block states, and the input handler. The only production constructor takes `PathingBehavior`. A spliced executor copies that behavior's context. No unit test builds one. Tick-granular headless execution that exists today is `PlayerSim.tick*` and `PhysicsPathfinder` over a `PlayerSim.World`, not `PathExecutor`.

## Phase 01 input ownership

`Settings.inputArbitration` defaults to false, declared beside `physicalInput`. **VERIFIED**.

Flag off: `FallController.tick` calls `drive` and returns. `press` clears the override map and sets forward, back, left, right, sneak, jump, and right-click. It does not call `getInputArbiter`. A false entry and a missing entry both read as up (`InputOverrideHandler.isInputForcedDown`, `PhysicalInput.Chord.on`), so those explicit offs match the previous clear-then-set. **VERIFIED** in source. This session did not boot a client.

Flag on: the same `press` claims owner `fall` at priority 10 for move, jump, sneak, and click, submits one nine-key chord (`FallInput.chord`; sprint and left-click stay up), seals, and writes only the keys that are down. A tick that returns -1 after a frame was written calls `confirmRelease` and clears those keys. A `RuntimeException` calls `fail` and rethrows. **VERIFIED** in source. The arbiter outcomes are unit-tested. A live fall with the flag on was not run.

`Baritone` holds one `InputArbiter` per bot. `InputOverrideHandler`'s map is unchanged. No other controller was moved onto the arbiter. `PathExecutor` order is unchanged.

## Phase 01 point

See `DECISIONS.md`. The safe seam is the headless planner already called by `PathBench.run`, plus `PlayerSim` as the tick body. It is not `PathExecutor` and not `SecondoFeatures.invoke`.
