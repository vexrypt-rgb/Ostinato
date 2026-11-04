# Grace Note continuation — 2026-10-30

Grace Note's existing 26.3 water-clutch slice was recovered, hardened, compiled, unit-tested, and exercised in Minecraft. All five final disposable-world scenarios passed, including a lethal fall while pathing and subsequent arrival at the original goal. This is a bounded survival capability, not completion of every proposed emergency response. Normal startup remains OBSERVE; this continuation enabled LIVE only inside the disposable test harness. Nothing was committed or pushed.

Evidence labels: **Verified** means inspected code or an executed check; **Measured** means recorded benchmark observations; **Reported** means inherited documentation/log claims; **Contradicted** means evidence disagrees; **Unknown** means unvalidated.

## Work recovered

- **Verified:** `Ostinato-ppe`, branch `feat/predictive-physics`, HEAD `1aed5cc2`, contained uncommitted Grace Note API, adapter, state machine, live harness, unit tests and benchmark. PPE and Threat Assessment were also uncommitted. No Grace Note commit was found in inspected local history. Matching remote branch-name queries found no Ostinato Grace Note/physics/threat branch. Remote evidence cannot establish the state of local edits.
- **Verified:** existing integration edits were in `IBaritone`, `Baritone`, `InventoryBehavior`, `PathingBehavior`, and `TestHarness`. These were preserved. The main `Ostinato` checkout was on a different 1.16.1 swarm branch and was not used for implementation.
- **Verified:** `TenorClef-main`, HEAD `983cf317` at inspection, had many unrelated working-tree edits, including threat integration. No Grace Note task integration existed. Further unrelated edits appeared during this session; none were reset, stashed, overwritten or committed. Our TenorClef changes are limited to `TaskRunner`, the new gate, and its tests.
- **Reported:** the recovered `fabric/run/client/gracenotetest-result.txt` said five earlier scenarios passed. A copy is retained in `grace-note-validation/recovered-live.txt`. Existing benchmark XML also reported a prior run. These were not treated as current validation.
- **Unknown:** which individual authored each uncommitted line. The handoff attributes development to Claude; Git cannot authenticate authorship of uncommitted files. No separate Grace Note completion report was found. PPE/Threat reports were inspected, not accepted as proof of Grace Note completion.

## Capability matrix

| Capability | Status | Evidence | Remaining work |
|---|---|---|---|
| Danger screening | Verified, implemented/tested | `GraceNote.screen`, core tests | Wider terrain and effect cases |
| Direct PPE fall detection | Verified, live-tested | Three successful final live clutches | More fall geometries |
| Threat integration | Verified, context only | Snapshot level/note; missing-threat core test | Freshness policy and entity responses |
| Hazard prioritization | Partial | Fall/lethality/planned-fall rules | Competing independent hazards |
| Feasibility/resources | Verified, unit-tested | Reach/look window, bucket, evaporation, replacement, policy checks | Non-hotbar resources and more surfaces |
| Prediction acceptance | Verified, unit-tested | `ForecastTrustTest`, current-tick authorization | More live invalidation/impulse cases |
| Emergency acquisition | Verified for tested path owner | Process identity plus executor hold, late input check | Arbitrary mods/listeners and human input |
| Active movement interruption | Verified, live-tested | `live-pathing-31`, original goal reached afterward | Combined TenorClef runtime |
| Water maneuver | Verified, live-tested | 15- and 31-block falls, zero observed damage | Waterlogged/interactive/neighbor cells |
| Outcome verification | Verified, client observation | Persistent target water, fall reset, health settle, rejection tests | Explicit server acknowledgments and high latency |
| Release | Verified, unit/live-tested | Hold observed gone; timeout classified FAILED_CONTROL | More competing-owner runtime tests |
| TenorClef notification | Implemented, polling boundary | `GraceNoteTaskGate` reads immutable status/outcome | Combined runtime validation |
| Task recovery/replanning | Implemented | Wait through verification, interrupt after confirmed release, re-evaluate | Real task replay, policy integration |
| Death/unload/dimension/cancellation | Verified, scripted tests | `GraceNoteCoreTest` | Controlled live fault injection |
| Anti-thrashing | Verified, scripted tests | Airborne-span guard, cooldown, sliding window | Sustained real hazard sequences |
| Telemetry/performance | Measured | Benchmark and live logs below | Cold-start and capture budget acceptance |
| Version compatibility | 26.3 Ostinato only | Java 25 build and runtime | Separate mechanics audit for each port |
| Live-enabled deployment | No | Default remains OBSERVE | Deliberate rollout after integration validation |

