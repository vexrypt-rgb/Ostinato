# Ostinato

Ostinato is a Baritone-derived pathfinding and automation engine for Minecraft.
It keeps the AltoClef-compatible APIs required by
[TenorClef](https://github.com/vexrypt-rgb/TenorClef) while tracking a modern
Cabaletta Baritone base.

TenorClef decides what to do. Ostinato provides movement, mining, building,
inventory, and schematic processes to make it happen. Multi-bot coordination
(`#swarm`) uses the [SIGIL](https://github.com/vexrypt-rgb/sigil) wire format
for sealed whispers.

## User guides

Step-by-step guides for every feature, written from the source:

- [Guide index](docs/guides/README.md)
- [Getting started](docs/guides/getting-started.md) · [Command reference](docs/guides/commands.md) · [Settings](docs/guides/settings.md)
- [Movement and travel](docs/guides/movement.md) · [Combat, clutch and survival](docs/guides/combat-and-clutch.md)
- [Mining, building and farming](docs/guides/mining-and-building.md) · [Swarm and region builds](docs/guides/swarm-and-region-builds.md)
- [Screen, freecam and task lists](docs/guides/gui-freecam-tasks.md)

## Compatibility

`gradle.properties` on this branch is the source of truth for the Minecraft
version of `main`.

| Minecraft | Branch | Java | Status |
| --- | --- | --- | --- |
| 1.21.4 | `main` | 21 | Primary. Pairs with TenorClef `:1.21.4`. |
| 1.16.1 | `1.16.1` | 8 | Legacy. Pairs with TenorClef `:1.16.1`. Gradle 4.9; do not build with JDK 21. |
| 1.21.11 | `1.21.11` | 21 | Experimental. TenorClef's 1.21.11 module is not a release target. |
| 26.3 | `26.3` | 25 | Upstream 26.x line. Not a TenorClef pairing. |

Do not point a 1.21.4 TenorClef build at a jar from `1.21.11` or `26.3`. See
[TenorClef's wiring guide](https://github.com/vexrypt-rgb/TenorClef/blob/main/docs/OSTINATO_WIRING.md).

## Build

Java 21 is required on `main`.

On Windows:

```bat
gradlew.bat build
```

On macOS or Linux:

```sh
./gradlew build
```

The first build may take some time: Unimined downloads and remaps Minecraft for
the enabled loaders. `available_loaders` on `main` is Fabric. Build outputs go
to `dist/`. The Fabric artifact TenorClef consumes is `:fabric:build`.

## TenorClef integration

Keep the trees as siblings. Build Ostinato first, then TenorClef.

| TenorClef module | Ostinato branch | Typical staged jar |
| --- | --- | --- |
| `:1.21.4` | `main` | `dist/baritone-unoptimized-fabric-*.jar` → TenorClef `libs/baritone-unoptimized-fabric-1.21.4.jar` |
| `:1.16.1` | `1.16.1` | `libs/baritone-unoptimized-fabric-1.16.1.jar` |
| `:1.21.11` | `1.21.11` | experimental; TenorClef does not yet compile this module |

Before shipping a 1.21.11 paired release, finish TenorClef's source port, tag
and publish the matching Fabric artifact under a pinned version coordinate,
then make TenorClef consume that coordinate.

## Movement backends

Default pathing is Baritone-compatible. On the modern target, an optional
Tungsten physics A* backend can handle goto / custom-goal travel. Mining,
digging, schematics, and inventory stay on classic Ostinato processes.

| Setting | Values | Behavior |
| --- | --- | --- |
| `movementBackend` | `baritone` | Always use classic travel |
| `movementBackend` | `tungsten` | Prefer Tungsten; use Baritone if unavailable |
| `movementBackend` | `auto` | Use Tungsten when present, otherwise Baritone |

Install a compatible Tungsten Fabric jar beside Ostinato, then
`#set movementBackend tungsten`. If Tungsten is absent, the fallback is
Baritone. Backend selection should be visible in logs; treat an unexpected
fallback as a configuration issue.

## Movement features

Beyond upstream Baritone, Ostinato adds:

- **Kinematic travel** (`#set kinematicTravel false` to disable, on by default): plain
  walking stretches of a path (traverse, diagonal, 1-block ascend, drops up to
  3 blocks) are driven by a per-tick physics look-ahead. The controller
  simulates a set of yaw and jump choices with a copy of vanilla player
  movement, then presses the keys of the one that gets furthest along the path
  while staying on it. Anything it cannot model (breaking, placing, water,
  most of parkour) goes back to Baritone. Experimental; benchmarked with
  TenorClef's PathBench.
- **Soprano-style movement tech, found by simulation.** These features and their design come from
  [Soprano](https://github.com/AverWasTaken/soprano) (AverWasTaken's Baritone fork); credit for the ideas
  is theirs, and the ladder clutch is ported from their code. Where Soprano hand-codes each jump, Ostinato
  searches vanilla player physics offline and ships the answers as templates, flown with the same
  search at run time (`baritone.pathing.kinematic`). The simulated player now handles ladders and
  vines, so these exist on top of the neo and momentum jumps already there:
  - **Ladder and vine jumps** (`allowClimbJumps`, off by default): catch a ladder or vine in mid air
    across a gap of 1 to 4 blocks, or let go of one and land on a ledge or another ladder
    (`MovementClimbJump`).
  - **Chained jumps** (`allowMomentumJumps`, on by default): two jumps through a one block pad, landing
    with the speed the next jump needs, for gaps no single jump makes (`MovementChainJump`).
  - **Ladder clutch** (`allowLadderClutch`, off by default): survive a long fall by placing a ladder or
    vine on a wall beside the last blocks of it, then pick the ladder back up (`pickupLadders`). Needs
    one on the hotbar; a water bucket is still preferred when it is no more expensive.
  - **Experimental movement** (`experimentalMovement`): a preset, not a settings rewrite. Parkour,
    neos, momentum and climb jumps, diagonals and `kinematicTravel` act as if on, jumps cost
    `experimentalJumpBias` times as much, block placement at most `experimentalBlockPlacementPenalty`,
    and falls that hurt are taken when nothing protects you and you stay above `experimentalMinHealth`.
    Turning it off hands your own settings back.
- **Checking the simulation** (`kinematicTrace`, off by default): while a jump, climb jump or chain flies,
  every tick compares the real player with `PlayerSim`'s prediction and logs `SIMTRACE` lines (per-tick
  rows in `simtrace/simtrace.csv`, one summary per movement in `simtrace/summary.csv`): the one-tick
  prediction error, how far the whole jump drifts open loop, mismatches on being grounded, and how far
  the real look lags the commanded yaw. `-Dostinato.simbench=jump,climb,chain` (or `all`; also
  `-Dostinato.simbench.limit=N`, `-Dostinato.simbench.exit=true`) builds a course in the sky for each
  template in a superflat singleplayer world, flies it, and writes `simbench/simbench.csv` plus a
  `SIMBENCH SUMMARY` per kind: landed count and the median/p90 worst one-tick error.
- **Pitfall avoidance** (`pitfallAvoidance`, on by default): the pathfinder
  never stands on sand, gravel or concrete powder resting on a block without
  collision (air, an open fence gate, a sign…), since it can drop out from
  under the player.
- **Water and air**: 3D swim moves, underwater digging, surface travel, and
  air management that uses bubble and magma columns.
- **Boats and elytra**: boat travel, including refusing boats occupied by
  mobs, plus elytra gliding with rocket-free descent.
- **Sprint-jumping** on land (`sprintJump`, off by default on current
  benches).

## Swarm and region builds

Bots running Ostinato can form a signed, whisper-based group, share status,
and split a schematic across the roster with `#swarm build`. Partitioning is
deterministic so every member computes the same plan locally. Tokens on the
wire are SIGIL S2 (including S2S when a sender must be proven).

- [Region builds](docs/REGION_BUILD.md)
- [SIGIL](https://github.com/vexrypt-rgb/sigil)

## Development documentation

- [Porting and upstream notes](docs/PORTING.md)
- [TenorClef pairing](docs/TENORCLEF.md)
- [Movement engine](docs/MOVEMENT_ENGINE.md)
- [Upstream Baritone documentation](README.baritone.md)
- [Features](FEATURES.md)
- [Setup](SETUP.md)
- [Usage](USAGE.md)

## License

Ostinato is licensed under LGPL-3.0 with upstream Baritone's anime exception.
See [LICENSE](LICENSE) and preserve all applicable notices when redistributing
artifacts.

## Vibe coding / AI use

Large parts of this repository were written or edited with AI assistants
(Claude, Grok, and similar). That is vibe coding: a person set the
direction; a model produced a lot of the text. A green CI run or a
commit message is not proof that a human understood every line.

Read the diff before you run or merge it. Do not treat this as audited
software. File bugs. Do not assume the model already considered your
case.
