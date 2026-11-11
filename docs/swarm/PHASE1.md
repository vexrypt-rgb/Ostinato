# Phase 1 swarm command protocol

Architecture v3 is unchanged. This is the Phase 1 slice of the distributed execution contract: identity, signed commands, acks, at-least-once delivery, dedup, split execution and observation states, and cancellation. Leases, fencing tokens, reservations, and durable coordinator state are Phase 2 and are not implemented. A command that needs a lease, or a world mutation aimed at more than one bot, is refused with `coordinated work is not available in phase 1`.

## How to enable

Both settings default to `false`. With them off, `#swarm ping`, `status`, `reload`, `build`, and `stop` behave as before, and no command is sealed as type `SWC`.

```
#set swarmEnabled true
#set swarmCommandProtocol true
#swarm reload
```

`swarmEnabled` starts the existing SIGIL link. `swarmCommandProtocol` attaches the protocol to that link. Toggling either one reloads the link. After a drop, in-flight commands stay `running` with observation `unknown`. Status can still show that retained ledger. A new link starts a fresh in-memory ledger.

## What was built

Layering stays semantics, then SIGIL, then transport.

| Piece | Where |
| --- | --- |
| Bot identity | Roster member id plus the SIGIL signet key id (`CommandProtocol.BotIdentity`, `SwarmEndpoint.localSignetKeyId`) |
| Command identity | UUID, issuer, target, registry name and args, issued-at, deadline. Body `1\|cmd\|...`. Payload hash is SHA-256 of that exact body |
| Authentication | `SwarmEndpoint` seals and checks the frame. The protocol trusts only the authenticated `from`. It rejects forged (issuer ≠ sender), unknown member, expired deadline, and a replay whose payload hash differs |
| Acks | Receiver replies `accepted` or `rejected(reason)` on type `SWA`. The sender waits for that ack. A transport accept is not an ack |
| At-least-once | `pump` resends the same body and the same command id until the deadline, then logs `gave up` and stops. The in-memory ledger is capped at 256 entries and is not written to disk |
| Dedup | The same id and the same hash return the recorded ack and do not call `Executor.start` again, including when a second transport delivers it |
| Execution states | `accepted`, `running`, `succeeded (verified)`, `failed (verified)`, `canceled (confirmed)`. `ExecutionState.transit` refuses any other move |
| Observation | `confirmed`, `stale`, `unknown`, a separate field. `unknown` is not an execution result. A disconnected bot is `running` + `unknown` |
| No auto-retry | `mine`, `place`, `drop`, `pickup`, `craft`, and `use` are not retried while observation is `unknown` |
| Cancellation | `cancel <command-id>` or `stop <command-id>` names the target id. `canceled (confirmed)` only after `confirmStopped` returns true while the link is still up. Any stop that is not confirmed (still running, unknown id, link down, or the target already finished) stays `running` with observation `unknown`. A verified terminal result is not rewritten |
| Safety | Immediate safety, owner emergency stop/pause, and local survival outrank swarm commands. Swarm commands never clear those latches. Owner emergency stop names (`pause`, and owner-tagged `stop` / `cancel` / `disconnect` / `forcecancel`) still pass the owner-emergency latch |
| Registry | `RegistryExecutor` calls `ICommandManager.execute` for `#` commands and `ExternalCommandHook` for TenorClef `@` commands the registry does not own. Permission tags are `CommandPermission` (`owner`, `swarm`, `AI`) on `ICommand.getPermission`. `#help` and `#set` stay owner. `goto`, `mine`, `follow`, and `come` are tagged `swarm`. `agent` is tagged `AI` and still goes through `AgentPolicy` (owner, dry-run, tool whitelist) |
| Status | `#swarm status` appends protocol lines when the flag is on. `#swarm send <bot> <command...>`, `#swarm outcome <id>`, `#swarm cancel <id>`. `@swarm send`, `outcome`, and `cancel` call the same `baritone.api.swarm.ISwarm` methods. Closing `ISwarmStatusView` drops the view only. It does not cancel work. Bots do not need the view |
| Capabilities | A hello (`SWH`) carries a versioned descriptor. Gameplay advertises navigation, mining, building, combat, and inventory. `liveRendering` is absent, so render and freecam are refused. An unknown descriptor version matches nothing |

`#swarm stop` is still the region-build stop. The protocol stop is `#swarm cancel <command-id>`, which sends `cancel <id>` to the bot that was given that command.

TenorClef's older fleet guide mentions `@swarm cancel` for coordinator assignments. This command now handles a protocol command id. Coordinator assignment ids are a different system and are not canceled by this path.

Dry-run (the command flag, or an AI command while `agentDryRun` is on) is rejected with reason `preview` and is not executed.

## Phase 1 quantitative gate

Each row is one `Phase1GateTest.gate_*` method. Do not treat a transport receipt as a pass.

