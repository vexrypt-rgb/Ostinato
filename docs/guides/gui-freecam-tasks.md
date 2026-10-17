# Screen, freecam and task lists

## The Ostinato screen
Press `guiKeybind` (default Right Ctrl) to open it. It has tabs including **Tasks**. `renderPathHud` shows a path
HUD, placed by `pathHudAnchor` (default RIGHT). `#click` opens the click GUI.

## Freecam (`#freecam`)
Toggles a camera body that walks, swims, and slime-jumps with the game's own movement. The key is `freecamKey`
(default F8) and `freecamSpeed` defaults to 1. Double-tap jump to fly: flight phases the camera through blocks. The bot
is never affected: it keeps its own flight and collision. Double-tap again to land. `freecamGhostOpacity`
(0.35) is ignored on 26.3, where the body is drawn solid.
- The hotbar, hearts, and the first-person arm stay on screen. F5 shows the bot's body, with its hotbar (and any
  open container) drawn above its head. Container screens the bot opens do not cover the view; the survival and
  creative inventories still draw.
- Left click an entity: follow it. Left click a block: travel to it.
- Right click: travel to the camera position (`#come` does the same from chat).
- Middle click: mark an enemy for [`#pvp`](combat-and-clutch.md). Marking mobs requires `enemyMobs`, default false.
- `#freecam spar`: a client-only sparring ghost that uses the bot's melee timing. Hits change a local health count only.

### Building in freecam
- `#freecam flat`: a 32×16×32 client-only superflat pad under the camera. The bot keeps its task.
- `#freecam edit`: copy the selection, or 16×12×16 around the build you look at, and edit the copy. Added blocks are
  textured ghosts, removed blocks are red outlines.
- Nothing reaches the world until `#freecam apply` (build now, keep editing), `#freecam done` (build and close), or
  `#freecam live` (build each edit as you make it; this takes the builder). Flat apply places only added blocks;
  edit apply also breaks removed ones.
- Tools (`#freecam tool <name>`, shared with the Studio): brush, select, fill, replace, line, sphere, hollow, wall,
  copy, paste, rotate, flip. `#freecam brush <block>` locks the brush, `brush hand` follows the held block again,
  middle click picks a block, `#freecam radius <1-8>` sizes spheres, `#freecam undo` / `clear`.
- Clicks on a canvas never send the bot anywhere. 26.3 only.

## Task lists (`#tasks`)
Build a list in the Tasks tab; lists are saved in `baritone/tasks/<name>.json`.

| Command | Effect |
| --- | --- |
| `#tasks run <name>` | load and run a saved list |
| `#tasks run` | run the list open in the Tasks tab |
| `#tasks pause` / `resume` / `stop` | control it |
| `#tasks list` | saved lists |
| `#tasks status` | the running step |

Step types: `GOTO`, `MINE`, `FOLLOW`, `FARM`, `EXPLORE`, `GET_TO_BLOCK`, `BUILD`, `WAIT`, `SET`.
