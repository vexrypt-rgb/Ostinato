# center-app: the Phase 3 command-center window, built and tested without Minecraft

The window's sources live in the mod tree (`src/main/java/baritone/swarm/center/{app,feed,channel}` plus a few pure
shared classes listed in `build.gradle`). This build compiles them with only the JDK on the class path, so a Minecraft
import fails it, and runs their tests headless. No client, no server, no game assets.

| Command (from the repo root) | What |
|---|---|
| `./gradlew -p center-app test` | every window, feed and channel test; PNGs of the wall in all four core themes land in `center-app/build/screens/` |
| `./gradlew -p center-app run --args="--fake 8"` | the window over a moving fake swarm (paths, leases, observations, terrain) |
| `./gradlew -p center-app run --args="--dir C:/path/to/center"` | the window over a real feed directory (`swarmCenterDir`) |
| `./gradlew -p center-app jar` | `build/libs/ostinato-center-app.jar` for live checks (`javaw -cp <jar> baritone.swarm.center.app.CenterApp ...`) |
| `pwsh scripts/center-app.ps1 test\|demo\|screens\|jar` | the same, with JDK 21 and a 1.5 GB daemon cap set for you |

Window arguments: `--dir <feed dir>` or `--fake N`, `--theme dark|light|high-contrast|colorblind`,
`--layout wall|map`, `--pos x,y` (default 2600,40, the second monitor), `--log <file>` (replies and tile lines),
`--auto "secs|bot|formId|arg=value,..."` (press a tile button through Swing after `secs`; for live checks, never the
real mouse).

Tests: `feed/` (codec, schema golden, reader, alerts, inventory, terrain), `channel/` (HMAC, replay, loopback end to
end), `app/` (`UiHarness`-driven UI tests, command dispatch, placement, source lint). Bot-side tests (host, gather,
accessors, settings) are in the mod build: `./gradlew test --tests "baritone.swarm.center.*"`.

How to add a slice: `docs/PHASE3-GROK-HANDOFF.md`. Review: `docs/PHASE3-REVIEW-CHECKLIST.md`.