## Defects corrected

1. A previously feasible episode could click after its prediction became missing/untrusted or resource feasibility changed. Click permission now expires every tick and must be renewed by the current feasible forecast; an out-of-tick late callback cannot authorize use.
2. The adapter accepted partial and outcome-changing approximate predictions. It now requires complete results, acceptable confidence and section age within PPE's 20-tick reuse contract; stale/superseded and other outcome-changing assumptions are rejected. Each player state is still captured synchronously for the current request. A moved landing block invalidates the maneuver; within-block landing overlap is refreshed.
3. A release timeout could still produce AVOIDED. Unobserved release and control loss, including loss after execution, now produce FAILED_CONTROL. The adapter also verifies the actual controlling process before a click.
4. A late observation of the empty bucket could overwrite RELEASING_CONTROL and prevent release completion. Post-tick execution observation now preserves an outstanding release.
5. An exempt body could start from an old dangerous forecast. Ground/water/exemption gates now apply in the core; newly exempt execution stands down.
6. Success could be attributed to an empty bucket without persistent target water. Success now requires the water observation; AVOIDED also requires observed water contact/fall reset. Water recovery starts only with ground support.
7. TenorClef task priority/tick callbacks continued while Grace Note held movement. The optional reflective gate now pauses them, waits for the matching outcome and verified release, and invokes task interruption/re-evaluation afterward. API read failures wait; absent APIs on older versions remain usable. No physics or maneuver tuning was moved to TenorClef.
8. The old nonlethal active-path test could legitimately defer to `MovementFall`, making it unsuitable as a mandatory preemption check. Its drop is now lethal so both possible path states require emergency preemption.

Emergency prediction requests now carry a cooperative 10 ms deadline. Terrain capture still occurs on the client thread and is not preempted by that deadline; this is not a hard wall-clock guarantee.

## Mechanics audit

The target is Minecraft **26.3 / Java 25**, established by `gradle.properties` and the executed game. Inspected local 26.3 `LivingEntity` source confirms safe-fall-distance and fall-damage-multiplier attributes and the floor/epsilon rule; no legacy fall formula was substituted. PPE's vanilla collision oracle executed 600 deterministic cases inside its test.

`grace-note-validation/bucket-26.3-bytecode.txt` records `javap -c -p` against the actual cached 26.3 game jar. It verifies full/empty bucket raycast modes (NONE/SOURCE_ONLY), liquid-container handling, and `WATER_EVAPORATES`. Jar SHA-256: `DBD930BB6E43100E0AF2C02205D0F4E1AD17E48130EECB1181103B4D42757D25`.

The runtime test exercises item-use timing, landing, physical interruption and client/server gameplay in an integrated server. The production `acknowledged` field means water remained visible for another client tick; it is **not** an explicit server acknowledgment. The ten-tick health settle cannot prove immunity to delayed rejection on arbitrary servers. No new projectile, explosion, block-placement, knockback or acceleration mechanics were introduced. Their full independent correctness remains outside this completed slice; PPE and Threat regression tests are supporting evidence, not proof of all Minecraft mechanics.

## Validation and measurements

