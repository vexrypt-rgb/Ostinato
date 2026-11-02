# Handoff: maze.backrooms forever-expand (Ostinato + TenorClef)

Written 2026-10-29 by the previous Claude chat. You now own this. Read the whole file before acting.

## The goal

The owner wants the **maze.backrooms forever-expand** harness (`-Dostinato.mazetest.rooms=0`) to keep building rooms
one after another in survival, with the bot **farming its own materials** (wood, cane/paper, stone, picks) instead of
running dry. Each room is: Baritone digs it, then TenorClef furnishes it (LIGHT → CONTAINER → FILL → SEAL), and the harness
verifies it strictly (every planned cell must be right). Then the bot walks to the next cell and repeats.

Fix the bot, not the task: make capability fixes that last, not one-off hacks. Judge every change by a live client run.

## Owner rules (must follow)

- **Leave Gradle daemons 6880 and 18564 alone.** Never run `gradlew --stop`. Kill only java pids you started.
- One client at a time. Never double-launch. The client goes on the 2nd monitor, never steals focus, fullbright (the launcher handles this).
- Another chat owns **#allblocks**: don't touch it. **Tunnels are paused.**
- Prefer copper tools/armor (copper → iron → stone).
- Mark rewritten Baritone/AltoClef code with an `// Ostinato:` comment naming the run that motivated it (house style; see the existing code).
- Commit locally freely; don't ask before small improvements. Push TenorClef `main` after each change. Push the Ostinato feature branch.
- **Report style, short:** root cause → fix → commit hashes → jar swapped? → relaunch pid / early log proof lines. Give frequent short progress updates.
- Read logs compactly (grep for `MAZETEST` lines). Never dump raw logs; the owner is saving credits.
- Append a line to `Ostinato-maze-survival\GROK_BUILD_HANDOFF.txt` on each launch ("Claude owns it; do NOT launch another client").

## Repos

| Repo | Path | Branch | State |
|---|---|---|---|
| Ostinato (Baritone fork, Mojang names) | `C:\Users\redfa\Documents\MinecraftDev\Ostinato-maze-survival` (a git worktree) | `maze-backrooms-expand` (pushed, 18 commits ahead of origin/main, **not merged**) | clean except untracked scratch files |
| TenorClef (AltoClef fork, 1.21.1 yarn names in `src/`) | `C:\Users\redfa\Documents\MinecraftDev\TenorClef-main` | `main` (pushed, HEAD 3797d158) | clean except build logs |

Key files:
- Harness: `Ostinato-maze-survival/src/main/java/baritone/maze/MazeTest.java`. It holds the room loop, `nextRoom` (expand), `walkTick` (state 30), `provisionTick` (state 31), `furnishTick`/verify, failStreak and FOREVER.
- Room planner: `src/main/java/baritone/process/MazeProcess.java` (`buildHere`/`prepare`) and `baritone/maze/MazePlanner.java` (`placeOpen` spiral search).
- Furnish: `TenorClef-main/src/main/java/adris/altoclef/maze/MazeFurnishTask.java`. Gather loop, kit, leash/home, surfacing, the step loop with defer/secondPass, retry-before-seal, repair sweep, `isFinished`.
- Ceiling placing: `TenorClef-main/src/main/java/adris/altoclef/maze/MazeCeilingPlaceTask.java`.
- Farms: `tasks/resources/FarmSugarCaneTask.java` (catalogue `sugar_cane`) and `tasks/resources/ReplantTreesTask.java`.

## Build, swap, launch (exact)

1. **Stop the client first** (memory is tight, ~24 GB commit):
   ```powershell
   Get-CimInstance Win32_Process -Filter "name='powershell.exe'" | ? { $_.CommandLine -match 'maze-survival-launch' } | % { Stop-Process -Id $_.ProcessId -Force }
   Get-CimInstance Win32_Process -Filter "name='java.exe'" | ? { $_.ProcessId -notin 6880,18564 } | Stop-Process -Force
   ```
   Archive the log first: `copy proof\complete-mazetest.log proof\runNN-<what-happened>.log`.
