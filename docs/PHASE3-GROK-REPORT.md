# Phase 3 command center — remaining slices

The slices below were built in order, one branch each, stacked. Each branch starts from the previous slice, because these branches were not merged to `main`. The base is `swarm/p3-scaffolding` (the handoff commit), not `origin/main`.

No slice was skipped. `docs/PHASE3-GROK-QUESTIONS.md` was not written.

Live checks were not run. The request for this pass forbids launching Minecraft clients or servers, merging to `main`, editing `SWARM_DESIGN_DOC_v3.tsx`, and `gradlew --stop`. The test command for every slice was `scripts/center-app.ps1 test` (Gradle `--no-daemon`). The mod-wide `./gradlew test` suite was not run. Each run below printed `failed: 0` and the daemon stopped at the end of the build.

## Branches

| Slice | Branch | Commit | `center-app` tests |
|---|---|---|---|
| S1 tile details and focus | `phase3/tile-details` | `16f87eb89c09576b8b6e062ebbb8c064ad8fd03a` | 82, failed 0 |
| M1 war room map | `phase3/war-room-map` | `9c3cf73894625355fc66c0f6c1803ac3997aa465` | 96, failed 0 |
| M2 leases and observations | `phase3/leases-regions` | `1677106d63d2b86e7e9d3beb2ce6294ebd455462` | 104, failed 0 |
| M3 select bots | `phase3/select-bots` | `c444a0d9f4b04cec0500f01b51746ced0313c0b0` | 117, failed 0 |
| M4 area orders | `phase3/area-orders` | `f178f98b39d8e7d5c8ac3d9d0ec1a21eec1a54da` | 125, failed 0 |
| L1 layout remembered | `phase3/layout-remembered` | `01afba257f880bdf7da4549b5175f063bc32d4f5` | 130, failed 0 |
| T1 core themes | `phase3/core-themes` | `4f3409b64f9da7aa65904bf0b50805a5485d442c` | 137, failed 0 |

`phase3/core-themes` contains every slice above. Earlier branches contain only the slices up to their own commit.

## What each slice does

- S1. Focus panel on the wall (position, dimension, path, executions, leases, inventory, perf, link, age, gather, alerts). A tile click selects; a double-click opens focus. Despawn still asks for a second click.
- M1. War room map: terrain, markers, paths, dimension buttons, pan, zoom, and an in-map menu that centers on a bot. Default zoom is 4. The camera frames once, then stays where the user left it.
- M2. Lease rectangles, observation marks, a legend, key-lease lines, and independent toggles for leases, observations, paths, and terrain. Toggles default on.
- M3. Click, shift-click, and drag-box selection. Inspector cards use the same tile forms, one request per card. Control groups 1–9 are stored in `<centerDir>/prefs/<user>.properties`.
- M4. Area tool (off unless armed). One Send is one goto or one mine lease per selected bot. Preview is listed before Send, and every reply after.
- L1. The header toggle switches the card, writes `<centerDir>/prefs/layout-<user>.txt`, and sends `layout-wall` or `layout-map`. A changed file switches the card on the next refresh. A missing file keeps the current card. Garbage is the wall. With the feed on, `#center layout` also writes that shared file and still writes the old config file.
- T1. Header picker for Default dark, Light, High contrast, and Colorblind safe. A pick applies at once, writes `<centerDir>/prefs/theme-<user>.txt`, and sends `theme-pick`. A changed file switches the theme on the next refresh and keeps the layout, the wall focus, the map selection, zoom, pan, typed text, and the last footer reply. Screenshots: `theme-wall-*` and `theme-map-*` for all four.

## Plain choices

- No height slider on the map. Terrain is the surface overview, so Y is not a map axis. Area orders take Y as two text fields (median block Y of the selected bots, ±8).
- The right-click menu is the in-map panel `map:menu`, not a popup, so the window does not raise itself.
- Mine strips are equal shares along the longer edge. X wins a tie. This is not the occupancy-weighted cut in `SchematicPartitioner`. If any edge difference would be 64 or more, or the area is shorter than the selection, the whole plan is refused and nothing is sent.
- A goto spread includes only selected bots that have a position in the current dimension. A mine includes every selected name.
- `LayoutStore.file` and `ThemeStore.file` are unchanged (`command-center-layout-<user>.txt` and `command-center-theme-<user>.txt` in the config directory), so the in-game window keeps its paths. The standalone window reads and writes `prefs/layout-<user>.txt` and `prefs/theme-<user>.txt`.
- The window does not load `ThemeStore` (it uses Gson). Picker labels come from `CenterTheme.label`, and `ThemeStore.label` delegates to that. Status colors alias the existing tokens: ok, idle = muted, warn, bad = danger. The map marker color is the theme's 12-color identity palette. Colorblind safe uses its own palette, checked at ΔE ≥ 10 under simulated deuteranopia and protanopia. Custom theme JSON stays on the mod side; the window's four built-ins are the set it follows. An unknown name in the shared theme file is Default dark.
- When a layout or theme file exists, it wins over the process start flags. A missing file leaves the flag (or the wall, or Default dark) as it is.

## Unfinished

- Live rows for S1, M1, M2, M3, M4, L1, and T1 (client, server, `send.ps1`, window restart). Not run, by request.
- Mod-wide `./gradlew test` was not run. `CenterThemeTest` was extended for the identity-color check, but that class is outside the `center-app` run.
- Build-in-area is not in this pass. It waits on the schematic editor, and there is no dead button for it.
- Watch and Switch POV are Phase 4 and are not shown.
