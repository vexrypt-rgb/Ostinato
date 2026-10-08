Ostinato is a Baritone fork built for [TenorClef](https://github.com/vexrypt-rgb/TenorClef): AltoClef
integration hooks, a kinematic travel controller, and an encrypted multi-bot swarm link.

## Downloads

One set of jars per Minecraft version (`ostinato-mc<version>-*`). For a normal install use
`ostinato-mc<version>-unoptimized-fabric-*.jar` with Fabric Loader; `standalone` is obfuscated and
`api` keeps the public API for other mods.

| Minecraft | Branch | Java |
| --- | --- | --- |
| 1.21.4 | `main` | 21 |
| 26.3 | `26.3` | 25 |
| 1.21.11 | `1.21.11` | 21 |
| 1.16.1 | `1.16.1` | 8 |

## New in v1.18.0

- **Add-on messages on the swarm link**: `SwarmControl.registerHandler(type, handler)` lets another mod send
  its own message types over the same sealed, signed link and roster. [TenorClef](https://github.com/vexrypt-rgb/TenorClef)'s
  swarm (`@swarm`) now rides it instead of a separate transport. See `docs/guides/swarm-and-region-builds.md`.
- **User guides** (`docs/guides/`): getting started, commands, settings, movement, combat and clutch, mining and
  building, GUI/freecam/tasks, and swarm and region builds.
- **Combat**: the bot eats during a fight, wall clutch is calmer, and the ladder pillar is steadier.
- **Movement**: forced keys are released when a path finishes or fails; freecam applies landing and
  stuck-block effects.
- Live-tested over the swarm link with three clients: assignment, reassignment after a worker dies, cancel,
  `#swarm build` placing blocks, and signed (S2S) mode. The 1.16.1 and 26.3 builds of the hook compile but have not been run in a client.

## Highlights

- **Swarm link** (`#swarm`): sealed, signed chat messages between bots in a roster group (sigil S2S,
  Ed25519 per member), with `#swarm ping`, `status`, `reload`, `build` and `stop`. Settings `swarm*`,
  off by default (`swarmEnabled`).
- **Coordinated region builds** (`#swarm build <group> <file> [x y z]`): the group lead splits a
  schematic into one region per member (`strips`, `grid`, or bottom-up `layers`); every member builds
  only its own region. See `docs/REGION_BUILD.md`.
- **PvP** (`#pvp <name|players|hostiles>`): crits, W-taps, shield/axe play, bow, crossbow, cobwebs, potions,
  crystals, anchors, TNT carts, and every mace style, plus multi-opponent retargeting and automatic fight
  recording (`pvplogs/`, read with `tools/pvplog.py`).
- **Freecam enemy list**: toggle freecam with `freecamKey` (F8), middle-click a player to mark an enemy; anyone who
  hits the bot in freecam is added. Mobs only with `enemyMobs`.
- **Kinematic travel, parkour and swimming** improvements, pitfall avoidance.
- **Soprano-style movement** (ideas from [Soprano](https://github.com/AverWasTaken/soprano) by AverWasTaken; ladder clutch ported from it) found by simulation: ladder and vine jumps (`allowClimbJumps`), chained
  jumps through a pad, ladder clutch (`allowLadderClutch`, `pickupLadders`) and the
  `experimentalMovement` preset. Not yet tried in a live game.
- **Minecraft 26.3 now carries the full Ostinato feature set** (GUI, PvP, kinematic travel, boats, freecam, swarm). Not yet played in a world; `freecamGhostOpacity` has no effect there.
- **Stuck forward key fixed**: when a path finished or failed, the kinematic controller's last forced MOVE_FORWARD was never released, so W stayed held with nothing driving. Forced keys are now cleared when a path ends.
- **Freecam physics** (1.21.4 and 1.21.11; 1.16.1 already had it): the freecam camera now moves like a player: ice and slime slipperiness, slime bounce, soul sand, cobwebs, ladders and vines, swimming in water and lava, and a 0.6 step-up.
- **Per-version overlay system** (`versions/<mc>/`, `scripts/use-version.sh`): one base tree with a small set of
  files overridden per Minecraft version, with a CI matrix that builds each one.
- **Kinematic driving** now flies standing jumps and plain parkour itself (takeoff search), finishes each
  stretch end on its own, and swims along the surface with a breathing latch and bank hop-out.
- **26.3** support (unobfuscated Minecraft), merged from upstream Baritone 26.2.

## Notes

- Not every build has been tested in a live game; please report problems with the Minecraft version,
  the jar name and `latest.log`.
- Ostinato is LGPL-3.0, like Baritone. The 1.16.1 jars include BouncyCastle's Ed25519 classes (MIT).