2. **Build TenorClef** (only the 26.3 module is needed):
   ```powershell
   cd C:\Users\redfa\Documents\MinecraftDev\TenorClef-main
   $env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
   & .\gradlew.bat "-Dorg.gradle.jvmargs=-Xmx1536M" :26.3:build -x test --max-workers=2 *> tc-build-runNN.log
   ```
   The `IdeaWin64.dll UnsatisfiedLinkError` line is harmless. Look for `BUILD SUCCESSFUL` or `error:`.
3. **Swap the jar:** copy `versions\26.3\build\libs\altoclef-26.3-0.27.0.jar` over
   `Ostinato-maze-survival\fabric\run\client\mods\altoclef-26.3-0.26.2.jar` (keep the old file name).
4. Kill the build daemons you just created (the new jdk-21 and jdk-25 java pids, never 6880/18564).
5. **Launch** (Ostinato compiles from source inside runClient; no separate build is needed):
   ```powershell
   cd C:\Users\redfa\Documents\MinecraftDev\Ostinato-maze-survival
   Start-Process powershell -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','maze-survival-launch.ps1','-extra','-Dostinato.mazetest.rooms=0' -WindowStyle Minimized -PassThru
   ```
   The log goes to `proof\complete-mazetest.log`. Each launch is a fresh world (seed 71006). The furnish cap is 108000 ticks and the dig cap is 12000.
6. **Watch with a line-buffered grep**, never `cut` (it buffers):
   ```bash
   tail -n 0 -F proof/complete-mazetest.log | grep -a --line-buffered -oE "MAZETEST (build ok|FURNISH (handoff|replant-trees|repair-[a-z]+|retry-before-seal|surface-stalled|regather-[a-z]+|loot-skip)|FARM|EXPAND (room|walked|walk )|PROVISION stage|ROOM-RESULT|RESULT|STUCK-NOW furnish progress flat (40|60|80)min).{0,160}"
   ```
   For progress: `grep -a -oE "MAZETEST FURNISH progress.{0,90}" proof/complete-mazetest.log | tail -n1`.

Gotchas:
- In the 26.3 module, `BlockState.isIn(TagKey)` and `FluidState.isIn` don't exist. Compare `block.asItem()` against ItemHelper item lists, and use `Blocks.WATER`.
- `@Override` on new accessors can fail with "does not override".
- The TenorClef command bridge is reached by reflection from MazeTest: `adris.altoclef.AltoClef.getCommandExecutor().execute("@get ...")`. To cancel, use `AltoClef.getInstance().getUserTaskChain().cancel(mod)`. There is no `@stop` command.

## What's proven live (don't re-fix)

| Feature | Proof |
|---|---|
| Walk expand (no tp) | run91–94: next cell reached in about 800–1130 ticks |
| Inventory kept across rooms; kit only on room 1 | run92 |
| Pick provisioning before later digs | run92/93: durability 18 → 280 and 137 → 399 in about 120 ticks |
| Wood provisioning stage (`@get log`) | run93: 0 → 48 planks, then stalled on an unreachable log; the 2-min no-gain cutoff was added, **not yet live** |
| Tree replant after chopping | planted 2, 2, 3 (once 0) |
| Loot gathered at FILL, not before lights | run92 |
| Ceiling equip fix, retry deferred lights before the seal | run92 |
| Repair sweep runs all 3 rounds | run93 |
| Widening gather leash (+32 per trip) | run92 |
| Surfacing cutoff (60s with no height gain) | run93 (fired correctly) |
| **Full room PASSes** | run92: room 1 yellow_office PASS, room 2 pipes PASS (108k ticks). Room 3 FAIL, 3 cells short |

