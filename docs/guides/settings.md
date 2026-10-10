# Settings

`#set list [page]` lists every setting with its description; `#set modified` shows what you changed;
`#set <name>` reads; `#set <name> <value>` writes; `#set toggle <name>`; `#set reset <name>` or `#set reset all`.
The source of truth is `src/api/java/baritone/api/Settings.java`. Upstream Baritone settings are documented in
the [Baritone project](https://github.com/cabaletta/baritone); this page lists the Ostinato-specific ones.

| Area | Settings | Guide |
| --- | --- | --- |
| Kinematic travel | `kinematicTravel`, `slowKinematic`, `headSteering`, `pathWander`, `kinematicTrace` | [Movement](movement.md) |
| Moves | `allowClimbJumps`, `allowMomentumJumps`, `allowLadderClutch`, `pickupLadders`, `pitfallAvoidance`, `sprintJump`, `allowBoats`, `experimental*` | [Movement](movement.md) |
| Backend | `movementBackend` | [Movement](movement.md) |
| Elytra | `elytra*`, `elytraTermsAccepted` | [Movement](movement.md) |
| Interface | `guiKeybind`, `renderPathHud`, `pathHudAnchor`, `freecamSpeed`, `freecamKey`, `freecamGhostOpacity`, `enemyMobs` | [GUI](gui-freecam-tasks.md) |
| Region build | `buildPartition*`, `buildRegionProtectForeign` | [Swarm](swarm-and-region-builds.md) |
| Swarm | `swarmEnabled`, `swarmRosterFile`, `swarmSigilHome`, `swarmRequireSignedSender`, `swarmChannel`, rate limits | [Swarm](swarm-and-region-builds.md) |

Defaults quoted in these guides come from the source at the time of writing; use `#set <name>` for yours.
