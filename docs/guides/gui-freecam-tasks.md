# Screen, freecam and task lists

## The Ostinato screen
Press `guiKeybind` (default Right Ctrl) to open it. It has tabs including **Tasks**. `renderPathHud` shows a path
HUD, placed by `pathHudAnchor` (default RIGHT). `#click` opens the click GUI.

## Freecam (`#freecam`)
Toggles a camera body with player physics (double-tap jump to fly). The key is `freecamKey` (default F8),
`freecamSpeed` defaults to 1 and `freecamGhostOpacity` to 0.35.
- Left click an entity: follow it. Left click a block: travel to it.
- Right click: travel to the camera position (`#come` does the same from chat).
- Middle click: mark an enemy for [`#pvp`](combat-and-clutch.md). Marking mobs requires `enemyMobs`, default false.

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
