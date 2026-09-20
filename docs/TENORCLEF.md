# TenorClef architecture docs

TenorClef (AltoClef fork) is the high-level agent that consumes Ostinato as its movement layer.

Phase 0 architecture audit and roadmap live in the TenorClef repo:

- https://github.com/vexrypt-rgb/TenorClef/blob/main/docs/ARCHITECTURE.md
- https://github.com/vexrypt-rgb/TenorClef/blob/main/docs/OSTINATO_BOUNDARY.md
- https://github.com/vexrypt-rgb/TenorClef/blob/main/docs/ROADMAP.md
- https://github.com/vexrypt-rgb/TenorClef/blob/main/docs/DEPENDENCIES.md
- https://github.com/vexrypt-rgb/TenorClef/blob/main/docs/DEVELOPMENT.md

Ostinato owns pathfinding / physics traversal / Baritone+Tungsten backends.
TenorClef owns goals, planning, tasks, world model, and recovery.

## MovementEngine (Phase 2)

See [`MOVEMENT_ENGINE.md`](./MOVEMENT_ENGINE.md). Tip `main` exposes `IMovementEngine` /
`HybridMovementEngine` built on the existing `IMovementBackend` precursor.
TenorClef migrates travel call sites gradually via a thin adapter (stock Baritone jars
fall back to `CustomGoalProcess`).

## Build JDKs (Phase 1 note)

| Branch / line | Minecraft | JDK | Gradle |
| --- | --- | --- | --- |
| `main` (this tip) | 1.21.11 | **21** | 8.x — CI: `.github/workflows/gradle_build.yml` |
| `1.16.1` | 1.16.1 | **8** | **4.9** — do not build with JDK 21 |

TenorClef CI checkouts this tip for the `1.21.11` compile job and stages fabric jars into a sibling `Ostinato/dist` layout.
