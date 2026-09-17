# Ostinato

Ostinato is a Baritone fork seeded from modern [cabaletta/baritone](https://github.com/cabaletta/baritone), with AltoClef-specific APIs ported from [MiranCZ/baritone_altoclef](https://github.com/MiranCZ/baritone_altoclef) so [TenorClef](https://github.com/vexrypt-rgb)/AltoClef can drive pathing, inventory, and schematic build hooks.

## Upstream base

| Field | Value |
| --- | --- |
| Upstream | `https://github.com/cabaletta/baritone` |
| Branch chosen | `1.21.11` (Minecraft **1.21.11**, Java 21) |
| Base SHA | `23723891da460ef15797b02fe5b385b0c5b163cc` |
| Why not `26.x` | Minecraft 26.x needs **Java 25**; this machine/TenorClef toolchain is Java 21. Cabaletta default branch is still `1.21.4`; `1.21.11` is the newest `1.21.x` that matches that toolchain. |
| Why not gaucho-matrero | Stale; user asked for cabaletta as source of truth. |

Upstream Baritone docs/history: see `README.baritone.md`.

## AltoClef ports (from MiranCZ `baritone_altoclef` patches, primarily `1.21.4`)

Ported onto Ostinato with mappings adapted for modern mojmap / 1.21.11:

1. **`baritone.altoclef.AltoClefSettings`** â€” singleton settings hooks TenorClef/AltoClef mutate (break/place avoiders, force walk-on / avoid walk-through, force-save / force-use tool predicates, protected items, interaction pause, flowing-water pass, lava swim, soul-sand treatment, end-portal walk, global heuristic hooks).
2. **Inventory / tool APIs** â€” `InventoryBehavior` respects interaction pause, protected throwaways, and place-avoid; `ToolSet` honors force-save / force-use tools and soft-block early-out.
3. **Pathing hooks** â€” `PathNode` global heuristic; `CalculationContext` / `MovementHelper` avoid break/place, lava swim, forced walk-through avoidance, end-portal frame/portal walkability; descend/fall/traverse/path executor soul-sand / bucket-fall / walk-through force checks.
4. **Processes** â€” `MineProcess` pause + portal/lava plausibility; `BuilderProcess` AltoClef stack (`popStack` / `isFromAltoclefFinished`), protected schematic items, interaction-pause around place/inventory, break-history helpers.
5. **Input / raytrace** â€” `InputOverrideHandler` clears click inputs while paused; `RayTraceUtils.fluidHandling` overridable for AltoClef.
6. **API surface** â€” `IBuilderProcess.popStack()` / `isFromAltoclefFinished()`.

### Could not / did not port

| Item | Reason |
| --- | --- |
| MiranCZ `0004-Properties-changes.patch` (pin `mod_version`, strip git describe) | Kept cabaletta version detection / `gradle.properties` for 1.21.11 loaders. |
| Exact 1.21.4 line context for some hunks | Re-applied by hand against 1.21.11 (inventory list APIs, movement helpers, builder tick). |
| Full Tungsten movement backend | Stub/notes only â€” see below. TenorClef wiring left for a later step. |
| Publishing TenorClef `build.gradle` dependency swap | Deferred; optional note below. |

## Tungsten movement backend

Ostinato can execute **goto / custom-goal travel** via Tungsten physics A* when the Tungsten Fabric mod is on the classpath. Mining, digging, schematics, and inventory stay on classic Baritone pathing.

| Setting | Values | Default |
| --- | --- | --- |
| `movementBackend` | `baritone` / `tungsten` / `auto` | `auto` |

- `baritone` â€” always classic Baritone travel
- `tungsten` â€” prefer Tungsten when present; else Baritone
- `auto` â€” Tungsten when present; else Baritone

Enable Tungsten:

1. Build or obtain a Tungsten Fabric jar (TenorClef vendors `3ndetz/Tungsten` @ `altoclef-compat`; jar under `altoclef/libs/tungsten-*.jar` or `vendor/tungsten/build/libs`).
2. Drop that jar next to Ostinato / into the Minecraft `mods` folder with Ostinato.
3. In game: `#set movementBackend tungsten` (or leave `auto`).

SPI: `baritone.api.movement.IMovementBackend` with `BaritoneMovementBackend` and reflection-based `TungstenMovementBackend`. Hooked from `CustomGoalProcess` only.

### TenorClef note

TenorClef still has its own `TungstenMovement` facade for tasks. Prefer Ostinato `movementBackend` when both mods load together so Baritone custom goals and AltoClef travel share one switch. Point TenorClef at Ostinatoâ€™s `baritone-unoptimized-fabric` jar (see TenorClef `build.gradle` / docs).

## Build

```bat
cd C:\Users\user\Documents\MinecraftDev\Ostinato
gradlew.bat build
```

- Java **21** required (`java_version=21` in `gradle.properties`).
- Loaders: fabric / forge / neoforge / tweaker (same as upstream).
- First configure may take a long time (Unimined remaps Minecraft for each loader).

### TenorClef wiring

| TenorClef module | Ostinato source |
| --- | --- |
| `1.21.11` (preferred modern) | this branch / `main` dist `baritone-unoptimized-fabric-*.jar` |
| `1.21.1` / `1.21` | same modern jars (API compile OK; prefer `:1.21.11:runClient` for matching MC) |
| `1.16.1` | Ostinato branch `1.16.1` → `baritone-unoptimized-fabric-ostinato-1.16.1.jar` |

See TenorClef `docs/OSTINATO_WIRING.md`.

## License

LGPL-3.0 with upstream Baritone anime exception â€” see `LICENSE` / upstream notices.


## Best-of-forks notes (UnionClef / Cabaletta / AltoClef)

- **Travel:** `movementBackend=auto` prefers Tungsten when present (UnionClef tungsten-first travel model); mining/build stay on classic Baritone/Ostinato processes.
- **Tungsten `ActionCosts.COST_INF`:** must be **positive** (Baritone convention). A negative sentinel breaks A* relaxation (UnionClef fix; ported into TenorClef `vendor/tungsten`).
- **1.16.1:** Ostinato branch `1.16.1` replaces libs/MiranCZ Baritone; see TenorClef `docs/OSTINATO_WIRING.md`.
- **1.21.11:** preferred modern MC alignment with Ostinato `main`.
