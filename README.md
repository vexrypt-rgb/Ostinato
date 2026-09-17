# Ostinato (1.16.1)

Baritone fork for **Minecraft 1.16.1** / Fabric, carrying AltoClef hooks (`AltoClefSettings`, BuilderProcess stack APIs, inventory/tool/pathing hooks) from MiranCZ `baritone_altoclef` on cabaletta **1.16.5** sources, retargeted for TenorClef `1.16.1`.

## Critical 1.16.1 fixes

1. **`MixinClientPlayNetHandler.postHandleMultiBlockChange`** — no-op. Upstream 1.16.5 calls `SMultiBlockChangePacket.func_244310_a` (yarn `method_30621` / `visitUpdates`), which does not exist on 1.16.1 (`NoSuchMethodError`).
2. **`BlockOptionalMeta.drops`** — catch loot generation failures (including `minecraft:origin` loot table issues without a full server world) so BuilderProcess does not crash.

## Artifact

Built / shipped as:

`dist/baritone-unoptimized-fabric-ostinato-1.16.1.jar`

TenorClef `:1.16.1` prefers this jar from `../Ostinato/dist` (see TenorClef `docs/OSTINATO_WIRING.md`).

## Build (from this branch)

Requires the legacy Baritone Fabric toolchain (Loom 0.7 / Java 8) used by cabaletta 1.16.5:

```bat
cd Ostinato-1.16.1
set JAVA_HOME=<JDK8>
gradlew.bat build -Pbaritone.fabric_build
```

Then copy `dist/baritone-unoptimized-fabric-*.jar` to `../Ostinato/dist/baritone-unoptimized-fabric-ostinato-1.16.1.jar`.

## Relationship to main

Ostinato `main` / `1.21.11` is modern cabaletta Unimined (MC 1.21.11). This `1.16.1` branch is a parallel lineage for legacy TenorClef — same AltoClef API intent, different Minecraft/mappings toolchain.
