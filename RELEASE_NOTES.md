Ostinato is a Baritone fork built for [TenorClef](https://github.com/vexrypt-rgb/TenorClef): AltoClef
integration hooks, a kinematic travel controller, and an encrypted multi-bot swarm link.

## Downloads

One set of jars per Minecraft version (`ostinato-mc<version>-*`). For a normal install use
`ostinato-mc<version>-unoptimized-fabric-*.jar` with Fabric Loader; `standalone` is obfuscated and
`api` keeps the public API for other mods.

v1.22.0 adds the menu, the path view, the parkour work, and the zero-cycle geometry on 26.3 (`main`, Java 25).
The 1.21.11 jars, and the Conductor module jar, are that line's autotune, bastions, and command guard, which
had not been tagged before. They do not include the menu, the path view, the parkour work, or the zero-cycle
geometry. The 1.21.4 and 1.16.1 jars are the same lines as v1.20.0.

| Minecraft | Branch | Java | This tag |
| --- | --- | --- | --- |
| 26.3 | `main` | 25 | New. `ostinato-mc26.3-*-fabric-1.22.0.jar` |
| 1.21.11 | `1.21.11` | 21 | Autotune, bastions, and the command guard. `*-fabric-1.21.0.jar` |
| 1.21.11, Conductor module edition (`ostinato-mc1.21.11-module-unoptimized-*`) | `conductor-module` | 21 | The 1.21.11 work, built as a Conductor module |
| 1.21.4 | `1.21.4` | 21 | Unchanged since v1.20.0 |
| 1.16.1 | `1.16.1` | 8 | Unchanged since v1.20.0 |

## New in v1.22.0

`main` is the Minecraft 26.3 line (Java 25). 1.21.11 stays on branch `1.21.11`. Pictures of the menu and of the
path view are in the README. The menu, the path view, the parkour work, and the zero-cycle geometry below are
26.3 only.

- **Menu.** Right Control opens it (`guiKeybind`). Settings stay, with search. Studio is a schematic editor:
  drag orbits, shift-drag or middle-drag pans, scroll zooms, left click places, right click erases, Ctrl+Z
  undoes, Ctrl+S saves. The volume starts at 24×12×24 and stops at 32 on a side. Saves are
  `schematics/<name>.ostinato`. Build starts the builder at your feet and leaves the menu open. Arcade adds
  Slide, Mines, and Motif. The menu was opened in a world for the README pictures. The builder was not watched
  placing that house into the world. The orbit view draws the blocks; a frame drawn after the raster had been
  painting over them.
- **Path view.** While kinematic or physics travel drives, the plan is drawn in the world and Baritone's path
  ribbon is hidden. Kinematic: corridor, rollout fan, jump marks. Physics: speed trail (blue slow, red fast),
  input marks, and the search with a purple replan diamond. Six settings, on by default:
  `renderKinCorridor`, `renderKinRollouts`, `renderKinJumps`, `renderPhysSpeedTrail`, `renderPhysInputs`,
  `renderPhysSearch`. The README pictures are from a dev client on a parkour course.
- **Parkour and pathfinding.** The search prices surfaces, doors, falls, and mobs while it runs, reuses a route
  when the goal moves, and slews a kinematic takeoff toward the plan before it snaps. Knight-move corridors and
  each move's cost scale are taken once per search. A 2-by-1 refuses a door, a gate, or a climb. A standstill
  jump the yaw grid cannot fly gets one beam search, 2500 by 22. An overhanging landing steps back onto the
  block. Honey slabs and honey carpet can still have no path. An aligned 3-wall neo from a fast straight
  approach can still fail. The separate TAS jump search is not called.
- **Zero-cycle geometry.** The dragon's 24 path nodes, the pillar ring, the bed rig, and the end crystal one
  block above its pillar. On a flat podium the common holding target is the back diagonal and the rare one is
  the south straight. The perch roll is one chance in the living crystals plus three, and it happens after a
  path finishes. Nothing in this release places the bed, and a player has not stood on the rig.
- **Soprano.** A* search speedups (open-set heap, cell node map, mining-cost memo, favoring cache) and the boat
  steering controller, ported from [Soprano](https://github.com/AverWasTaken/soprano) by AverWasTaken. See
  CREDITS.md.
- **Autotune Combat System** (26.3 and 1.21.11; `#pvp`, setting `pvpAutotune`, off by default): learns the combat timing numbers and
  the healing, totem and reserve numbers on its own, one at a time, against the baseline, keeping a change only
  when it does better and never when the damage-taken guard is breached. `#pvp tune` shows and sets them; the HUD
  card shows what it is trying. Gear is picked by wear as well as tier, and spares replace worn pieces before they
  break. Learned values are not claimed as improvements: the learners have run live against one bot only.
- **Bastions** (26.3 and 1.21.11; `#bastion`, `#structures`): bastion layouts recognised from loaded blocks; fights with brutes from
  behind walls, towers and slits; piglin bartering camps; lava and fire recovery; water-pocket escapes; and a
  lava-flow forecast priced into paths.
- **Command execution guard** (API, 26.3 and 1.21.11): `ICommandManager.setExecutionGuard` lets another mod be
  asked before any command runs, from chat, the GUI, or code. With no guard set, nothing changes. The Conductor
  module edition is the separate `conductor-module` jar. It is not inside the plain 26.3 jar or the plain 1.21.11 jar.

Autotune, bastions, and the command guard are on 26.3 and on 1.21.11. The other bullets in this section are 26.3 only.

## New in v1.20.0

- **Nether portal at a lava pool** (all versions): `#portal build` makes a portal out of a lava pool with a
  water bucket, an empty bucket and flint and steel, the way speedrunners do. It picks a shore where four
  lava sources lie against solid ground (any side, mirrored where only one hand has room), sets the base
  with water, raises the two shapes, casts the frame from the pool with the bucket, lights it and refills
  the bucket. `#portal build enter` then walks in; `#portal find` only lists the shores; `#portal status`
  and `#portal cancel` do what they say. Each step waits for the blocks it expects and digs out cobblestone
  that set where obsidian should be. About 20 seconds with a pickaxe, about 30 without; a one-deep pool
  needs the pickaxe.
- **Humanized look on 1.21.4 and 1.16.1**: the hand-like aim the other lines already had (limited
  acceleration, whole mouse steps) is there too, and the portal builder aims with it on every line.
- Built in the dev client on every line: test pools in a flat world everywhere, natural lakes on 1.16.1 and
  1.21.11. Walking into the portal (`enter`) and the recovery from a wrong flow were not played.

## New in v1.19.2

A second review pass. Mostly fixes; the kinematic simulation learns a few blocks and potion effects.

- **Combat** (1.21.11, 26.3): looking up an item no longer moves it. Items are pulled into the hotbar only when
  they are about to be held, so a fight that carries more than nine useful things no longer shuffles them in
  and out with the inventory screen flashing. An inventory screen the fight opened and then had no use for is
  closed again; one the player opened is left alone. Digging through to a walled-off opponent respects `allowBreak`.
- **Kinematic simulation** (all versions): soul sand and honey slow the simulated walk as they do the real
  one, honey halves the jump and slows a fall down its side, and Speed, Slowness, Jump Boost and Soul Speed
  are followed. The planner's and the driver's views of the world answer the same questions. Under Levitation
  or Slow Falling the look-ahead drivers stand down and no gap jumps are planned; under Slowness the longer
  jumps (four-block gaps, neos, climb and momentum jumps) are left out.
- **Kinematic fixes** (1.21.11, 26.3): the mid-air extension check judged every jump against the first
  destination it was ever asked about, and the parkour landing check never dropped its cached collision boxes.
- **Pathing**: a look-ahead mover that holds one path position too long steps aside for classic movement on
  1.21.11 and 26.3 as well (physics and kinematic drivers only). The physics mover leaves digging, bridging and
  drops deeper than three blocks to Baritone. A drop into shallow water next to deep water costs more instead
  of being refused, which left no way down under some cliffs.
- **`maxPathLengthBlocks`** (all versions; ported from xiaoka6666/baritone by way of the 26.3 line): throws
  away a path longer than this many positions and leaves that goal alone for five seconds. Off by default
  (`0`), because it also refuses long routes that are fine. On 26.3, where it was added at 150, it is now off
  as well.
- **Lanterns**: a lantern standing on the ground can be walked on, as the low floor it is (no pillaring from
  it, no jump from it onto a full block).
- **GUI**: the reset button says what it will reset, and the task panel no longer lists the task folder on
  every frame.
- The fault event file stops growing at 4 MB.
- Checked by unit tests and builds on every line, not played live.

## New in v1.19.1

A review pass over the fork's own code. Fixes only, no new features.

- **Pathing**: the planner and the executor now give the same answer for cells an add-on (TenorClef) asks the bot
  to keep out of or vouches for as footing, so a path no longer runs into a cell the executor refuses and then
  tries to mine air. Jump, chain-jump, climb-jump, slime and parkour movements start afresh when the executor
  runs them again after a setback. The swimming and failed-jump lookups the planner repeats at every node are cached.
- **Look-ahead movers** (1.21.4): a physics or kinematic mover that holds one path position for too long now steps
  aside for classic movement instead of circling with every timeout switched off.
- **Tungsten backend**: retargeting waits for the cancelled search to wind down instead of falling back to
  classic pathing on every new goal.
- **Combat**: checking whether an item is carried no longer moves it into the hotbar; the totem keeps the offhand
  while it is what keeps the bot alive; timers survive a respawn or dimension change (fights, boats, boat clutch);
  spectators are not targeted. PvP on 1.21.4 digs through to an opponent that is right there but walled off.
- **PvE**: a mob that despawns is no longer counted as a kill, marked mobs are fought alongside the filter, and the
  per-mob bookkeeping is pruned.
- **Swarm**: a build stop names its job, so a late stop cannot end the next job; an S2S endpoint refuses unsigned
  tokens; two signet records naming one member with different keys are refused; the spool transport no longer
  loses lines when a file is briefly held open; a settings change restarts the link.
- **GUI**: an open dropdown is closed when the list is rebuilt, a letter bound to the screen can be typed into
  the search box, and the version chip shows the game version.
- **Air process**: an air pocket found in one world is not carried into the next.
- 1.21.11 and 26.3 receive all of this except the look-ahead mover and PvP changes (their executor and combat
  code differ); 1.16.1 receives the swarm fixes. Checked by unit tests and builds on every line, not played live.

## New in v1.19.0

- **PvE combat** (`#pve [hostiles|<mob id>|stats|clear]`): mobs now have their own combat process instead of
  borrowing the player-fight one. Each mob gets a profile (melee, ranged, bomb, avoid) and the bot picks tactics
  from it: charged swings with back-off during recharge, a shield guard against hard hitters, a weaving charge at
  skeletons and other shooters, creeper clearance, eating only when no shooter has line of sight, ignoring mobs it
  cannot reach (burrowed silverfish), and running from Wardens, Guardians, Withers, Ender Dragons, Evokers and Ravagers.
- `#pvp hostiles` and freecam mob enemies now route to `#pve`; `#pvp` is players only.
- Trained live against about 30 mob scenarios on hard (single mobs, mixed groups, hordes, weak kit). The 1.21.4 and
  26.3 builds compile but have not been played yet.

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
