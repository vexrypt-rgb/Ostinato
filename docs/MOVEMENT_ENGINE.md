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
| `MovementBackendKind` | `BARITONE`, the backend that accepted a request (Ostinato's own pathing is the only one) |

## Implementations (`baritone.movement`)

| Class | Role |
|-------|------|
| `HybridMovementEngine` | Starts Baritone custom-goal pathing (flown by the kinematic and physics travel drivers); follow goals become a `GoalNear` round the entity |
| `MovementBackends` | `engine(IBaritone)` factory and `preference()`, both reached by TenorClef through reflection. `preference()` stays `BARITONE`. The executor reads `travelStack`. |

There is no alternate backend. Ostinato used to bridge to the Tungsten mod for goto travel; its own kinematic and
physics travel replaced that, and the bridge and the `movementBackend` setting were removed.

## TenorClef usage

```java
IMovementEngine engine = MovementBackends.engine(mod.getClientBaritone());
PathResult r = engine.goTo(MovementGoal.getToBlock(pos));
// or engine.follow(entity, 2.0);
```

When the running jar is stock Baritone (no MovementEngine classes), TenorClef’s adapter falls back to `getCustomGoalProcess()`.

## Version notes

| Line | MovementEngine |
|------|----------------|
| Ostinato tip (`main`, modern MC) | Yes |
| Ostinato `1.16.1` | No MovementEngine types yet |

## Out of scope (later phases)

Planner, world model, `TaskResult`, migrating all ~60 TenorClef Baritone call sites, mining/builder through this engine.
