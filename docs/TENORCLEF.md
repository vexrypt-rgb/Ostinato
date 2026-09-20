# TenorClef architecture docs

TenorClef (AltoClef fork) is the high-level agent that consumes Ostinato as its movement layer.

Phase 0 architecture audit and roadmap live in the TenorClef repo:

- https://github.com/vexrypt-rgb/TenorClef/blob/main/docs/ARCHITECTURE.md
- https://github.com/vexrypt-rgb/TenorClef/blob/main/docs/OSTINATO_BOUNDARY.md
- https://github.com/vexrypt-rgb/TenorClef/blob/main/docs/ROADMAP.md
- https://github.com/vexrypt-rgb/TenorClef/blob/main/docs/DEPENDENCIES.md

Ostinato owns pathfinding / physics traversal / Baritone+Tungsten backends.
TenorClef owns goals, planning, tasks, world model, and recovery.

Until the MovementEngine API (roadmap Phase 2) lands, TenorClef still calls Baritone processes directly; tip `main` already has a precursor `IMovementBackend` SPI.
