# Ostinato

Ostinato is a Baritone-derived pathfinding and automation engine for Minecraft. It
maintains the AltoClef-compatible APIs required by [TenorClef](https://github.com/vexrypt-rgb/TenorClef)
while tracking a modern Cabaletta Baritone base.

TenorClef decides what to do; Ostinato provides movement, mining, building,
inventory, and schematic processes to make it happen.

## Compatibility

| Minecraft | Branch | Java | Status |
| --- | --- | --- | --- |
| 1.21.11 | `main` | 21 | Primary target |
| 1.16.1 | `1.16.1` | branch-specific | Legacy target |

Ostinato `main` is intended for TenorClef's experimental 1.21.11 port. That port does
not currently compile, so it is not a supported release pairing. TenorClef 1.21.1 and
1.21 intentionally resolve matching Baritone artifacts rather than loading an
incompatible Ostinato 1.21.11 jar. See [TenorClef's wiring guide](https://github.com/vexrypt-rgb/TenorClef/blob/main/docs/OSTINATO_WIRING.md).

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

The first build may take some time: Unimined downloads and remaps Minecraft for the
enabled loaders (Fabric, Forge, NeoForge, and Tweaker). Build outputs are written to
`dist/`. To build the Fabric artifact used by TenorClef, run `:fabric:build`.

## TenorClef integration

For the legacy 1.16.1 integration, build the matching Ostinato branch first, then
build TenorClef from a sibling directory. Do not point a 1.21 or 1.21.1 TenorClef
build at an artifact produced from `main`.

Before shipping a 1.21.11 paired release, finish TenorClef's source port, tag and
publish the matching Fabric artifact under a pinned version coordinate, then make
TenorClef consume that coordinate.

## Movement backends

Ostinato's default pathing behavior is Baritone-compatible. On the primary modern
target, an optional Tungsten physics A* backend can handle goto/custom-goal travel;
mining, digging, schematics, and inventory remain on classic Ostinato processes.

| Setting | Values | Behavior |
| --- | --- | --- |
| `movementBackend` | `baritone` | Always use classic travel |
| `movementBackend` | `tungsten` | Prefer Tungsten; use Baritone if unavailable |
| `movementBackend` | `auto` | Use Tungsten when present, otherwise Baritone |

Install a compatible Tungsten Fabric jar beside Ostinato in the game instance, then
choose it with `#set movementBackend tungsten`. If Tungsten is absent, the current
fallback is Baritone. Backend selection should be visible in logs; treat unexpected
fallback as a configuration issue worth reporting.

## Development documentation

- [Porting and upstream notes](docs/PORTING.md)
- [Upstream Baritone documentation](README.baritone.md)
- [Features](FEATURES.md)
- [Setup](SETUP.md)
- [Usage](USAGE.md)

## License

Ostinato is licensed under LGPL-3.0 with upstream Baritone's anime exception. See
[LICENSE](LICENSE) and preserve all applicable notices when redistributing artifacts.
