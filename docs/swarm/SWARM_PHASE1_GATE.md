# Phase 1 quantitative gate

Headless JUnit on this machine (America/Phoenix). Architecture v3 was not edited. Client rows are not claimed from unit tests.

The swarm package run that produced the PASS rows below is `gradlew test --tests baritone.swarm.*` with `-Dorg.gradle.jvmargs=-Xmx1g` and `--max-workers=2`: 194 tests, 0 failures. Names below are the methods that map to each row.

| Gate | Target | Result | Test |
| --- | --- | --- | --- |
| Client crashes | 0 | needs Phase 1 live run | No game client was launched. `Phase1GateTest.gate_clientCrashesAndOomStayZero` only loops headless `echo` commands and is not this row. |
| OOM | 0 | needs Phase 1 live run | Same. Phase 0 is the live memory measurement. |
| Unexpected command execution | 0 | PASS | `Phase1UnexpectedExecutionTest`: `unauthenticatedEnvelopeNeverExecutes`, `wrongSignerEnvelopeNeverExecutes`, `replayedFrameNeverExecutesTwice`, `tamperedEnvelopeNeverExecutes`, `notPermittedEnvelopeNeverExecutes`, `capabilityMissingEnvelopeNeverExecutes`, `flagOffEnvelopeNeverExecutes`, `malformedEnvelopeNeverExecutes`, `issuerMismatchNeverExecutes`, `aiToolNotOnTheWhitelistNeverExecutes`, `badArgumentsNeverExecute`, `coordinatedMineOnAGroupNeverExecutes`, `immediateSafetyOutranksSwarmCommand`. Also `Phase1GateTest.gate_unexpectedExecution_*` and `gate_sigil_unauthenticatedForgedReplayNeverExecute`. |
| Duplicate active task ownership | 0 | PASS | `Phase1DuplicateOwnershipTest.duplicateDeliveryExecutesAtMostOnce`, `randomizedRedeliveryReturnsRecordedOutcome` (120 reordered copies, N≥100), `reorderedDuplicatesDoNotReexecute`, `secondCommandWhileOneIsActiveDoesNotStart`, `inFlightRecordIsNotEvictedByTtl`. Also `Phase1GateTest.gate_duplicateActiveTaskOwnership_concurrentDelivery`. |
| Stop scenarios | 100% canceled (confirmed), or running with observation unknown | PASS | `Phase1StopScenariosTest.stopConfirmedReachesCanceled`, `stopDuringDisconnectStaysRunningUnknown`, `stopUnknownIdStaysRunningUnknown`, `stopRacingCompletionKeepsVerifiedTerminal`, `completionRacingStopDoesNotOverwriteCancel`, `ownerStopOutranksLocalSurvival`, `immediateSafetyBlocksEvenOwnerStop`. Also `Phase1GateTest.gate_stopScenarios_allCanceledConfirmedOrRunningUnknown` and `Phase1DisconnectTest.stopDuringDisconnectOverADroppedFrameStaysUnknown`. |
| Command-center UI | Closing it does not interrupt the bots | needs Phase 1 live run | Code-level stand-in only: `Phase1StateModelTest.closingStatusViewDoesNotChangeBots` and `Phase1GateTest.gate_statusViewClose_doesNotInterruptExecution` (closing the list does not change records or the start count). There is no command-center window in this run. |
| Performance | No material degradation beyond a preregistered tolerance | needs Phase 1 live run | Headless stand-in only: `Phase1GateTest.gate_performance_withinPreregisteredTolerance` (batch 20, median factor 80, additive 5 ms, 21 samples, `echo` receive path). Not a live client baseline. |
| Injected disconnects | Every tested case reaches a known safe state | PASS | `Phase1DisconnectTest.busDropDelayAndDuplicateReachKnownSafeStates`, `spoolDropDelayAndDuplicateReachKnownSafeStates`, `mineNotRetriedWhenObservationUnknown`, `stopDuringDisconnectOverADroppedFrameStaysUnknown`. Also `Phase1GateTest.gate_injectedDisconnects_everyCaseKnownSafeState` and `gate_noAutoRetryMineOrPlaceOnUnknown`. |
| State model | Illegal transitions rejected. `unknown` is never an execution state | PASS | `Phase1StateModelTest.illegalTransitionsRejected`, `unknownIsNeverAnExecutionState`, `disconnectIsRunningPlusUnknownNotAnExecutionResult`, `ambiguousCompletionIsNotSucceeded`. Also `Phase1GateTest.gate_unknownIsNeverAnExecutionResult`. |
| Flags off | No behaviour change | PASS | `Phase1FlagOffTest.flagOffIgnoresCommandFramesLikeUnknownTypes`, `flagOffDoesNotChangeExistingSwarmPing`. Existing `baritone.swarm.*` tests passed with the new flag defaulting off. |

## Not in this run

Leases, generations, fencing, reservations, durable coordinator state, reconciliation, shared world knowledge, and SQLite are Phase 2 and are not implemented.

## Full suite

Recorded 2026-11-10 09:55 MST after the Phase 0 clients were stopped. `gradlew test -x nativeHost --no-daemon --max-workers=2`: 1702 tests, 0 failures, 0 errors, 25 skipped (1677 passed). A later `gradlew test` with `nativeHost` included, `-Dorg.gradle.jvmargs=-Xmx1g`, `--max-workers=2`, and `--no-daemon` finished BUILD SUCCESSFUL with the same counts. `PiCheapPathTest` passed 5/5. `LongRouteSimTest.tripsReachTheGoalOnBothArms` passed. `LongRouteSimTest.corpus` was skipped. TenorClef `:26.3:test` the same morning, against `ostinato/fabric/build/libs/baritone-fabric-1.27.0.jar`: 365 tests, 0 failures, 0 skipped. The 07:39 `baritone-unoptimized-fabric-1.27.0.jar` was locked during ProGuard and does not contain the Phase 1 classes. The baritone import gate passed.
