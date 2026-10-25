# Ostinato driver API (for TenorClef and other driving mods)

Step 2 of the TenorClef↔Ostinato coupling plan. Everything here is in `src/api` (`baritone.api.*`) and delegates to the existing internals, so behaviour is unchanged. A driver should reach Ostinato only through `BaritoneAPI.getProvider().getPrimaryBaritone()` (an `IBaritone`) and these types.

| Need | API | Replaces |
| --- | --- | --- |
| Policy hooks (break/place avoiders, walk-on/avoid-walk-through, protected items, tool save/use, global heuristic, interaction pause, lava swim, flowing water, bucket-fall, soul sand) | `IBaritone.getAgentPolicy()` → `baritone.api.policy.IAgentPolicy` | `baritone.altoclef.AltoClefSettings.getInstance()` |
| Pause the executor for a tick | `IPathingBehavior.requestPause()` | cast to internal `PathingBehavior` |
| Check the current movement can be interrupted | `IPathingBehavior.isSafeToCancel()` | cast to internal `PathingBehavior` |
| Combat | `IBaritone.getPvpProcess()` → `IPvpProcess` (`attackPlayer`, `attackPlayers`, `attackHostiles`, `isActive`, `getTarget`, `getStats()` → `PvpStats`, `stats()`, `displayName()`) | reflection over `PvpProcess` and its public counter fields |
| Swarm | `IBaritone.getSwarm()` → `ISwarm` (`isOnline`, `state`, `self`, `myGroups`, `group`, `send(group, to, type, body, SwarmPriority)`, `registerHandler(type, (from, group, body) -> …)`), `ISwarmGroup`, `baritone.api.swarm.SwarmPriority` | reflection over `SwarmBehavior`, `SwarmControl`, `SwarmRoster`, `SwarmEndpoint`, transport `SwarmPriority` |
| Movement engine | `IBaritone.getMovementEngine()`, `getMovementBackendPreference()` | reflection over `baritone.movement.MovementBackends` |
| Freecam ghost tag text | `IBaritone.setFreecamStatus(Supplier<String>)` | writing static `FreecamBehavior.statusSupplier` |
| Build-finished flag | `IBuilderProcess.isExternalBuildFinished()` | `isFromAltoclefFinished()` (deprecated, still works) |

Not in this cut (next Ostinato API PR): read-only movement queries to replace `MovementHelper` / `CalculationContext` / `ToolSet` / `BlockStateInterface` use, typed Ostinato-only settings (`sprintJump`, `kinematicTravel`, `physicsTravel`, `movementFault`; held back because pathfinding Phase 2 is editing `Settings.java`), and the shared `fault` package.

Backport (`1.21.11`, `1.21.4`, `1.16.1`) comes after TenorClef 26.3 is ported. `PvpStats` is a final class, not a record, so it compiles on Java 8. 1.16.1 has no `PvpProcess`; its `getPvpProcess()` will need a no-op implementation.
