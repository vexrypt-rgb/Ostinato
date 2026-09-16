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

1. **`baritone.altoclef.AltoClefSettings`** — singleton settings hooks TenorClef/AltoClef mutate (break/place avoiders, force walk-on / avoid walk-through, force-save / force-use tool predicates, protected items, interaction pause, flowing-water pass, lava swim, soul-sand treatment, end-portal walk, global heuristic hooks).
2. **Inventory / tool APIs** — `InventoryBehavior` respects interaction pause, protected throwaways, and place-avoid; `ToolSet` honors force-save / force-use tools and soft-block early-out.
3. **Pathing hooks** — `PathNode` global heuristic; `CalculationContext` / `MovementHelper` avoid break/place, lava swim, forced walk-through avoidance, end-portal frame/portal walkability; descend/fall/traverse/path executor soul-sand / bucket-fall / walk-through force checks.
4. **Processes** — `MineProcess` pause + portal/lava plausibility; `BuilderProcess` AltoClef stack (`popStack` / `isFromAltoclefFinished`), protected schematic items, interaction-pause around place/inventory, break-history helpers.
5. **Input / raytrace** — `InputOverrideHandler` clears click inputs while paused; `RayTraceUtils.fluidHandling` overridable for AltoClef.
6. **API surface** — `IBuilderProcess.popStack()` / `isFromAltoclefFinished()`.

### Could not / did not port

| Item | Reason |
| --- | --- |
| MiranCZ `0004-Properties-changes.patch` (pin `mod_version`, strip git describe) | Kept cabaletta version detection / `gradle.properties` for 1.21.11 loaders. |
| Exact 1.21.4 line context for some hunks | Re-applied by hand against 1.21.11 (inventory list APIs, movement helpers, builder tick). |
| Full Tungsten movement backend | Stub/notes only — see below. TenorClef wiring left for a later step. |
| Publishing TenorClef `build.gradle` dependency swap | Deferred; optional note below. |

## Tungsten movement backend (future)

TenorClef already has an optional Tungsten jar facade that falls back to Baritone. Ostinato intentionally does **not** implement Tungsten yet.

Planned later:

- Keep Baritone pathing as the default executor.
- Add a thin `MovementBackend` SPI (Baritone vs Tungsten) without breaking AltoClefSettings.
- Document the TenorClef `libs/tungsten-*.jar` drop path once Tungsten targets 1.21.11+.

## Build

```bat
cd C:\Users\redfa\Documents\MinecraftDev\Ostinato
gradlew.bat build
```

- Java **21** required (`java_version=21` in `gradle.properties`).
- Loaders: fabric / forge / neoforge / tweaker (same as upstream).
- First configure may take a long time (Unimined remaps Minecraft for each loader).

### TenorClef note (not wired yet)

When ready, point TenorClef at an Ostinato `baritone-unoptimized-fabric` artifact (local `mavenLocal` or `libs/`), same pattern as the existing MiranCZ/baritone-plus include. Do not commit secrets or machine-local JDK paths into Ostinato.

## License

LGPL-3.0 with upstream Baritone anime exception — see `LICENSE` / upstream notices.
