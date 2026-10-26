# Underground maze mode

Backrooms-style maze mode for Ostinato. It is **off by default** (`mazeMode false`). While it is off, `#maze` refuses to run and no path cost, movement setting, or process behavior changes.

This document is the full wish-list. The foundation that landed with it is called out in [What landed](#what-landed) and [What is stubbed](#what-is-stubbed).

## Full vision

The maze is a place bots can keep extending under an otherwise untouched world.

- **Fully underground, and it expands forever.** Rooms keep getting added. There is no fixed bounding box and no planned end.
- **Never visible from caves or other structures.** A room that would touch air or fluid is not dug there. The shell stays sealed. Accidental openings get closed.
- **Many room types, not one repeating corridor.** Yellow offices, pipe and utility rooms, empty office hubs, and rare deep rooms. Themes blend softly where two cells meet, instead of a hard cut.
- **Rare surface entries.** Almost every entrance is underground. A surface opening is uncommon and deliberate, so the surface still looks like the world that was already there.
- **Stealth on multiplayer.** Digging and placing use a human-like cadence: a delay between actions, with jitter, so the bot is not clicking every tick.
- **Swarm.** Several bots share one maze. They do not dig the same cell twice. The layout is a pure function of the seed and the cell, so bots that have never met still build rooms that line up.
- **Loot, by theme and container.**
  - Yellow office: junk and flavor.
  - Pipes: redstone and utility.
  - Hubs: books, maps, emeralds.
  - Deep: rare books, diamonds, discs, sherds, lore books.
  - Chests are mixed or mid-tier. Barrels are bulk junk or food. Pots are a pottery sherd plus something small.
  - Rates stay low and come only from the seed. A container is never refilled.
- **Recycle what was dug.** Stone and other excavated blocks go back into walls, floors, and the loot, instead of only into a throwaway pile.
- **Maintenance.** An optional later pass reseals a shell that a cave-in or another bot opened.
- **Default off.** Normal pathing stays the pathing you already have.

## What landed

| Piece | Behavior |
| --- | --- |
| Settings | `mazeMode` (default **false**), `mazeSeed` (default `0`, which is a real seed), `mazeMinActionDelay`, `mazeMaxActionDelay`, `mazeActionJitter`, `mazeMapFile`. They show up in the settings screen under Building. |
| Shared map | `MazeMap` stores claimed cells and container positions in `mazeMapFile` (default `baritone/maze-map.txt`). The first claim wins. |
| Swarm hook | `MazeSwarmBus` is the interface a later transport can attach with `MazeProcess.attachBus`. The default bus drops messages. Nothing is sent over chat or a socket. |
| Seeded rooms | `MazeSeed.roomSeed` and `MazeTheme.forCell` depend only on `mazeSeed` and the cell. Same numbers, same room, on every bot. |
| One room | `#maze` or `#maze build` plans **one** room plus a short corridor stub, under the player. The stub is plugged with solid blocks. |
| Cave check | `ShellSeal` refuses the spot when air, fluid, or an unloaded block touches the shell, or when there are fewer than 4 solid blocks of cover above the ceiling. The planner shifts inside the cell (sideways, then deeper) and then tries nearby unclaimed cells. |
| Themes | Yellow office, pipes, and hub have their own block palettes. Deep uses the same shell with a deepslate palette and is marked stubbed. |
| Loot plan | `LootPlanner` rolls containers and item ids from the room seed. Chests, barrels, and pots follow the rates below. Positions already in the map are skipped. |
| Cadence | The process waits `StealthCadence.delayTicks` before it hands the schematic to the builder. The wait is stable for a given room seed. |
| Command | `#maze`, `#maze build`, `#maze plan`, `#maze status`, `#maze cancel`. |
| Tests | Seed stability, theme catalog, loot determinism and the no-refill ledger, and the seal / surface-cover helper. They do not start a world. |

Cell size is 32 blocks. The room is inset inside the cell so two neighboring cells cannot overlap. Each `#maze build` claims that one cell when the map file has been written, then the normal builder carves the schematic.

Approximate container rates (a roll of 0–99 on the room seed):

| Theme | None | One container | Two |
| --- | --- | --- | --- |
| Yellow office | 55 | 30 barrel, or 5 chest | 10 barrel + pot |
| Pipes | 50 | 30 chest, 13 barrel, 7 pot | — |
| Hub | 45 | 35 chest, 13 barrel | 7 chest + pot |
| Deep | 75 | 17 chest, 8 pot | — |

Yellow loot is paper, bread, string, and similar junk. Pipes roll redstone and rails. Hubs roll books, maps, and emeralds. Deep rolls diamonds, a music disc, sherds, and plain books. Counts on diamonds, emeralds, discs, and sherds stay at one.

## What is stubbed

These are named in the code or the settings, and deliberately not finished:

| Wish | Foundation stand-in |
| --- | --- |
| Expands forever on its own | One room per `#maze build`. There is no loop that keeps digging. Run the command again for the next open cell. |
| Full corridors between rooms | The stub runs toward +X for a few blocks and is **resealed**. Cells do not join up yet. |
| Soft theme blends at the edges | Every block in a cell uses that cell's theme. There is no blend. |
| Rare surface entries | The generator never opens a stair to the surface. If the cover check fails it shifts deeper or refuses. |
| Human cadence on every dig and place | The delay runs once, before the builder starts. The builder's own break and place speed is unchanged. |
| Live swarm | Bots share a map file if `mazeMapFile` points at the same path. `MazeSwarmBus` is not wired to swarm chat. |
| Items inside containers | The schematic places the chest, barrel, or decorated pot. The item list is written to the map. Nothing is inserted. |
| Lore books | Deep and hub "books" are the `book` item. Written books with pages are not generated. |
| Deep rooms of their own | `MazeTheme.DEEP` is stubbed. It uses the shared room shape and a deepslate palette. |
| Recycling excavated blocks | The builder uses the player's inventory and the usual throwaway blocks. Dug stone is not turned into the shell. |
| Maintenance pass | `ShellSeal.maintenanceTargets` lists blocks that break the seal. Nothing places them back. `#maze plan` reports a refusal; it does not repair. |
| Approach tunnel | The builder pathfinds to the schematic the usual way. That path can dig. The room shell itself is checked; the path to it is not a maze corridor. |

## Commands and settings

`#maze` does nothing useful until `mazeMode` is true:

```
#set mazeMode true
#set mazeSeed 12345
#maze plan
#maze build
```

`#maze plan` only prints the cell, theme, origin, and how many containers the seed would place. It does not claim the cell.

`mazeMinActionDelay` and `mazeMaxActionDelay` are in game ticks (20 ticks is one second). `mazeActionJitter` adds or subtracts that many ticks. A minimum above the maximum is clamped up. Negative jitter is treated as zero.

`mazeMapFile` is relative to the `baritone` directory unless it is absolute. The first line of the file is `ostinato-maze-map 1`.

## Layout of the code

Pure planning, with no Minecraft types, lives in `src/main/java/baritone/maze/`:

- `MazeSeed`, `MazeTheme`, `MazePlanner`, `RoomPlan` — cell, theme, and the one room
- `ShellSeal`, `BlockProbe` — sealed shell and surface cover
- `LootPlanner` — theme tables and the no-refill rule
- `MazeMap`, `MazeSwarmBus` — file and the bus hook
- `StealthCadence` — the tick delay

`MazeWorldProbe` and `MazeSchematic` are the only maze types that touch Minecraft. `MazeProcess` stays inactive unless `mazeMode` is on and a build is waiting. It does not write pathing settings.

Tests are in `src/test/java/baritone/maze/`.
