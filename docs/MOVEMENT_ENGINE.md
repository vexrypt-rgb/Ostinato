# Ostinato MovementEngine (Phase 2)

Stable travel boundary for TenorClef and other agents. **Mining / digging / builder stay on classic Baritone processes** — this API covers goto / follow / custom-goal style travel only.

## API surface (`baritone.api.movement`)

| Type | Role |
|------|------|
| `IMovementEngine` | Public travel facade: `goTo` / `follow` / `status` / `cancel` |
| `MovementGoal` | Structured intent (block, get-to-block, XZ, Y, near, follow, custom Goal) |
| `MovementStatus` | `IDLE` / `REQUESTED` / `PATHING` / `ARRIVED` / `FAILED` / `CANCELLED` |
| `PathResult` | Accepted/failed outcome with backend + `MovementFailureReason` |
| `MovementFailureReason` | `NONE`, `BACKEND_UNAVAILABLE`, `DECLINED`, `PATH_CALC_FAILED`, `INTERRUPTED`, `UNSUPPORTED_GOAL`, `EXCEPTION` |
| `IMovementBackend` | Low-level executor (precursor SPI; still used internally) |
| `MovementBackendKind` | `BARITONE` / `TUNGSTEN` / `AUTO` (setting `movementBackend`) |
| `MovementBackendSelector` | Pure selection + failure mapping (unit-tested) |

## Implementations (`baritone.movement`)

| Class | Role |
|-------|------|
| `HybridMovementEngine` | Prefer Tungsten when available/appropriate; else Baritone; **never crash** if Tungsten absent |
| `TungstenMovementBackend` | Reflection bridge to Tungsten; soft-fail when jar missing |
| `BaritoneMovementBackend` | Marker for selection; real Baritone starts go through `ICustomGoalProcess` via the hybrid engine |
| `MovementBackends` | Setting-based resolve + `engine(IBaritone)` factory |

## Selection rules

1. Read `Settings.movementBackend` → `MovementBackendKind`.
2. `BARITONE` → classic custom-goal pathing only.
3. `TUNGSTEN` / `AUTO` → if Tungsten `isAvailable()`, try Tungsten first; on decline/exception fall back to Baritone.
4. Follow goals: Tungsten `follow(entity, dist)` when selected; else `GoalNear` via Baritone.

`CustomGoalProcess` retains its own Tungsten intercept for chat `#goto`-style control so existing behavior is preserved.

## TenorClef usage

```java
IMovementEngine engine = MovementBackends.engine(mod.getClientBaritone());
PathResult r = engine.goTo(MovementGoal.getToBlock(pos));
// or engine.follow(entity, 2.0);
```

When the running jar is stock Baritone (no MovementEngine classes), TenorClef’s adapter falls back to `getCustomGoalProcess()` / local Tungsten facade.

## Version notes

| Line | Tungsten | Hybrid behavior |
|------|----------|-----------------|
| Ostinato tip (`main`, modern MC) | Optional | Full hybrid |
| Ostinato `1.16.1` | Not supported | Baritone-only (no MovementEngine types yet — see docs note) |

## Out of scope (later phases)

Planner, world model, `TaskResult`, migrating all ~60 TenorClef Baritone call sites, mining/builder through this engine.
