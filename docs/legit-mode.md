# Legit mode

`legitMode` controls whether ore and block targeting may use blocks the bot has not seen. It is a registered setting, so `#set` works today. The radial menu, the command center, and AI tool calls are not part of this change.

| Value | Effective mode |
| --- | --- |
| `auto` (default) | On only while the client is connected to a remote multiplayer server. Off in singleplayer, off on LAN, and off when no remote connection can be shown. |
| `on` | On, in every session. |
| `off` | Off. Pathing, mining, and targeting stay as they were before this setting. |

```
#set legitMode on
#set legitMode off
#set legitMode auto
```

Any other token is rejected. The message says the setting expects `on`, `off`, or `auto`.

`auto` does not mean "off". A singleplayer or LAN world does not turn the filter on by itself. The owner turns it on with `#set legitMode on` there.

This is separate from `legitMine`. `legitMine` is the older strip-mine explore flag. `legitMode` is the seen-only gate.

The check is `LegitMode.isActive(setting, session)`. Live code reads the session from `LegitSessions.current()`: an integrated server is singleplayer, an integrated server opened to LAN is LAN, and a connection with no integrated server is remote.

## Seen-only filter

While the effective mode is on, Mine and GetToBlock cannot take a block that fails `SeenOnly.knowable`. The ore cache is not read. A block is knowable only when a face is open to air or fluid, a block-grid line from the eye reaches that open cell, and the open cell's light is at least 1. An unknown cell blocks the line. A block that passed the check stays admitted for the rest of that mine or get-to-block request, in memory only.

`SeenOnlyShare.mayShare` is the hook a later world-knowledge service calls. When legit mode is on, it shares a block only if the filter passed. The service is not built here.

When the effective mode is off, `LegitTargets.select` returns the candidate list unchanged.

## Gathering while the filter is on

When the effective mode is on and Mine has no block that passed the filter, one bot gathers. `#mine` is that path. The off path, including `GoalRunAway` while `legitMine` or `exploreForBlocks` is on, is unchanged.

| Situation | What the bot does |
| --- | --- |
| More than one bot is assigned the gather | Refuse with `swarm: coordinated work is not available in phase 1`. No goal. |
| Grace Note holds control | Yield. Water and mining do not override it. |
| Head is in water and air is at or under 40 while a sink was the pocket choice | Retreat one block up. |
| Head is in water, a door is carried, and the floor can hold it | `WATER_DOOR` at the feet. The right-click that places the door needs a live client. |
| An open neighbor is unlit | `LIGHT` on that cell. The bot pauses instead of walking in. Placing the torch needs a live client. |
| An open neighbor is lit | Walk one block into that cave cell. |
| Nothing open is nearby, and a pick is in the hand | Branch one block at a time at the 26.3 level for the ore. The cell comes from the tunnel origin and a salt. It is not an ore coordinate. |
| The pick is not in the hand | Swap to it, or yield when there is no pick. The bot does not punch the stone. |
| The look period for this salt elapses | Look, through the existing mouse movement, and pause the walk. |

Branch tunnels are 1 block wide and 3 blocks apart center to center, so the two blocks between them each have a face on a tunnel. A block that was buried becomes a legal target only after that face is open and the seen-only filter passes.

Diamond branches at Y -59. Iron's tunnel is Y 16 (the mountain peak Y 232 is recorded and is not the tunnel). Coal is Y 96 (Y 136 is the other peak). Nether quartz and nether gold branch at Y 48, inside their flat Y 10–117 range and above the lava sea. Ancient debris is Y 15. An unknown block uses Y 16.

## What is still not built

Shared world knowledge, leases, and splitting caves or strip lanes between bots are Phase 2. This setting does not start that work. TenorClef's own collect task is not this path. `#mine` is.

## Tests

| Rule | Test |
| --- | --- |
| `auto` is off in singleplayer, LAN, and when no remote session is shown, and on for a remote session | `LegitModeTest.autoIsOffInSingleplayerLanAndWhenNoRemoteIsShown` |
| `on` and `off` ignore the session | `LegitModeTest.onAndOffIgnoreTheSession` |
| Default is `auto` | `LegitModeTest.defaultSettingIsAuto` |
| `#set` accepts `on`, `off`, and `auto`, and rejects other tokens | `LegitModeTest.setAcceptsOnOffAutoAndRejectsOtherTokens` |
| A fully buried ore is not knowable | `SeenOnlyTest.buriedOreIsNotKnowable` |
| An exposed, lit ore with a clear line is knowable | `SeenOnlyTest.exposedLitOreWithAClearLineIsKnowable` |
| A wall, darkness, or an unknown cell hides it | `SeenOnlyTest.aWallOrDarknessOrAnUnknownCellHidesTheOre` |
| A corner crossing needs both side cells open | `SeenOnlyTest.aCornerCrossingFailsUnlessBothSideCellsAreOpen` |
| Effective off does not filter; effective on drops the buried ore | `SeenOnlyTest.filterOffKeepsTheBuriedOreAndFilterOnDoesNot` |
| Mine and GetToBlock call that filter and skip the ore cache | `SeenOnlyTest.mineAndGetToBlockCallTheFilter` |
| A later share keeps a block only when legit is off or the block was knowable | `SeenOnlyTest.shareHookAllowsOnlyKnowableBlocksWhileLegitIsOn` |
| 26.3 branch levels, including both iron and coal peaks | `LegitGatherTest.yLevelsMatch263Peaks` |
| Branch spacing exposes the in-between faces and misses a hidden ore | `LegitGatherTest.branchCellsExposeFacesAndDoNotAimAtAHiddenOre` |
| A dug branch cell lets the filter accept the neighbor | `LegitGatherTest.openingABranchFaceMakesTheNeighborKnowable` |
| One bot steps down, then branches, and the goal is not the hidden ore | `LegitGatherTest.aSingleBotBranchesBesideItselfAndNotAtTheHiddenOre` |
| Multi-bot gather is the phase 1 refusal and has no goal | `LegitGatherTest.multiBotGatherIsRefusedAndDoesNotPlan` |
| Grace Note yields before a low-air swim | `LegitGatherTest.graceNoteYieldsBeforeWaterOrMining` |
| Low air retreats; a door is chosen on a floor; a sink is not toward the ore | `LegitGatherTest.lowAirRetreatsAndADoorIsChosenWhenThereIsAFloor` |
| No pick does not start a branch | `LegitGatherTest.noPickDoesNotStartABranch` |
| A dark opening is light, not a cave step | `LegitGatherTest.aDarkFrontierIsLitBeforeItIsWalked` |
| A lit opening is one cave step | `LegitGatherTest.aLitOpenFrontierIsACaveStep` |
| Look ticks follow the salt | `LegitGatherTest.lookTicksPauseOnASaltCadence` |
| Mine calls gather only in the legit-active branch and still has the old runaway | `LegitGatherTest.mineUsesGatherOnlyWhileLegitIsActive` |

Headless JUnit only. No Minecraft client is launched.

Client crashes, out-of-memory, and a live cave run are **needs a live run**. They are not marked as passed.
