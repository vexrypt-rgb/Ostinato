# Porting and upstream notes

This document records the current maintenance baseline for Ostinato. It is developer
context, not installation guidance.

## Upstream base

| Field | Value |
| --- | --- |
| Upstream | `cabaletta/baritone` |
| Branch | `1.21.11` |
| Minecraft | 1.21.11 |
| Java | 21 |
| Base SHA | `23723891da460ef15797b02fe5b385b0c5b163cc` |

The `26.x` line requires Java 25, while the current TenorClef/Ostinato toolchain uses
Java 21. `1.21.11` is therefore the modern baseline. Update this table whenever the
upstream base or required Java version changes.

## AltoClef-compatible ports

Ostinato carries API and behavior needed by TenorClef, originating largely from
MiranCZ's `baritone_altoclef` patches and adapted to the modern mappings:

1. `AltoClefSettings` hooks for movement, interaction, protected items, and path cost.
2. Inventory and tool behavior for protected throwaways and force-save/use tools.
3. Pathing hooks for break/place avoidance, lava/swim handling, portal walkability,
   soul sand, bucket falls, and heuristic overrides.
4. Mine and builder process hooks, including AltoClef stack helpers.
5. Input/raytrace hooks for paused interaction and fluid handling.
6. `IBuilderProcess.popStack()` and `isFromAltoclefFinished()` API additions.

Some source patches did not apply cleanly because their original context was 1.21.4;
the equivalent code was re-applied manually against 1.21.11. Keep those adaptations
small and document any new manual port here.

## Known integration limits

- Tungsten currently handles goto/custom-goal travel only.
- TenorClef still has a compatibility facade for Tungsten-facing tasks.
- TenorClef consumes local Ostinato artifacts during development; publication under a
  pinned dependency coordinate remains a release follow-up.

## Updating upstream

1. Record the target Cabaletta branch and commit in this document.
2. Compare upstream changes against the files touched by the AltoClef ports.
3. Rebuild every loader and run the test suite.
4. Verify the Fabric artifact with TenorClef's primary module.
5. Update release notes with the new matching revision.
