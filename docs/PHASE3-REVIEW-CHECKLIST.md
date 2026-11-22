# Phase 3 command center: review checklist for slice branches

For whoever reviews a `swarm/p3-<slice>` branch before it merges. Work through every section; one unchecked box in
sections A to D is a "request changes". Background: `docs/PHASE3-GROK-HANDOFF.md` (rules, API, slice criteria).

## A. Scope and bot behaviour (blocking)

- [ ] `git diff --stat main...swarm/p3-<slice>` touches only `src/main/java/baritone/swarm/center/{app,feed,channel}`,
      their tests, `center-app/`, `docs/`, and harness files outside the repo. Anything else is explained in the
      commit message and is one of: a new feed field, a read-only accessor, a `#center` / `#theme` change asked for by
      the slice.
- [ ] No change to pathing, mining, building, processes, leases, fences, reservations, observations, survival, the
      swarm link or chat traffic. Read the diff of every file under `src/main/java/baritone/` outside `swarm/center/`
      line by line.
- [ ] New accessors return copies, take the existing lock, and do not purge, prune, load or reorder anything (compare
      with `WorldKnowledge.status`, which purges entities: an accessor must not). Each has a test against the
      matching `describe()` / `status()` output.
- [ ] No setting default changed. Any new `swarm*` setting that does not affect the link is excluded from
      `SwarmBehavior.settingsKey` (otherwise toggling it restarts the link) and has a description
      (`scripts/gen_setting_descriptions.py`).
- [ ] Bot-side work stays off the client thread when it can block (file IO, terrain) and is bounded when it cannot
      (`gatherUs` in the feed stays in the low hundreds of microseconds in the live check).

## B. Commands (blocking)

- [ ] Every command the window sends goes through `Commands.press` (a catalog form) or `Commands.line` (one registry
      line). No socket, file or process call to a bot anywhere else in `app/`.
- [ ] Every order shows its equivalent `#` line before it is sent (`Commands.preview` or the literal line) and the
      host's reply (`reply.summary()`) after.
- [ ] Multi-bot orders send one request per bot and show each reply; nothing is retried automatically.
- [ ] Lines are built from existing commands only (`goto`, `mine`, `cancel`, `swarm send`, `swarm lease`, `swarm
      despawn`, `center`, `theme`...). Grep the slice for new command words; there must be none.
- [ ] No change to `CenterAuth`, `CenterHostCore`, `CenterHost` admission or `TileRoute` unless the slice is about
      them; if changed, `ChannelTest` and `CenterHostTest` cover the change and still refuse bad MAC, replay, stale,
      malformed and oversized requests before the handler.

## C. Window rules (blocking)

- [ ] `SourceLintTest` unchanged and green: no `toFront`, `requestFocus*`, `setAlwaysOnTop`, `JOptionPane`, `JDialog`,
      beep, taskbar or tray in `app/`. Confirmations are in-window (second click), never a dialog.
- [ ] New buttons are made with `AppStyle` (not focusable). Text fields may take focus only when clicked.
- [ ] No Minecraft, Gson or Baritone-core import in `app/`, `feed/`, `channel/`: `./gradlew -p center-app compileJava`
      builds.
- [ ] Every color comes from `AppStyle`; every status color is paired with a glyph or shape and a word. Look at the
      PNGs in `center-app/build/screens/` for all four core themes: nothing unreadable, nothing clipped at 1280 x 860.
- [ ] `update(SwarmView)` keeps user state across refreshes (typed text, selection, zoom, pan, open panels) and handles
      an empty swarm, an offline bot, a bot in another dimension, no coordinator, no catalog, no host.
- [ ] Nothing in `update` is slow: no file IO, no image decode per refresh; map repaint timing test present (< 16 ms
      per frame budget, see M1).

## D. Read API (blocking when touched)

- [ ] Feed changes are additive inside v1: no renamed, removed or re-meant field; `FeedSchemaTest.GOLDEN` extended
      (new keys at the end), not rewritten.
- [ ] Readers ignore unknown keys; a new field has a sensible default when absent (older bots).
- [ ] `FakeBot` / `FakeSwarm` can produce the new field; `FakeCatalogMatchesRegistryTest` still green.

## E. Tests

- [ ] `./gradlew -p center-app test` green, run by you, not only by the author. Count the tests: the slice added
      tests for every acceptance bullet in the handoff.
- [ ] Pure logic (transforms, hit tests, partitions, order lines, stores) has unit tests with edge cases (1 and 11
      bots, empty, offline, 64-block limit, odd sizes).
- [ ] UI tests use `UiHarness`, click with `doClick` / synthetic events, and check the request that reached
      `FakeCommandPort`, not just that a button exists.
- [ ] `./gradlew test` (full mod suite) green on the merge candidate; failures listed with cause if any were
      pre-existing on `main`.
- [ ] No test sleeps for long or depends on wall-clock timing without a margin; run the app suite 3 times.

## F. Live check evidence

- [ ] `PREREG.md` written before the first run (timestamps), rows match the handoff's acceptance criteria.
- [ ] `check-*.ps1 -DryRun` output shows: free commit >= 5800 MB, two monitors, no other harness java, no recent
      `gate.log` elsewhere, all jars present.
- [ ] Report lists every run, including failed ones and harness faults, with what was fixed.
- [ ] Foreground poll: the window was never the foreground window (or the report explains a user click).
- [ ] The window was closed (killed) during a running command at least once and the bots carried on.
- [ ] 0 crashes, 0 deaths, clients launched hidden or headless at 2600+,40+ with sound off.
- [ ] Jar hashes in the report match a clean build of the branch head.

## G. Merge hygiene

- [ ] Branch is based on current `main`; merge is `--no-ff`; branch pushed before `main`.
- [ ] Commit messages say what and why, name the slice, contain no model names and no co-author line naming a model.
- [ ] Design doc: only the Implementation status section changed, one row for the slice (branch @ commit, merge hash,
      what was verified live, what was not run and why).
- [ ] No `gradlew --stop` in any script or log; harness kills only pids it recorded.