| Gate | Test | Result |
| --- | --- | --- |
| `unknown` is never an execution result | `gate_unknownIsNeverAnExecutionResult` | PASS |
| Unexpected command execution = 0 (duplicate delivery × N) | `gate_unexpectedExecution_duplicateDeliveryTimesN` | PASS |
| Cross-transport duplicate | `gate_unexpectedExecution_crossTransportDuplicate` | PASS |
| Tampered replay | `gate_unexpectedExecution_tamperedReplay` | PASS |
| Forged | `gate_unexpectedExecution_forged` | PASS |
| Unknown member | `gate_unexpectedExecution_unknownMember` | PASS |
| Wrong target | `gate_unexpectedExecution_wrongTarget` | PASS |
| Expired deadline | `gate_unexpectedExecution_expired` | PASS |
| Unauthorized | `gate_unexpectedExecution_unauthorized` | PASS |
| Dry-run never executes | `gate_unexpectedExecution_dryRunNeverExecutes` | PASS |
| Safety outranks swarm, including cancel during owner emergency | `gate_unexpectedExecution_safetyOutranksSwarm` | PASS |
| Duplicate active task ownership = 0 | `gate_duplicateActiveTaskOwnership_concurrentDelivery` | PASS |
| Stop reaches canceled (confirmed) or stays running + unknown | `gate_stopScenarios_allCanceledConfirmedOrRunningUnknown` | PASS |
| Injected disconnects end in a known safe state | `gate_injectedDisconnects_everyCaseKnownSafeState` | PASS |
| No auto-retry of mine or place on unknown | `gate_noAutoRetryMineOrPlaceOnUnknown` | PASS |
| At-least-once, same id, visible give-up | `gate_atLeastOnce_retriesSameIdAndGivesUpAtDeadline` | PASS |
| Status view close does not interrupt execution | `gate_statusViewClose_doesNotInterruptExecution` | PASS |
| Protocol path within the preregistered tolerance | `gate_performance_withinPreregisteredTolerance` | PASS |
| Headless gate crash / OOM = 0 | `gate_clientCrashesAndOomStayZero` | PASS |
| SIGIL: unauthenticated, forged frame, replay never execute | `gate_sigil_unauthenticatedForgedReplayNeverExecute` | PASS |

The performance constants are `PREREGISTERED_BATCH = 20`, `PREREGISTERED_MEDIAN_FACTOR = 80`, `PREREGISTERED_ADDITIVE_MS = 5`, `PREREGISTERED_SAMPLES = 21`, written in the test before the timed section. The check is `protocolBatchMedianMs <= plainBatchMedianMs * 80 + 5`. It times the protocol receive path for `echo`, not Ed25519.

Headless gate run, 2026-11-10 morning MST: `gradlew test --tests baritone.swarm.*` with `-Dorg.gradle.jvmargs=-Xmx1g` and `--max-workers=2` — the swarm package was 194 tests, 0 failures. That includes the `gate_*` methods above (57 protocol tests) plus the existing swarm tests. The PASS marks in this table are those headless methods. Live client crash, OOM, command-center close, and live performance stay out of this table; see `SWARM_PHASE1_GATE.md`.

Full suite, 2026-11-10 09:55 MST: `gradlew test -x nativeHost --no-daemon --max-workers=2` — 1702 tests, 0 failures, 0 errors, 25 skipped (1677 passed). A later `gradlew test` the same morning, with `nativeHost` included, `-Dorg.gradle.jvmargs=-Xmx1g`, `--max-workers=2`, and `--no-daemon`, also finished with those counts. The skips are existing benchmarks and diagnostics. `PiCheapPathTest` passed 5/5. `LongRouteSimTest.tripsReachTheGoalOnBothArms` passed; `LongRouteSimTest.corpus` was skipped. TenorClef `:26.3:test` the same morning, with `-PostinatoJar.26.3` pointing at `ostinato/fabric/build/libs/baritone-fabric-1.27.0.jar`: 365 tests, 0 failures, 0 skipped, including `Phase1AtAdapterTest` (2). Remap was a no-op, so that jar is the current classes. ProGuard could not replace `baritone-unoptimized-fabric-1.27.0.jar` because the file was locked; the 07:39 copy does not contain the Phase 1 classes. The baritone import gate passed (1 allowlisted file).

## Left for Phase 2

Leases, fencing, reservations, durable coordinator state, and a required command-center window. Hidden or headless rendering is not part of this phase.

## Phase 1 regression fixes

Coordinate `goto` completion requires its captured goal to be reached, its custom-goal process to be inactive,
and no active path or path calculation. An empty control slot is not success, including before the first process
tick. After pathing has been observed, idle away from the captured goal is failure (not a hang). A non-goto
process such as `#mine` succeeds once it has run and gone idle. A command that never shows activity stays in
progress rather than acquiring an invented success outcome. The goal is also captured on a later poll if
`execute()` had not activated the custom-goal process yet.

Signed command envelopes (especially `cancel <UUID>`) can exceed eight frames after S2S overhead.
The default `swarmMaxChunks` is now 32 on both send and receive. Installations explicitly configured to 8
must raise that setting on both peers. Command ids, bodies, retry intervals, and ack/deadline rules are unchanged.

While Phase 1 is enabled, short authenticated heartbeats run every 10 seconds. After 30 seconds without
an authenticated peer message, that peer's in-flight outbound commands become observation `unknown`.
Execution is preserved, including on local disconnect. Heartbeat receipt does not claim a command outcome.

S2S signing uses Ed25519 (32-byte raw seed and public key), as specified by `SigilEd25519` and the signed
wire format. P-256 ECDH keys belong to encryption and cannot be converted into those signing keys.
This worktree does not contain the external `sigil.py`; use the local writer:

```
python ostinato/tools/sigil_signets.py BotName --out-dir /path/to/private-keyring
```

It requires Python's `cryptography` package, refuses to overwrite files, and writes `signet-BotName.json`
(private seed) and `signet-BotName-public.json` (shareable pin). Keep the seed only on that bot; distribute
only the public pin to peers. On Windows, place the output in a directory whose ACL restricts access to the
bot owner. Existing P-256 identities need newly generated Ed25519 signets and updated peer pins.
The shared RFC 8032 test-only fixture checks Python writer / Java reader agreement.