## Shipped but not yet seen live (verify first)

1. **TenorClef 15595347: repair reopens a finished seal.** In run93 room 1, 4 lights were still wrong after the seal. The bot was outside a room enclosed by protected cells, walking in searched 1M+ nodes, and all 3 rounds skipped them. Now a repair round with only interior cells wrong unprotects the seal cells (`repairOpen`). The bot can dig back in, and the SEAL steps re-place the seal (`repair-reseal`). Look for `repair-open-seal`, then a PASS. Risk: Baritone might dig in somewhere other than the seal shaft, or the reseal could leave the bot sealed inside the room.
2. **TenorClef 3797d158: bounded regather of a skipped item.** In run94 the barrel was skipped by the pre-gather budget. The CONTAINER step's regather and the gather loop then flipped `gathered` every tick: log spam plus an unbounded barrel hunt with a 282-block path. Now a skipped item gets one 3-min regather (`regather-skipped`, then `regather-giveup`).
3. **Ostinato 7831928b: provisioning no-gain cutoff** (2 min). This is part of the wood stage from 162344a2.

**Last run (run94) did not finish.** The client window was closed at 22:56 (a clean disconnect, not a crash) during room 1 at light 110/133, in the barrel loop above. Nothing is running now. The jar with all TenorClef fixes is already swapped in. **Next step: launch run95 (rooms=0) and watch for items 1–3.**

## Open problems (in priority order)

1. **Why the barrel gets skipped at all.** The pre-gather gives "woody extras" (barrel/chest/bowl) a 6-min budget. In run94 room 1 it ran out even though the bot had cladding wood. Find out why the barrel gather is slow; likely crafting-table, slab or planks contention, or a far hunt. Without a barrel, CONTAINER and FILL can't pass and the room fails.
2. **The sugar cane farm has never triggered live.** No cane has been near any room, and paper gets skipped. `FarmSugarCaneTask` harvests above the base and replants beside water (`MAZETEST FARM cane planted`), but only if cane is found. Consider whether paper/loot should be fully optional, or should seed a farm on the first cane found, or plant near water by the room.
3. **Wood supply across rooms.** Provisioning tops up to 64 planks-equivalent, but `@get log` can lock onto unreachable logs (1M-node searches). Replanted saplings are never harvested later. A real "home wood farm" or a reachable-tree filter would be the lasting fix.
4. **Room 1 starts with no wood underground** (run93: "Surfacing for stick", planks=0 logs=0). The room-1 kit appears to carry no wood (check `setup` in MazeTest). Consider giving the kit a few logs, or putting wood first in the order.
5. **Ceiling or wall cells that stay stuck from a valid stand** (run93: `-87,74,-150` "Placing ceiling" stuck from a side stand inside the room; run92: ceiling-unreachable-stand). Most rooms lose 1–4 cells to this before repair.
6. **The next-room cell isn't the built cell.** `nextRoom` picks a theme for the target cell, but `MazeProcess.prepare` → `MazePlanner.placeOpen` spirals to the first open cell, so a different cell and theme gets built (target -5,1 hub, built -6,0 yellow_office). Fix: dry-run `place()` for candidates in `nextRoom` before walking, or pass the chosen cell into `buildHere`.
7. **A dig timeout (`FAIL timeout`) ends a forever run directly**, ignoring failStreak (only verify fails get the second chance).
8. Furnish is slow: 50k–165k ticks per room (40 min to over 2 h), mostly gathering (andesite x79 + polished x53 for pipes, diorite for yellow_office).

## Run history (archived logs in `Ostinato-maze-survival\proof\`)

- `run90-…` 131/133: holes after the seal, equip fixed.
- `run91-…` room 1 131/133; the walk worked; room 2's dig timed out hand-mining with no picks (led to pick provisioning).
- `run92-rooms12pass-room3fail.log`: the best so far. 2 PASS, then room 3 FAIL because repair stopped after 1 round (fixed in e328cb1f).
- `run93-room1fail-sealed-repair.log`: 4 lights unreachable after the seal (fixed in 15595347). The wood stage was proven here.
- run94: not archived separately. `proof\complete-mazetest.log` currently holds it (barrel loop, window closed).

