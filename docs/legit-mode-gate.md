# Legit mode gate

Headless JUnit only. No Minecraft client is launched. A row marked **needs a live run** was not executed and is not a pass.

| Check | Result |
| --- | --- |
| `auto` is off in singleplayer, LAN, and when no remote session is shown | `LegitModeTest.autoIsOffInSingleplayerLanAndWhenNoRemoteIsShown` |
| `on` and `off` ignore the session | `LegitModeTest.onAndOffIgnoreTheSession` |
| `#set legitMode` accepts `on`, `off`, and `auto` only | `LegitModeTest.setAcceptsOnOffAutoAndRejectsOtherTokens` |
| Buried ore is not knowable | `SeenOnlyTest.buriedOreIsNotKnowable` |
| Exposed, lit, clear line is knowable | `SeenOnlyTest.exposedLitOreWithAClearLineIsKnowable` |
| Effective off does not filter; effective on drops the buried ore | `SeenOnlyTest.filterOffKeepsTheBuriedOreAndFilterOnDoesNot` |
| Mine and GetToBlock call the filter and skip the ore cache | `SeenOnlyTest.mineAndGetToBlockCallTheFilter` |
| Share hook | `SeenOnlyTest.shareHookAllowsOnlyKnowableBlocksWhileLegitIsOn` |
| 26.3 Y levels and branch spacing | `LegitGatherTest.yLevelsMatch263Peaks`, `LegitGatherTest.branchCellsExposeFacesAndDoNotAimAtAHiddenOre` |
| Hidden ore is not a branch cell, and a newly opened face can pass the filter | `LegitGatherTest.openingABranchFaceMakesTheNeighborKnowable`, `LegitGatherTest.aSingleBotBranchesBesideItselfAndNotAtTheHiddenOre` |
| Multi-bot gather refused like phase 1 | `LegitGatherTest.multiBotGatherIsRefusedAndDoesNotPlan` |
| Grace Note, low air, door choice, no-pick | `LegitGatherTest.graceNoteYieldsBeforeWaterOrMining`, `LegitGatherTest.lowAirRetreatsAndADoorIsChosenWhenThereIsAFloor`, `LegitGatherTest.noPickDoesNotStartABranch` |
| Dark frontier, lit frontier, look cadence | `LegitGatherTest.aDarkFrontierIsLitBeforeItIsWalked`, `LegitGatherTest.aLitOpenFrontierIsACaveStep`, `LegitGatherTest.lookTicksPauseOnASaltCadence` |
| Effective off still has the old mine runaway | `LegitGatherTest.mineUsesGatherOnlyWhileLegitIsActive` |
| Client crash, out of memory, live cave, live branch, live water dive | **needs a live run** |
| Placing the torch and placing the door | **needs a live run** |
