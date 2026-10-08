# Swarm and region builds (`#swarm`)

This is Ostinato's swarm link: a sealed, signed, roster-authenticated channel between your bots, plus **signed
region builds** on top of it. TenorClef's `@swarm` (leader/worker objectives such as "gather 64 logs") is not a
separate network: it rides this same link as message type `TCS`, through `SwarmControl.registerHandler`. See
[that guide](https://github.com/vexrypt-rgb/TenorClef/blob/main/docs/guides/swarm-and-fleet.md), or the Swarm tab
in TenorClef's menu.

## Setup
1. Set `swarmEnabled true` (default off).
2. Make a roster file (`swarmRosterFile`, default `swarm.txt`, relative to the `baritone` folder). One line per group; it holds names only:
   ```
   group builders circle=my-circle members=Alice,Bob,Carol lead=Alice
   ```
   Optional keys include `parent=<group>` and `transport=` (see `SwarmRoster`). The lead must be a member.
3. Provide a [SIGIL](https://github.com/vexrypt-rgb/sigil) keyring: `swarmSigilHome` or the `SIGIL_HOME` environment variable (`circle-*.json`). With `swarmRequireSignedSender` (default true) you also need pinned member `signet-*.json` records.
4. Every line sent is a sealed SIGIL token (`swarmWireVersion` S2). There is no plaintext mode.

## Commands
| Command | Effect |
| --- | --- |
| `#swarm status` | roster, last-seen times, link health, and each region's build state |
| `#swarm ping [group]` | ping members |
| `#swarm reload` | reload roster and keyring |
| `#swarm build <group> <file> [x y z]` | as the group's lead, split a schematic over the group |
| `#swarm stop` | stop the job you lead, and your own region |

## Region builds
The file must be a plain name in every member's `schematics` folder. Each member (roster order) gets region `i`
of `n`. The lead's `buildPartition*` values travel with the order, so members' own settings do not matter. The
lead builds its own region too.

| Setting | Default | Meaning |
| --- | --- | --- |
| `buildPartitionStrategy` | `strips` | `strips`, `grid`, or `layers` (bottom-up) |
| `buildPartitionAxis` | `auto` | `auto`, `x`, `z` |
| `buildPartitionGridColumns` | 0 | grid columns; 0 = from aspect ratio |
| `buildPartitionSeamWidth` | 1 | seam thickness per cut; 0 = none |
| `buildRegionProtectForeign` | true | bot will not break/place inside other members' regions |

`layers` is enforced: band `i+1` is sent only when band `i` reports done. An order with no answer after 60 s is
resent once. Members accept orders and stops only from the roster lead.

## Add-on messages
`SwarmControl.registerHandler(type, handler)` lets another mod register a message type. Its messages get the
same sealing, signature, replay and rate-limit checks as Ostinato's own, and arrive as (sender, group, body).
Builds without this method cannot host TenorClef swarms.

## Limits
Blocks at a region edge that need a neighbour's block as support may wait until that neighbour is built. See
[Region builds](../REGION_BUILD.md) for the algorithm and edge cases. Other `swarm*` settings are rate limits,
replay window, chunking and peer caps; defaults are conservative for vanilla servers (`swarmSendRatePerSec` 0.5,
burst 5). Unit and integration tests exist (`SwarmBuildTest`, `SwarmBuildIntegrationTest`); this guide does not
claim a verified live multi-bot build on a server.