## Commits this effort

TenorClef (main, pushed): 56188609 (cane/tree farm, loot at FILL, leash) · 78285755 (ceiling equip) · 98b2c541
(retry before seal, stand timeout) · 33ef88f7 (loot stall recovery, ceiling task cache) · e328cb1f (surfacing cutoff, 3
repair rounds) · 15595347 (reopen seal) · 3797d158 (bounded skipped regather).

Ostinato (`maze-backrooms-expand`, pushed, not merged to main; release is by `v*` tag, so never merge or tag without the
owner): 0b682eaa (walk expand, kit room 1 only) · dd9c0060 (forever continues past one FAIL) · 63fa9860 / 17a0623d (pick
provisioning, cap cancels TenorClef) · 162344a2 (wood provisioning) · 7831928b (no-gain cutoff).

## 2026-11-02: connected-mode room variants (Claude, generator slice)

No client launched. Ostinato commits (local, not pushed): `e721f3016` (furnisher busy tri-state + lock-free AltoClef
avoider reads, the earlier uncommitted diff), `f3ade7e60` (room variants), plus a docs/handoff commit.

- `RoomVariant` (new): 11 picked variants + `classic`. Sizes 5x5 to 13x13, halls 13x5/5x13, L office, lobby 11x9x11
  (6 clear), poolroom (3x3 pool), maintenance 6x8x11 (ceiling pipes, 4 clear under), warehouse 13x9x13 (pillars).
  Table and gap list: `docs/maze/README.md` "Room variants".
- New themes appended to `MazeTheme` (ordinals of the old four unchanged): `mono_yellow`, `poolrooms`,
  `maintenance`, `warehouse`, `lobby`. `MazeTheme.forCell` is unchanged, so the backrooms grid is unchanged.
- `ConnectedPlan`: `Room` carries a variant; map line gets an optional 6th field (old maps load as `classic`, same
  geometry: there is a fingerprint test). Tunnel runs wall-to-wall of the two footprints; rise cap = run - 2.
  An L's cut corner is checked as world by `breaches`. Soft blend near the parent door (60/30/15%).
- New `BlockRole.WATER` (pool cells). Survival clads them as diorite unless `-Dostinato.maze.water=true`.
- Log line: `MAZE ROOM variant=<id> theme=<id> size=WxHxD cell=x,z phase=planned|dig` (stdout, so it lands in
  `proof\complete-mazetest.log`; grep `MAZE ROOM`).
- Tests: `ConnectedVariantTest` (11). Full `gradlew test` green.

