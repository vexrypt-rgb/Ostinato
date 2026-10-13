# TenorClef architecture docs

TenorClef (AltoClef fork) is the high-level agent that consumes Ostinato as its
movement layer.

Phase 0 architecture audit and roadmap live in the TenorClef repo:

- https://github.com/vexrypt-rgb/TenorClef/blob/main/docs/ARCHITECTURE.md
- https://github.com/vexrypt-rgb/TenorClef/blob/main/docs/OSTINATO_BOUNDARY.md
- https://github.com/vexrypt-rgb/TenorClef/blob/main/docs/ROADMAP.md
- https://github.com/vexrypt-rgb/TenorClef/blob/main/docs/DEPENDENCIES.md
- https://github.com/vexrypt-rgb/TenorClef/blob/main/docs/DEVELOPMENT.md
- https://github.com/vexrypt-rgb/TenorClef/blob/main/docs/OSTINATO_WIRING.md

Ostinato owns pathfinding / physics traversal / Baritone + Tungsten backends.
TenorClef owns goals, planning, tasks, world model, and recovery.

## MovementEngine (Phase 2)

See [`MOVEMENT_ENGINE.md`](./MOVEMENT_ENGINE.md). Tip `main` exposes
`IMovementEngine` / `HybridMovementEngine` built on the existing
`IMovementBackend` precursor. TenorClef migrates travel call sites gradually
via a thin adapter (stock Baritone jars fall back to `CustomGoalProcess`).

## Build JDKs

`gradle.properties` on this tip (`minecraft_version`) is the source of truth
for `main`.

| Branch / line | Minecraft | JDK | Gradle |
| --- | --- | --- | --- |
| `main` (this tip) | **1.21.4** | **21** | 8.x — CI: `.github/workflows/gradle_build.yml` |
| `1.21.11` | 1.21.11 | **21** | 8.x |
| `1.16.1` | 1.16.1 | **8** | **4.9** — do not build with JDK 21 |
| `26.3` | 26.3 | **25** | not a TenorClef pairing |

TenorClef's primary compile job is `:1.21.4` against Ostinato `main`. The
experimental `:1.21.11` job, when enabled, must check out Ostinato branch
`1.21.11` and stage Fabric jars into a sibling `Ostinato/dist` layout. Do not
feed a `main` (1.21.4) jar to that job.

## Vibe coding / AI use

Large parts of this repository were written or edited with AI assistants
(Claude, Grok, and similar). That is vibe coding: a person set the
direction; a model produced a lot of the text. A green CI run or a
commit message is not proof that a human understood every line.

Read the diff before you run or merge it. Do not treat this as audited
software. File bugs. Do not assume the model already considered your
case.
