# MovementEngine on Ostinato 1.16.1

Phase 2 MovementEngine (`IMovementEngine`, `HybridMovementEngine`, `MovementGoal`, …)
lives on Ostinato **tip** (`main` / modern MC). This `1.16.1` line does **not** ship those
types yet.

## Behavior for TenorClef 1.16.1

- Travel is **Baritone-only** (Tungsten is not supported on 1.16.1).
- TenorClef `MovementEngineAdapter` probes for Ostinato engine classes via reflection;
  when absent it falls back to `getCustomGoalProcess()` — so 1.16.1 needs no stub jar.
- No parallel SPI is required on this branch for Phase 2.

## Optional later work

If TenorClef 1.16.1 should compile against engine types without reflection, add a thin
no-op `IMovementEngine` that only wraps `ICustomGoalProcess` (Baritone path). Not needed
for Phase 2 deliverables.