For TenorClef (Grok's side): five new theme ids reach `MazeFurnishJob.theme`; anything keyed on theme ids needs a
default. New clad ids: diorite/polished_diorite/calcite, tuff, birch/spruce planks. No `water` step unless the flag
is set. Rooms are larger: an 13x13 warehouse is roughly 3x the cladding of a classic room, so expect longer furnishes.
Not done: farming room (needs water + tilling in the furnisher), interior props.

## 2026-11-02: TenorClef survival palette (Grok Build, furnisher slice)

No client launched. Jar not swapped. TenorClef `main` commit `761a9dd3952fa57883bb56219c99d51f98a8aa2b` (local, not pushed). Jar built by `:26.3:build` with tests (347 tests, 0 failures): `C:\Users\redfa\Documents\MinecraftDev\TenorClef-main\versions\26.3\build\libs\altoclef-26.3-0.27.0.jar`. Do not copy it into mods. The coordinator does the proof run. No Ostinato Java was edited in this slice.

Claude's palettes should name ids from the list below. A step's `blockIds` are Minecraft path ids, best first; the furnisher gathers and places the first one it can obtain. Room size is the `airCells` box. Nothing in TenorClef assumes 7x7. Footprints from 5x5 through 13x5 and 11x11, clear height 4 to 6, are accepted. Log: `MAZETEST FURNISH room-bounds span=XxZ clear=H`.

### Place these

Whites, best first: `polished_diorite`, `diorite`, `calcite`. Polished diorite is a 2x2 craft from diorite the dig already finds. Calcite is real overworld stone but it generates in geodes, so list it after the diorite ids.

Also: `polished_andesite`, `andesite`, `polished_granite`, `granite`, `tuff`, `cobblestone`, `stone`, `sandstone`, `dirt`, `sand`. `smooth_stone` smelts and is slow; do not clad a whole room with it.

Wood: `oak_planks`, `spruce_planks`, `birch_planks`, `jungle_planks`, `acacia_planks`, `dark_oak_planks`, `mangrove_planks`, `cherry_planks`, `stripped_oak_log`, `stripped_spruce_log`, `stripped_birch_log`, `stripped_jungle_log`, `stripped_acacia_log`, `stripped_dark_oak_log`, `stripped_mangrove_log`, `stripped_cherry_log`, `barrel`, `chest`.

Maintenance: `iron_bars`, `chain`, `lantern`, `lightning_rod`, `cut_copper`, `cut_copper_slab`, `cut_copper_stairs`. `copper_block` costs 9 ingots; use it as an accent, not a wall.

Pool: `water`. The gather asks for one `water_bucket`. The bot places a source in each cell that is still dry, refilling the bucket, and treats flowing water as done.

Farm: `farmland`, `wheat`, `sugar_cane`.

Light: `torch`. Containers: `barrel`, `chest`.

### Do not name these

If a step lists them, they are dropped and `polished_diorite`, `diorite`, `calcite` are appended (calcite stays behind the diorite ids). Quartz is a real catalogue task and it forces the Nether. Prismarine and sea lanterns are monument loot. White concrete is a dye factory.

`quartz`, `quartz_block`, `smooth_quartz`, `quartz_bricks`, `quartz_pillar`, `chiseled_quartz_block`, `quartz_slab`, `quartz_stairs`, `smooth_quartz_slab`, `smooth_quartz_stairs`, `white_concrete`, `white_concrete_powder`, `prismarine`, `prismarine_bricks`, `dark_prismarine`, `prismarine_slab`, `prismarine_stairs`, `prismarine_wall`, `sea_lantern`.

`yellow_concrete` is not rewritten. It is not in the catalogue, so an older yellow-office list still falls through to terracotta or planks. Nether woods are not supported.

### How to write the steps

- `water`, `wheat`, and `sugar_cane` are LIGHT steps on the air cell. `farmland` is a LIGHT step on the soil cell.
- Put water steps before sugar cane. Cane is planted by clicking the top of the dirt or sand under the air cell, and that soil needs water beside it.
- Put farmland steps before wheat. A wheat step above dirt hoes, then plants `wheat_seeds`. A wheat step above stone does not place dirt; send a farmland step for that soil (stone gets dirt, then a hoe).
- Clear height 6 is in reach. The click measures the nearest point on the block (vanilla 4.5) and the stand search goes 8 blocks down. The horizontal ring stays at ±4, which is reach, not room width.
- A decorated pot can be placed as a block. Filling it is still unsupported.
- Kit order is copper, then iron already in hand, then stone. A missing tool does not start an iron hunt. One copper-tool attempt per client session (3600 ticks), then stone (`MAZETEST FURNISH skip-copper-tool`). Copper armor was already first. A copper axe counts when stripping logs; with no axe held, stripping still crafts a wooden axe.

`#maze.backrooms` and the Konami activation are unchanged. `rooms=0`, the swarm map format, the sealed shell, and tunnel connections are unchanged.