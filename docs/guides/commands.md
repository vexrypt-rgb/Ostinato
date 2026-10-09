# Command reference

Prefix `#`. Text below is taken from each command's built-in help; run `#help <command>` for the exact version in your build.

## Travel and goals
| Command | Effect |
| --- | --- |
| `goto <block>` / `<y>` / `<x> <z>` / `<x> <y> <z>` | go there; `~` is relative |
| `goal` / `goal <y>` / `<x> <z>` / `<x> <y> <z>` / `goal clear` | set or clear the goal without moving |
| `path` | start pathing to the current goal |
| `thisway <distance>` | goal that many blocks in the direction you look |
| `axis` | head for the nearest axis (X=0 or Z=0) |
| `invert` | run away from the current goal |
| `surface` / `top` | get out of caves/mines |
| `follow entities \| entity <names…> \| players \| player <names…>` | follow entities |
| `come` | head to the camera ([freecam](gui-freecam-tasks.md)) |
| `eta` | estimated time to next segment / goal (imprecise) |
| `elytra`, `elytra reset\|repack\|supported` | elytra flight to the goal (needs `elytraTermsAccepted`; see [Movement](movement.md#elytra)) |
| `blacklist` | blacklist the closest block you were trying to reach |

## Mining, building, farming
| Command | Effect |
| --- | --- |
| `mine <block…>` | search and mine blocks (see `legitMine` settings) |
| `tunnel` / `tunnel <height> <width> <depth>` | dig straight; default 1x2 |
| `farm [range [waypoint]]` | harvest mature crops and replant |
| `pickup [item…]` | collect dropped items |
| `explore [x z]` / `explorefilter <json> [invert]` | explore; filter marks chunks as explored |
| `find <block…>` | locate cached blocks |
| `build <file> [x y z]` | build a schematic file |
| `schematica`, `litematica [#]` | build the schematic open in that mod |
| `sel …` | WorldEdit-like selections (see [Mining and building](mining-and-building.md)) |
| `wp …` | waypoints (`list save info delete restore clear goal goto`) |

## Combat and groups
| Command | Effect |
| --- | --- |
| `pvp <name>\|players\|hostiles\|stats\|enemies\|clear` | see [Combat](combat-and-clutch.md) |
| `swarm ping\|status\|reload\|build\|stop` | see [Swarm](swarm-and-region-builds.md) |

## Interface and tasks
| Command | Effect |
| --- | --- |
| `freecam` | toggle the camera body |
| `tasks run\|pause\|resume\|stop\|list\|status` (alias `task`) | saved step lists, see [GUI](gui-freecam-tasks.md) |
| `click` | open the click GUI |

## Maintenance
`set …` (settings), `help`, `version`, `proc` (process state, for developers), `gc`, `render` (fix glitched
chunks), `repack` (re-cache nearby chunks), `reloadall`, `saveall` (world cache), `forcecancel`.

`set` forms: `set list [page]`, `set modified [page]`, `set <setting>`, `set <setting> <value>`,
`set toggle <setting>`, `set reset <setting>`, `set reset all`.

## Structures (`#structures`)

Ostinato finds structures two ways:

- **Client** (works on any server): counts signature blocks in the chunks you have loaded and matches them per variant (all village biomes, both mineshafts, every ruined portal, temples, monument, mansion, stronghold, ancient city, trial chambers, nether and end structures). Biome picks the variant for ruined portals, ocean ruins and shipwrecks.
- **Server** (singleplayer only): reads the exact structure starts. `#structures source server|both`.

Commands: `#structures [list] [type]`, `nearest [type]`, `goto [type]`, `types`, `source <client|server|both>`, `clear`, `on|off`. A type is a variant id (`village_desert`) or a family (`village`).

Known limits (client mode): buried treasure is server-only; an outpost or shipwreck inside the same 3x3 chunks as a mineshaft can be masked; the beached shipwreck and mountain portal variants may come back as the generic id. Calibration against a fresh world: 29 of 34 variants exact, 2 family-level, 3 missed.