- **Verified:** final Ostinato filtered run: **116 tests/benchmarks, zero failures, zero skips**. Includes 39 Grace Note core tests, 3 forecast acceptance tests, its benchmark, PPE core/scheduler/region/vanilla checks and Threat checks. `grace-note-validation/tests.json` and `tests.log` record the result.
- **Verified:** TenorClef 26.3 compiled and **237 tests passed, zero failures/skips**, including four task-gate tests. The gate tests cover absent APIs, matching outcomes/release, API loss during takeover, and failed reads. These are boundary unit tests, not a combined game/task integration test. Suite counts and timestamps are in `grace-note-validation/tenor-26.3-suites.json`.
- **Verified:** TenorClef 1.16.1 also compiled and the same four task-gate tests passed with zero failures/skips. The optional boundary therefore compiles on that older target; this does not port Grace Note or PPE to 1.16.1. XML results and the successful combined build log are retained in `grace-note-validation/tenor-1.16.1-tests.xml` and `tenor-tests.log`.
- **Verified:** final integrated-server run: five scenarios passed — observe-only 15; no-bucket 15; live standing 15; live pathing 31 with goal resumption; live lethal 31. All three interventions reported zero observed damage and recovered water. Fresh disposable worlds only; no existing valuable save was used. See `grace-note-validation/live-acceptance.txt`.
- **Measured:** core-only warmed idle tick, 20,000 samples: p50 100 ns, p95 200 ns, p99 200 ns, 80 bytes/tick. Scripted full episode, 5,000 samples: p50 2.2 microseconds, p95 5.4, p99 9.4; 24 scripted ticks. These exclude Minecraft and PPE capture.
- **Measured:** final live sample, 509 tick deltas: p50 25.9 microseconds, p95 817.3 microseconds, p99 2.4483 ms, maximum 14.6202 ms. Service telemetry's separate maximum was 18.4498 ms; maximum prediction call 4.8426 ms. One-time warm-up was **105.2778 ms**, outside the emergency maximum. No speedup claim is made.
- **Measured:** acquisition began on the same recorded tick as its request in all three successful live interventions. Placement occurred 13 ticks after detection for the 15-block fall and 23 ticks afterward for the 31-block falls, when the landing entered reach. This is not network-latency measurement.
- **Verified:** whitespace checks passed for tracked diffs. Source hashes for the tested Grace Note files are retained alongside the evidence.

Validation exposed and corrected an error introduced during this continuation: requiring terrain-section age zero suppressed clutches. PPE deliberately reuses unchanged sections for up to 20 ticks; the final contract and boundary test reflect that. A later runtime launch and TenorClef compile ran out of native resources. An overlapping Ostinato build/test attempt also reported missing `ThreatPublication` classes. Sequential capped runs replaced those failed attempts; the final Ostinato results above are from those successful runs. Logs of unsuccessful attempts remain at the worktree root.

## Changed files and reproduction

Continuation edits in Ostinato: `src/main/java/baritone/gracenote/{GraceNote,GraceNoteCore,GraceNoteLiveTest}.java`; new `ForecastTrust.java`; extended `src/test/java/baritone/gracenote/GraceNoteCoreTest.java`; new `ForecastTrustTest.java`; this report and `docs/grace-note-validation/` evidence. Earlier uncommitted API/behavior/PPE/Threat work remains intact.

Continuation edits in TenorClef: `src/main/java/adris/altoclef/tasksystem/TaskRunner.java`; new `GraceNoteTaskGate.java`; new `src/test/java/adris/altoclef/tasksystem/GraceNoteTaskGateTest.java`.

From Ostinato-ppe, run `gradlew.bat test --tests "baritone.gracenote.*" --tests "baritone.physics.*" --tests "baritone.threat.*" -Postinato_bench --max-workers=1`. For the disposable game harness use `:fabric:runClient -PclientXmx=768M`, with `JAVA_TOOL_OPTIONS` containing `-XX:ActiveProcessorCount=4 -Dostinato.noMouseGrab=true -Dostinato.testHarness=true -Dostinato.gracenotetest=true -Dostinato.gracenotetest.exit=true`. The harness enters from the title screen and creates a fresh world. Do not run builds concurrently against the same output directory.

From TenorClef-main on a JDK 21 Gradle host with a JDK 25 toolchain, run `gradlew.bat :26.3:test :1.16.1:test --tests "*GraceNoteTaskGateTest" --max-workers=1`.

## Remaining limits and next slice

The next engineering slice is **combined TenorClef/Ostinato task-recovery runtime validation**, including ownership conflicts, world replacement, task callbacks, server rejection and delayed health. The gate's matching-outcome and verified-release requirements intentionally keep tasks paused if release cannot be established; they do not fabricate recovery. Strategic resource-policy configuration is still provided by the Ostinato API, not a completed TenorClef policy UI.

Cold warm-up still exceeded a tick budget. Live observations on one local integrated server do not validate dedicated servers, packet delay, external mods, arbitrary later listeners, human input conflicts, every landing surface, multiple simultaneous threats, or older-version physics. General live intervention remains opt-in; no additional hazard families were added.
