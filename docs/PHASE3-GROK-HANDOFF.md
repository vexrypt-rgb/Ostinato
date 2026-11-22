# Phase 3 command center: handoff for the remaining slices

Written 22 Nov 2026 (America/Phoenix). Source of truth for the product: `SWARM_DESIGN_DOC_v3.tsx` sections
Command center (`cc`, `cc-features`), Command center layout (`cmds-layout`), Layout options (`cmds-layouts`: Mission
control wall default, War room / RTS optional, `#center layout wall|map`), Color schemes / themes (`cmds-themes`) and
Phases (Phase 3: "the full window, including map and advanced views, on stable coordinator APIs. Closing it does not
stop the swarm."). The doc is frozen except its Implementation status section.

What is merged on `main` (Ostinato, `vexrypt-rgb/Ostinato`):

| Item | Branch @ commit | Merge on `main` |
|---|---|---|
| 1. Read API v1 (feed files, accessors, alerts, standalone test build) | `swarm/p3-read-api` @ ad1ebe565 | d4f5d8c10 |
| 2. Command path (authenticated loopback, registry commands only) | `swarm/p3-command-path` @ 6ceaf6d1f | a15b19609 |
| 3. Window shell + Mission control wall slice + `#center` launcher | `swarm/p3-wall-slice` @ dcee12bc7 | d72eccb32 |
| 4. Test scaffolding (demo swarm, scripts, these docs) | `swarm/p3-scaffolding` | the merge that added this file |

The live check of item 3 is `swarm-gate-logs/PHASE3-WALL-SLICE-REPORT.md` (harness `swarm-gate-logs/p3cc/`); the
design doc's Implementation status has one row per merge.

## 1. Hard rules

Breaking one of these is a failed slice, whatever the tests say.

1. **Do not change bot behaviour to suit the UI.** The window reads feeds and sends existing registry commands. You
   may add read-only accessors that return copies of what a `describe()` / `status()` already prints, and feed
   fields. You may not change pathing, mining, building, leases, fences, reservations, observations, survival, the
   swarm link, chat traffic or any setting default. If a slice seems to need a behaviour change, stop and ask the
   owner.
2. **No model names** in commit messages, code comments, the design doc or these docs. No co-author line that names
   a model.
3. **Never run `gradlew --stop`.** It kills the owner's protected daemons. To free memory, stop only the java pids you
   started (note pids before a build, stop the new ones after).
4. **Clients go on the second monitor, muted, with no focus grab.** The harness does this (`-Dostinato.windowPos`,
   `soundCategory_*:0.0`, `pauseOnLostFocus:false`, headless or hidden). The window is shown at 2600,40 without
   activating and never calls `toFront`, `requestFocus*`, `setAlwaysOnTop`, a dialog, a beep or a taskbar flash
   (`SourceLintTest` fails the build).
5. **Free commit must be >= 5.8 GB at launch** of any live check (`check-p3.ps1` refuses below that). Check with
   `(Get-CimInstance Win32_OperatingSystem).FreeVirtualMemory/1KB` MB.
6. **No live check while another chat's gate run is going.** `check-p3.ps1 -DryRun` lists other harness java processes
   and any `gate.log` written in the last 10 minutes; any hit is a stop.
7. **Fix test-setup faults yourself** (a harness path, a stale fixture, a flaky wait). Do not weaken a row to make it
   pass; a real failure is reported as a failure.
8. **Ask the owner only about UX decisions the design doc leaves open.** Answers so far are in section 7.
9. **One-line progress updates** while working; **one report at the end** with the commit hashes.
10. **Merge each slice to `main` when it passes**: commit on its own branch, push the branch, `git merge --no-ff`
    into an up-to-date `main`, push `main`, then add one row to the design doc's Implementation status table.
11. **Windows PowerShell scripts**: never name a local variable like a script-level path (`$p` shadows `$P`); give
    paths distinctive names (`$BaseDir`, `$RunDir`). Never `tail -F` a file a script appends to.

## 2. Architecture

```
 bots (26.3 clients, headless in the gates)                          command-center window (own JVM, no Minecraft)
 ┌──────────────────────────────────────────┐   feed files (read)    ┌─────────────────────────────────────────┐
 │ SwarmBehavior.onTick                      │  <swarmCenterDir>/     │ FeedReader (SwarmSource) ── SwarmView     │
 │  └ CenterFeedService (swarmCenterFeed)    │──bots/<name>.json ────>│   + Alerts (rules, stuck tracker)        │
 │     BotFeedGather (client thread, getters)│  terrain/<name>.json   │                │ every 500 ms             │
 │     writer thread: atomic temp+rename     │  coordinator.json      │                v                          │
 │  └ CenterHost (swarmCenterHost, 1 bot)    │  catalog.json          │ CenterShell: header, cards, footer        │
 │     127.0.0.1:<port>, HMAC key in host.key│  host.json, host.key   │   LayoutView cards: WallView, MapView     │
 │     queue -> client thread -> registry    │<── signed request ─────│ Commands -> CenterClient (CommandPort)    │
 │     TileActions.press / registry.execute  │─── signed reply ──────>│   preview = TileRoute (same as the host)  │
 └──────────────────────────────────────────┘                        └─────────────────────────────────────────┘
```

- The window is a **client** of the swarm: closing or killing it changes nothing in any bot (`check-p3` row L5).
- Data goes out only as files; commands come in only over the signed loopback channel, and only as registry
  commands. There is no other path.
- `#center` on a bot with `swarmCenterFeed true` starts the window detached (`CenterLauncher`, `window.pid` in the
  feed dir); `#center close` ends that process. With the feed off, the Phase 1 in-process window is unchanged.
- The window's sources compile into the mod jar and, separately, in `center-app/` with only the JDK on the class path.
  A Minecraft, Gson or Baritone-core import in `swarm/center/{app,feed,channel}` fails that build.

Packages (all under `src/main/java/baritone/swarm/center/`):

| Package | What | Minecraft? |
|---|---|---|
| `feed/` | read API v1: `CenterFeed` (layout, envelope, atomic IO), `BotFeed`, `CoordinatorFeed`, `CatalogFeed`, `TerrainFeed`, `Json`, `SwarmSource`, `SwarmView`, `FeedReader`, `Alert`, `Alerts`, `InventorySummary`, `TerrainBuilder` | no |
| `channel/` | command channel: `CenterRequest`, `CenterReply`, `CenterAuth`, `CenterHostCore`, `CenterClient`, `CommandPort`, `HostInfo` | no |
| `app/` | the window: `CenterApp` (main), `CenterShell`, `LayoutView`, `WallView`, `TileView`, `MapView`, `Commands`, `AppStyle`, `Bars`, `TileText` | no |
| `app/fake/` | test scaffolding: `FakeSwarm`, `FakeBot`, `FakeCommandPort` | no |
| `host/` | bot side: `CenterFeedService`, `BotFeedGather`, `CenterHost`, `CenterLauncher` | yes |

## 3. Build and test (no Minecraft running, no client, no server)

```
./gradlew -p center-app test            # window, feed, channel: ~60 tests, headless AWT, ~10 s
./gradlew -p center-app run --args="--fake 8"              # the window over a moving fake swarm (demo)
./gradlew -p center-app jar             # build/libs/ostinato-center-app.jar for live checks
./gradlew test --tests "baritone.swarm.center.*"           # bot-side host, gather, settings (needs the MC
                                                            # mappings in the Gradle cache, not a running game)
./gradlew test                          # the full mod suite before every merge (about 2200 tests, ~10 min)
```

Run Gradle on JDK 21 (`JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`) with
`-Dorg.gradle.jvmargs=-Xmx1536M`. UI tests write PNGs to `center-app/build/screens/`; look at them for every slice in
all four core themes. `scripts/center-app.ps1` wraps the common commands (see item 4).

## 4. The API

### 4.1 Feed files (read API v1)

Directory: `swarmCenterDir` (absolute, same on every bot of the PC); empty means `center` next to
`swarmLocalSpoolDir`, else `<gameDir>/ostinato/center`. Every file has one writer and the envelope
`{"schema":"ostinato.center","v":1,"kind":...,"writtenAtMs":...,"seq":...}`.

| File | Writer | Period | Content |
|---|---|---|---|
| `bots/<name>.json` | that bot | 1 s | `BotFeed`: name, server, runMode, commandHost, coordinator, `pos` {dim o/n/e/?, x, y, z, yaw} (null outside a world), health/maxHealth, hunger, dead, process, `path` {state idle/calculating/pathing/waiting, goal, goalPos, distance, position, length, nodes ≤ 32}, `inventory` {freeSlots, totalSlots, food, top ≤ 4 {id, count}, lowestToolPct, lowestTool}, `executions` ≤ 8 {id, command, issuer, execution, observation, ageMs, endReason}, `leases` {id, gen, resource, phase, leftMs, active}, `perf` (SwarmSample figures), `link` {up, peersSeen, failure}, gatherUs |
| `terrain/<name>.json` | that bot | 10 s | `TerrainFeed`: 144 x 144 columns (9 x 9 chunks) around the bot from its own Baritone cache: dim, originX/Z, width, depth, palette (index 0 = "?" not cached), `blocks` base64 bytes, `heights` base64 big-endian shorts (`UNKNOWN_Y` = -32768). Row-major from the origin. |
| `coordinator.json` | lease coordinator | 1 s | `CoordinatorFeed`: mode, world, `leases` {id, resource, type vein/chest/region/key, dim, box x1,y1,z1,x2,y2,z2 inclusive or null, bot, gen, state offered/granted/revoking/released/expired, command, execution, observation, endReason}, `observations` ≤ 200 {key, kind, dim, box, bot, confidence, detail, state, suspect, ageMs}, observationTotal |
| `catalog.json` | command host | start + 10 s | `CatalogFeed`: every registry button {command, permission, id, label, prefix, placement tile/header/window, args {name, kind token/int/enum/rest, required, choices}} |
| `host.json`, `host.key` | command host | start | loopback port, pid; 32-byte HMAC key, hex, user-only ACL. Removed when the host stops. |

**Versioning rule.** Inside v1 you may only add fields (at the end of an object), never rename, remove or change the
meaning of one. Readers ignore unknown keys. A breaking change is v2 and needs the owner. `FeedSchemaTest.GOLDEN` pins
the v1 bot feed; extend it when you add a field, never edit existing keys.

**Freshness.** `FRESH_MS` 3 s, `OFFLINE_MS` 15 s, measured from the writer's wall clock.

**Alerts** (`Alerts`, window side, every rule has a glyph through `TileStatus.Level`): offline, dead, failed (newest
command failed < 60 s ago), stale feed, stuck (busy and < 1 block in 30 s), low health ≤ 6, hungry ≤ 6, link down
with a running command, lease expired (coordinator), tool ≤ 10 %, inventory full. Add a rule as a new `Alert.Code`
plus a case in `Alerts.forBot` plus a test in `AlertsTest`.

### 4.2 Command channel

`CenterRequest` ops: `PRESS` (a catalog form: command + formId + values + target bot), `LINE` (one registry line, run
on the host as written; compose `swarm send <bot> ...` yourself when you mean another bot), `PING`. `dry()` asks for
a preview: reason "preview", the line, nothing runs. The host checks MAC, id, ±30 s time, size, then queues the
request to the client thread (5 s, else "timeout") and runs it as a `RegistryCall.Kind.WINDOW` call. `CenterReply`
has admitted / executed / reason (registry reason words: accepted, unknown, unauthorized, busy, lease, invalid,
phase1, preview; channel words: bad mac, replay, stale, malformed, too long, unknown form, timeout, host closed) and
`line`, the exact `#` line that ran. `reply.summary()` is what the window shows: `Copper: #swarm send Azalea goto 10 64
3 - accepted`.

### 4.3 Window classes

- **`Commands`** — the only way a view sends anything: `press(button, values, bot, done)`, `line(line, bot, done)`,
  `Commands.preview(button, values, bot, host)` (the equivalent `#` line, or the form's reason), `Commands.ready(...)`,
  `onReply(listener)`, `history()`. Sends on a background thread, replies on the Swing thread.
- **`LayoutView`** — the slice contract (see 5.1).
- **`AppStyle`** — every color comes from it (`bg`, `surface`, `raised`, `line`, `text`, `muted`, `faint`, `accent`,
  `ok`, `warn`, `danger`, `level(TileStatus.Level)`); widgets: `panel`, `label`, `button`, `dangerButton`, `toggle`,
  `field(columns, prompt, tip)`, `box`. Buttons are not focusable.
- **`TileText`** — the words shown for a bot (task, inventory, position, age); reuse them in the map inspector.
- **`CenterApp.find(root, name)`** — component lookup by name (tests and `--auto`).
- **Component names** are part of the test contract: `tile:<bot>[:status|:glyph|:task|:position|:bars|:inventory|
  :alerts|:preview|:result]`, `tile:<bot>:button:<formId>`, `tile:<bot>:field:<formId>:<arg>`, `header.server`,
  `header.host`, `header.alerts`, `header.layout.wall|map`, `footer.reply`, `footer.problems`, `wall`, `map`. New
  slices use `<slice>:<thing>[:<part>]`, for example `map:marker:Azalea`, `map:order:goto-area`.

### 4.4 Test scaffolding

- **`FakeSwarm`** (`SwarmSource`): `threeBots(clock)`, `many(n, clock)`; `bot(name)` returns a `FakeBot` builder (`.at(x,y,z)`, `.health(4)`, `.process("Mine")`,
  `.execution("mine","running",ms)`, `.age(ms)`, `.dead(true)`, `.link(false)`, `.inventory(...)`, `.path(...)`,
  `.host(bool)`); `coordinator(CoordinatorFeed)`, `terrain(TerrainFeed)`, `catalog(bool)`, `hostUp(bool)`,
  `remove(name)`, `write(dir)` (real feed files for reader tests).
- **`DemoSwarm`** (`SwarmSource`, `new DemoSwarm(n, clock)`): a `FakeSwarm` that moves. Bots circle their homes
  (one lap per minute), odd bots have path nodes and a goal, the coordinator holds a region, a vein and an expired
  lease and knows four observations, and every bot has a terrain slice (hills, water, sand, stone). This is what
  `CenterApp --fake N` shows; develop the map slices against it.
- **`FakeCommandPort`**: records requests, answers "accepted" with the host's routing; `answer(fn)` scripts refusals.
- **`UiHarness`** (center-app tests): the whole window over a fake swarm and port, headless, commands answered on the
  calling thread. `threeBots()`, `many(n, theme)`, `demo(n, theme, layout)`; `refresh()`, `text(name)`, `type(field, text)`, `click(button)`, `sent()`, `advance(ms)`,
  `screenshot(name)`.
- **`FakeCatalogMatchesRegistryTest`** (mod tests) keeps `FakeSwarm.CATALOG` equal to the real command forms.

## 5. How to add a slice

### 5.1 Code

1. Branch from current `main`: `swarm/p3-<slice>` (for example `swarm/p3-map-layout`). Work in your own git worktree.
2. A new layout card implements `LayoutView` in `app/` (`<Name>View.java`): constructor `(AppStyle, Commands)`;
   `layout()` returns its `CenterLayout`; `component()` returns one root built once; `update(SwarmView)` runs on the
   Swing thread every 500 ms with a complete snapshot and must keep typed text, selection, zoom and pan. Replace
   `MapView` in `CenterShell` (`add(new MapView(...))`) rather than adding a third card: the doc allows two layouts.
3. Parts of an existing card go in their own class (`TileDetails`, `FocusPanel`, `MapCanvas`, `MapInput`,
   `AreaOrders`). Keep logic that can be pure (transforms, hit tests, partition, order lines) in a class with no Swing
   in it, so it is unit tested without components.
4. Every color through `AppStyle`; every status color next to its glyph or shape; text through `TileText` where it
   names a bot's state.
5. Commands only through `Commands`; show `Commands.preview(...)` (forms) or the literal line (`LINE` orders) before
   sending, and the reply after. Area or multi-bot orders send one request per bot and show each reply.
6. Need new data? Add a field to a feed (v1, additive), filled by `BotFeedGather` or `CenterFeedService` from an
   existing getter, with a golden/round-trip test and a `FakeBot` setter. If no read-only getter exists, add one that
   returns a copy (see `LeaseCoordinator.feedLeases`) with a test against `describe()`.

### 5.2 Tests to write (all must pass headless)

| Kind | Where | Minimum |
|---|---|---|
| pure logic | `center-app/src/test/java/.../app/<Thing>Test.java` | every rule and edge (empty swarm, 1 and 11 bots, offline bot, bot in another dimension, no terrain, no coordinator) |
| UI | `center-app/src/test/java/.../app/<Slice>ViewTest.java` using `UiHarness` | renders, reacts to a changed fake swarm, keeps state across refresh, every command button sends the expected request and shows the reply, refusals shown, `screenshot("<slice>-<theme>")` for all 4 core themes |
| feed/API changes | `FeedSchemaTest`, `FeedReaderTest`, mod-side accessor test | round trip, golden, old field meanings unchanged |
| lint | `SourceLintTest` (existing) | passes unchanged |
| bot side (only if touched) | `src/test/java/baritone/swarm/center/host/` | accessor equals `describe()`; settings off = nothing written |

Then `./gradlew -p center-app test`, `./gradlew test` (full), look at `center-app/build/screens/*.png`.

### 5.3 Live check (one per slice)

Copy `swarm-gate-logs/p3cc/` to `swarm-gate-logs/p3<slice>/` (`gate.ps1`, `send.ps1`, `runClient.args`,
`inst-template/`, `server/`, `sigil-home/`), replace `p3cc` in the scripts, write `PREREG.md` with the slice's rows
**before** running, adapt `check-p3.ps1` (keep its preconditions, foreground poll, window kill/reopen and `-StopAll` in
`finally`). Build `ost-<slice>.jar` (`./gradlew :fabric:remapJar`, the `baritone-fabric-*.jar` without
classifier) and `center-app.jar`, put both in the harness, run `-DryRun`, then the check. Drive the window with
`--auto "secs|bot|formId|arg=value"` (tile forms) or add an equivalent `--auto-map` for map actions: never move the
real mouse. Write `swarm-gate-logs/PHASE3-<SLICE>-REPORT.md` with every run.

### 5.4 Merge

`./gradlew test` full suite green → commit (no model names) → push branch → `git fetch`, `git checkout main`,
`git merge --ff-only origin/main`, `git merge --no-ff swarm/p3-<slice>` → push `main` → add one Implementation status
row to `SWARM_DESIGN_DOC_v3.tsx` (name, branch @ commit and merge hash, what was verified live) → report.

## 6. Remaining slices and acceptance criteria

Order: S1, M1, M2, M3, M4, L1, T1 (L1 and T1 can go before the map slices if you prefer; S1 first).

### S1. More tile details and focus mode (wall)

The doc: tiles carry health, task, progress and stuck alerts; "focus mode opens one bot without leaving the wall".

- Clicking a tile selects it (accent border) and opens a focus panel at the right of the wall; `Esc` or the close
  button returns to the full grid. Selection survives refreshes and bots joining or leaving; a vanished bot clears it.
- Focus panel shows, for the selected bot: position and dimension, path (state, goal, distance, node count, position
  / length), every execution (command, issuer, state, observation, age, end reason), held leases (resource, gen,
  phase, time left), the full inventory summary, perf (path ms, TPS, ping, RAM used / max, chat used / cap, dropped,
  retried), link (up, peers seen, failure), feed age and gather cost, all alerts.
- Tile adds a one-line perf strip (TPS, ping, RAM) and a small progress figure when a path is running
  (position / length).
- Accept: UI tests for select / deselect / vanish, every focus field from a `FakeBot` value, 11 bots still fit 3
  columns at 1280 x 860 with the panel open (scroll allowed), screenshots in 4 themes. Live: focus Azalea during a
  goto; the panel's path position advances and matches her feed; foreground unchanged.

### M1. War room map layout with bot positions and terrain

The doc: "a full-screen top-down map with bot markers... block colors by type... a Y-level slider cuts through caves";
owner: terrain is in Phase 3.

- Replace `MapView` with a real map card: top-down XZ, pan (drag with the left button on empty map, or arrow keys
  when the map has focus), zoom (wheel, 1 to 16 px per block), grid lines every 16 blocks with chunk coordinates,
  cursor coordinates in a corner, a dimension picker (overworld / nether / end; markers and terrain only for that
  dimension).
- Terrain backdrop from every bot's `TerrainFeed` in that dimension (newer slice wins where they overlap), colored by
  block id through a fixed block-to-color table (grass, stone, water, sand, logs, leaves, ores...) with an "other"
  color; uncached columns stay the background. Height shading (darker below the median y) is optional. The Y-slider
  of the doc is not possible with surface-only data; leave it out and say so in the report.
- Bot markers: identity color per bot (stable by name), a shape per status level (circle ok, hollow circle idle,
  triangle warn, cross bad: never color alone), name label, facing tick from yaw; the current path as a polyline
  through `path.nodes` and the goal as a ring when `goalPos` is set.
- Minimap in a corner showing all bots and the current view rectangle.
- "Center on bot" from a marker's right-click menu; double-click a tile on the wall switches to the map centered on
  that bot (optional).
- Performance: one repaint of 11 bots, 11 terrain slices and paths stays under 16 ms at 1280 x 860 (timed in a test
  with a 300 ms budget for 20 repaints).
- Accept: unit tests for the world/screen transform (round trip at every zoom, pan clamp), terrain color lookup,
  marker hit test; UI tests that markers sit at the fake bots' positions (±1 px at 4 px/block), path drawn, dimension
  switch hides other-dimension bots; screenshots in 4 themes with `FakeSwarm.demo`. Live: 3 markers within 1 block of
  the feeds at 3 sampled times; terrain visible around each bot; foreground unchanged.

### M2. Leases and regions drawn

- From `coordinator.json`: every lease with a box drawn as a rectangle in its dimension (region solid edge, vein
  dashed, chest small square), labelled `bot · gen · state`; live leases solid, expired ones faded with their end
  reason in the tooltip. Untyped (`key`) leases listed in a side list, not drawn.
- Observations as markers by kind (vein, chest, block, structure, waypoint) with state shape (confirmed, stale,
  gone, suspect) and a tooltip (detail, reporter, confidence, age). A legend explains every shape.
- Toggle buttons in the map header: leases, observations, paths, terrain.
- Accept: unit tests for box to screen rectangle and label placement; UI tests with a fake coordinator (one of each
  lease type and state, one of each observation kind and state); screenshots in 4 themes. Live: a leased mine
  (`#swarm lease <bot> r:o:... mine <ore>` sent from the window as a LINE request, or from chat) is drawn with the same
  id, gen and state as `coordinator.json` within 2 s, and goes faded when it ends.

### M3. Select bots

The doc: "Click selects, shift-click adds, groups can be selected together"; War room "control groups 1 to 9".

- Click a marker selects it; shift-click toggles it in the selection; drag on empty map with Shift (or the right
  button) draws a selection box; `Esc` clears. Selected markers get a ring.
- Control groups: Ctrl+1..9 stores the selection, 1..9 recalls it (only while the map has focus; the map takes focus
  only on a click into it). Groups are remembered per user in `<centerDir>/prefs/<user>.properties`.
- A selection inspector (bottom bar, as in the doc's mockup) shows a card per selected bot using `TileText`, with the
  same tile forms as the wall (goto, mine, cancel, despawn) sent through `Commands` for each selected bot.
- Accept: unit tests for hit tests, box select, group store/recall, prefs round trip; UI tests for every gesture via
  synthetic `MouseEvent`/`KeyEvent` dispatch (no real mouse); live: select Azalea and Spelunker, cancel both from the
  inspector, both report canceled (confirmed) in their feeds.

### M4. Draw an area and give orders

Owner's answer: "goto area" and "mine in area"; build waits for the schematic editor.

- An "Area" tool in the map header: drag a rectangle; it stays drawn with its size in blocks and an editable Y range
  (default the selected bots' median y −8..+8). The order bar for the area offers:
  - **Goto area**: spread the selected bots over the area (one point per bot on a grid inside it, nearest bot to
    nearest point); one `PRESS goto` request per bot (`target = "x z"`), each preview shown before sending.
  - **Mine in area** `<ore>`: split the box into one strip per selected bot along its longer edge (the
    `PartitionStrategy.STRIPS` rule; pure arithmetic in the window), each strip at most 64 blocks on every edge
    (`ReservationKey.MAX_EDGE`: larger areas are refused with that reason, not silently cut), and send one `LINE`
    request per bot: `swarm lease <bot> r:<dim>:x1,y1,z1:x2,y2,z2 mine <ore>`. This line runs on the lease
    coordinator; if the command host is not the coordinator (`coordinator` false in its feed), the order is disabled
    with the reason "area mining needs the command host to be the lease coordinator".
- Every request's preview is listed before a single "Send" press, and every reply is listed after.
- Accept: unit tests for the spread, the strip partition (1 to 11 bots, odd sizes, the 64 limit) and the exact lines;
  UI tests that one click sends exactly one request per selected bot with those lines; live: two bots mine in a drawn
  area: two leases granted with the drawn boxes (match `coordinator.json`), every block broken inside its own box
  (server-side check of the broken blocks), `geometryDenials` 0 for work outside, both end released
  succeeded (verified) or report why; foreground unchanged; close the window mid-order and the mining continues.

### L1. Layout toggle remembered per user

The doc: "A header toggle and a registry command (`#center layout wall|map`) switch between them and remember the
choice per user."

- The header toggle sends `center layout wall|map` as a `LINE` request (the registry form `layout-wall` /
  `layout-map` already exists, so a `PRESS` of that form is equally fine) and switches the card at once.
- With `swarmCenterFeed` on, `#center layout <x>` (from chat or from the window) also saves the choice in the shared
  directory, `<centerDir>/prefs/layout-<os user>.txt` (reuse `LayoutStore` with the center dir as its config dir).
  The window reads that file on every refresh and switches the card when it changes, and opens in it at start.
- Accept: unit tests for the store round trip, garbage falls back to the wall, per-user file name; UI test that the
  toggle sends the request and that a changed file switches the card. Live: L1 `#center layout map` typed into the
  host bot (via `send.ps1`) switches the open window within 2 s; L2 the header toggle writes the file and the host
  replies accepted; L3 restarting the window opens it in the last layout.

### T1. Core themes: Default dark, Light, High contrast, Colorblind safe

The doc: themes are JSON token files, `#theme <name>` applies live and is saved per user, status meaning never
relies on color alone, built-ins checked against WCAG AA, flavor themes and the editor can come later.

- Header picker with the four core themes (labels from `ThemeStore.label`). Picking sends `theme <name>` (registry
  form `theme-pick`) and applies at once. With the feed on, `#theme` also saves to `<centerDir>/prefs/theme-<os
  user>.txt`; the window follows that file like the layout (live, no restart).
- Live apply rebuilds the shell with a new `AppStyle` and keeps the layout, the selection, map zoom/pan, typed text and
  the last replies (store view state outside the components or carry it across the rebuild).
- Add the doc's missing token groups to `CenterTheme` with defaults so existing custom JSON files still load: bot
  status colors (ok/idle/warn/bad, may alias the current ones), the map marker palette, and 12 per-bot identity
  colors. The map uses identity colors from the theme.
- Custom theme JSON files in `<centerDir>/themes/` are optional in this slice; if you add them, parse with `Json`
  (the window has no Gson) and fall back to the base theme per missing key.
- Accept: unit tests that every built-in theme meets WCAG AA (4.5:1) for text and every status token on its
  backgrounds (extend `CenterThemeTest`), identity colors are pairwise distinguishable (ΔE ≥ 10 under simulated
  deuteranopia and protanopia for the Colorblind safe theme), state survives a theme switch; screenshots of the wall
  and the map in all four. Live: T1 `#theme light` (and each of the other three) applies to the open window within 2 s
  with no restart (screenshot each via `--log` + the PNG renderer, never a screen grab that moves focus); T2 the choice
  survives a window restart.

## 7. Owner decisions so far (22 Nov 2026)

- `#center` launches the standalone window as a detached process (outlives the host); `#center close` closes it; the
  in-process window stays only for a single visible client with the feed off. **Done** in item 3.
- Tile inventory summary: free slots, food count, top 4 items, lowest tool durability %. Full detail in the focus
  panel. **Done** (S1 adds the panel).
- Area orders: goto area and mine in area (one sub-box per selected bot); build waits for the schematic editor.
- Terrain on the War room map: in Phase 3 (M1).
- Watch / Switch POV buttons: Phase 4 in the doc. Not shown until then (no dead buttons).

Anything else the doc leaves open (for example the exact block color table, whether the map has a right-click menu,
default zoom): pick the plainest choice, note it in the slice report; ask the owner only if it changes what the
window does, not how it looks.

## 8. Known limits

- Feeds are same-PC files. Bots on another PC need a different transport (not in Phase 3).
- On Windows a rename onto a feed the window is reading can be refused; the writer retries for about 1.3 s and then
  drops that one write (logged once). At the real rates (1 write/s, 2 reads/s) this is rare and the window keeps the
  last good copy.
- Terrain is the surface overview only (Baritone's cache), not caves.
- The window runs with `-Xmx128m -XX:+UseSerialGC` (`CenterLauncher.JVM_FLAGS`): about 180 MB private. The map
  slices must stay inside that heap (one 1280 x 860 backbuffer is 4.4 MB; do not cache a full-resolution image per
  terrain slice per zoom level). `check-p3` row L6 measures the window's private bytes (limit 300 MB).
- `peersSeen` counts members heard in the last 30 s; `perf` link fields come from the bot's own chat budget.
