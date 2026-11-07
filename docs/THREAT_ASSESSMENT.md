# Threat assessment (26.3)

## Pre-edit audit and implementation plan

2026-10-30. Evidence: Verified means inspected code; Measured means executed here;
Reported means prior PPE documentation; Unknown means not established.

- Verified: base Ostinato 3d0b1674 is a dirty 1.16.1 checkout. Worktree
  Ostinato-ppe is feat/predictive-physics at 1aed5cc2 with uncommitted PPE.
  TenorClef-main is main at 983cf317 with unrelated tracker/maze/resource edits.
  Existing worktrees were inspected. Build on the existing PPE tree without
  copying, reverting, committing or porting its uncommitted implementation.
- Verified: PPE is 26.3/Java 25 only. Other Ostinato versions live on branches;
  TenorClef builds 1.16.1, 1.21.4, 1.21.11 and 26.3 through preprocessing.
- Verified: IPredictivePhysics captures bodies on the client thread, simulates
  projectiles and blast exposure/damage, uses a 64-job priority queue and 256-result
  cache. Tickets support cancellation/deadlines. Partial does not imply safety.
  Terrain copies expire after 20 ticks, block/chunk events invalidate them, world
  events cancel work. Results carry age, horizon and approximation flags.
- Verified: BodyCapture exposes velocity, defenses and client-interpolation flags.
  DamageMath supplies armor, resistance and difficulty arithmetic. CombatGeometry
  is package-private and contains combat-oriented heuristics; use game melee
  geometry and sight methods, not another projectile integrator.
- Verified: TenorClef EntityTracker/ThreatSignalCollector classify nearby hostiles;
  its ThreatMonitor already controls goal interruption. New intelligence must stay
  separate from this existing policy. The @threat diagnostic is a consumer point.
- Verified: Behavior receives PRE/POST tick, world, block and chunk events. New
  capture runs only on the client thread; public results contain values only.
- Reported: PPE tests and dry TNT integrated-server validation passed previously.
  Those results do not validate this new threat consumer.
- Unknown: server AI targets/cooldowns, custom creeper power/fuse, projectile damage
  payloads, future escape decisions, and dedicated-server parity.

Plan: immutable value API and deterministic rules; bounded client observation;
reuse the PPE scheduler for one selected prediction per tick; expose diagnostics
to TenorClef without altering policy; scenario tests, lifecycle tests and a warmed
allocation/latency benchmark. No commands or combat/movement control.

## Mechanics evidence

Inspected the installed 26.3 merged game jar with javap -c -p and existing local
decompiled 26.3 sources. MeleeAttackGoal.canPerformAttack checks cooldown,
Mob.isWithinMeleeAttackRange and sensing LOS; reset cooldown uses 20 adjusted ticks.
We do not infer the server cooldown from proximity. Creeper defaults are fuse 30,
radius 3; powered blasts double power. Swell direction, ignition and powered state
are synchronized; custom radius/maxSwell are not exposed as reliable client data.
Use swelling as evidence, with unknown exact impact time and explicit default-power
assumption. AbstractArrow's damage payload is not publicly readable: report impact
geometry without inventing damage. DamageMath and PPE retain their existing
version-specific formulas. Release reference:
https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3

## Implemented API and rules

`IBaritone.getThreatAssessment()` exposes `IThreatAssessment.latest()` and
`telemetry()`. `ThreatSnapshot` has generation/tick/subject/dimension, up to 32
entity sources plus an environmental source, top five, environmental observations,
truncation and availability status. Its maximum is the maximum individual ordinal,
not a sum; simultaneous-immediate count is descriptive only. All public collections
are copied; no game objects or PPE result arrays escape. A retained snapshot is
historical. Consumers must check status, generation and tick, not just level zero.

Rules are intentionally categorical:

- 0: no identified source (not a safety guarantee).
- 1: covered capability, unknown-intent player, or a completed projectile forecast
  without subject intersection within its horizon.
- 2: plausible exposed capability without immediate attack evidence.
- 3: melee geometry without target evidence, a later predicted projectile hit, or
  observed environmental harm; minor estimated melee damage also limits a 4 to 3.
- 4: observed aggression in melee geometry, near active fuse, imminent predicted
  projectile intersection, or an ongoing environmental hazard with health <=4.
- 5: estimated lethal damage plus imminent exposure and attack/fuse evidence. This
  is conditional on the reported assumptions, never high-confidence theoretical
  lethality based only on entity type.

The 2-damage minor threshold, 4-health environmental threshold, 10-tick immediate
projectile window and 0.9 swelling boundary are inspectable initial categories,
not Minecraft attack constants or calibrated probabilities. `damage` is an estimate,
not a guaranteed lower/upper bound. Zombie damage uses difficulty and armor math;
unknown enchantments, resistance, shield and authoritative attribute values limit
confidence. Blast damage reuses PPE's defenses. No precision is invented for arrow
payload damage, cooldowns, AI paths, exact custom fuse time or future melee contact.
Zero contact ticks means currently inside game melee geometry, not an imminent
server swing. Projectile ticks come from PPE and are aged before publication.

Sight uses the game's eye-ray method. Blocked sight reduces direct melee exposure;
it does not prove an impassable path or complete blast cover. PPE samples blast
exposure separately. Active fuses outside the assumed 6/12-block vanilla blast
radius are not rated imminent simply because they are swelling. Powered/default
blast assumptions remain explicit. Passive entities are omitted unless a mob's
observable target identifies the subject. Player capability alone remains level 1.

Confidence remains LIMITED for fresh client assessments and UNKNOWN for stale
observations; the OBSERVED category is reserved rather than falsely claiming server
intent parity. Unknown damage does not imply zero; it also cannot manufacture a
lethal prediction. No temporal smoothing hides a new critical result. Stable UUID
tie ordering prevents source ordering jitter; transitions around evidence boundaries
remain visible. Hysteresis is deferred pending measured transition traces.

## Scheduling, lifecycle and integration

Each PRE tick screens at most 512 loaded/renderable entities within 32 blocks and
keeps 32, prioritizing arrows/active creepers then distance. Exceeding either bound
sets truncation; threats beyond the scanned prefix can be missed. This is a bounded
coverage tradeoff, not a claim of exhaustive discovery. Detailed LOS/reach work is
bounded by 32. The service has no separate executor or unbounded queue. It submits
at most one 20-tick arrow/trident or instantaneous conditional blast query per tick,
at PLANNING priority, SCREENING fidelity, 40 ms deadline, maximum four outstanding.
Queries rotate among selected eligible hazards. Very crowded scenes can delay
prediction; observation scoring still runs. Terrain capture can overrun a frame:
work-count bounds are not hard real-time guarantees.

Pending work is cancelled on source disappearance, world/block/chunk change or age
over two ticks; replacing the local player also advances the generation.
Position/defense changes reject retained answers. Moving projectiles
allow a one-tick-old conditional flight forecast, explicitly flagged. Already elapsed
impacts, stale/unloaded terrain, partial results and failed/deadline answers do not
establish a safe miss. Terrain age is recorded separately and capped at PPE's 20-tick
window; invalidation flags still reject younger snapshots. Publication rejects old generations and older ticks. There
is no additional result cache; PPE's own cache/dedup and telemetry remain authoritative.

TenorClef's `OstinatoThreatBridge` reads the actual immutable result through an
optional reflective boundary so its older pinned jars still compile. `@threat`
prints the top sources, reasons, confidence, uncertainty, environmental observations
and truncation alongside the existing policy monitor. Callers can obtain the same
read-only view with `OstinatoThreatBridge.read(mod.getClientBaritone())`. It does not
feed the old monitor or change its pre-existing pause/fail behavior. New movement,
inventory, combat and task interruption policies are not installed.

## Validation scope and reproduction

Run from Ostinato-ppe (Java 25):

```powershell
.\gradlew.bat test -Postinato_bench -PbenchXmx=512m --tests baritone.threat.ThreatRulesTest --tests baritone.threat.ThreatBridgeTest --tests baritone.threat.ThreatBenchmark --tests baritone.physics.ThreatPredictionTest --tests baritone.physics.PpeCoreTest --tests baritone.physics.PpeSchedulerTest --tests baritone.physics.PpeVanillaTest --tests baritone.physics.mc.PpeRegionTest
```

The bridge test compiles the actual sibling TenorClef-main adapter with `--release
8` and consumes actual Ostinato snapshots. It explicitly skips if that checkout is
absent; do not count a skip as integration validation. Rule tests exercise cover,
near/open and distant sources, low health, armor, passive/player intent, fuse states,
multiple sources, unknown data, unsupported mechanics, age, timeout and sudden
critical promotion. Publication tests exercise dimension/world/chunk invalidation,
disappearance and out-of-order rejection; these are not end-to-end packet tests.
Simulated-world tests invoke the real PPE for incoming/outgoing arrows and covered/
exposed blasts. Existing PPE regressions include the version-matched collision oracle.

The opt-in `ThreatLiveTest` requires BOTH `ostinato.testHarness=true` and
`ostinato.threattest=true`. It creates a fresh world from the title screen, prepares
a small high-altitude floor and cover, and spawns one no-AI zombie. It records actual
client service results for covered/distant and close/exposed placements. It does not
attack, run combat controls, or open an existing save. `ostinato.threattest.exit=true`
closes only that test client afterwards. Output is `fabric/run/client/threattest-result.txt`.

## Limitations and next slice

Implemented: local-player observation, selected melee/ranged capability, player
intent caution, creeper fuse state, arrow/trident intersection, conditional blast
damage, and current lava/fire/air observations. Other subject identities are supported
by the pure value rules, but live capture is deliberately local-player only.

Unsupported: predictive knockback hazards/escape routes, combined-damage forecasts,
TNT/minecart/bed/crystal blast consumers, boss mechanics, projectile payload/homing/
piercing/owner/team rules, fully priced melee enchantments/status effects, moving
target interception, custom server AI/fuses/power, older-version PPE adapters,
protected locations, and automatic strategic policy. Environmental fall flags do
not predict a landing. Client rendering velocity and visible AI state are not
authoritative server intent. Missing chunk contents can affect direct sight.

Not measured by the pure benchmark: terrain capture, sight queries, total gameplay
tick overhead, real-world false-positive/negative rates or dedicated-server behavior.
No accuracy claim follows merely from passing unit tests. Next: server-controlled
projectile/fuse replay with moving subjects, event-to-result latency and discrepancy
counts, then evidence-based cache/hysteresis and targeted version ports.

## Exact implementation files

Ostinato-ppe additions:

- `src/api/java/baritone/api/threat/IThreatAssessment.java`
- `src/api/java/baritone/api/threat/ThreatAssessment.java`
- `src/api/java/baritone/api/threat/ThreatSnapshot.java`
- `src/main/java/baritone/threat/ThreatRules.java`
- `src/main/java/baritone/threat/ThreatPublication.java`
- `src/main/java/baritone/threat/ThreatService.java`
- `src/main/java/baritone/threat/ThreatLiveTest.java`
- `src/test/java/baritone/threat/ThreatRulesTest.java`
- `src/test/java/baritone/threat/ThreatBridgeTest.java`
- `src/test/java/baritone/threat/ThreatBenchmark.java`
- `src/test/java/baritone/physics/ThreatPredictionTest.java`
- `docs/THREAT_ASSESSMENT.md` and `docs/threat-validation/` evidence files.

Ostinato-ppe narrow edits to files already containing uncommitted PPE work:

- `src/api/java/baritone/api/IBaritone.java`: threat getter only.
- `src/main/java/baritone/Baritone.java`: service field, behavior registration and getter.
- `src/main/java/baritone/harness/TestHarness.java`: opt-in threat harness only.

TenorClef-main:

- Added `src/main/java/adris/altoclef/threat/OstinatoThreatBridge.java`.
- Updated `src/main/java/adris/altoclef/commands/ThreatCommand.java` to display intelligence.

Existing PPE files and unrelated TenorClef edits are not part of this change.
No commits, pushes or dependency repins were performed. Install an Ostinato build
containing this service alongside TenorClef to use it: existing pinned older jars
correctly report UNSUPPORTED_OSTINATO_VERSION. The source-level bridge integration
test exercises the actual new results without silently replacing pinned releases.

## Measured live evidence (2026-10-30)

Minecraft 26.3 / Fabric integrated server, Intel i5-1145G7, Java 25, 768 MiB client
heap. To avoid native-memory exhaustion on this workstation the successful launch
used `-XX:ActiveProcessorCount=2 -XX:HeapBaseMinAddress=32g -XX:TieredStopAtLevel=1`,
one Gradle worker and a 768 MiB single-use Gradle daemon. These JVM settings affect
timing; this is not a normal optimized-JVM performance acceptance run.

- Covered no-AI zombie at 4 blocks: level 1, COVERED/BLOCKED, LIMITED confidence,
  damage estimate 3, exact future contact unknown.
- Same zombie at approximately 0.8 blocks after cover removal: level 3,
  IN_REACH/CLEAR, LIMITED confidence, geometric contact zero, no claimed cooldown.
- Harness assertion passed: covered < near-open. This is client-observed exposure
  with integrated-server scene setup. No attack/health-loss prediction or dedicated
  server was validated. No combat or movement controller was activated.
- 199 service samples: P50 83.8 us, P95 155.8 us, P99 313.5 us, maximum 833.1 us.
  All 300 service evaluations (including startup) totaled 38.0152 ms, maximum
  10.3508 ms. The warmed sample and startup maximum describe different windows.
- Counters: 11,376 scanned entities, 200 candidates, zero PPE requests, zero discarded
  predictions and zero truncated ticks. Thus this live run measures observation cost,
  not prediction latency or a nontrivial cache hit/stale-result rate.
- Two earlier launches/builds failed with native-memory allocation errors; no success
  is attributed to them. Raw successful live output is retained in threat-validation.

## Final executed checks and benchmark

- **Tested:** 72 checks, zero failures/errors/skips: 24 threat rule/publication tests,
  two real-PPE threat scenario tests, one actual TenorClef adapter integration test,
  one scoring benchmark, and 44 existing PPE regression tests. `tests.json` lists
  exact suite totals; `checks.txt` contains the successful Gradle output. The first
  projectile-away assertion was corrected to allow a terrain hit while excluding a
  subject hit; it had incorrectly required no collision at all.
- **Tested:** TenorClef `:26.3:compileJava :1.16.1:compileJava` passed with existing
  pinned Ostinato dependencies. The preprocessor printed Kotlin-metadata warnings
  but both requested compile tasks and the overall build succeeded. 1.21.4/1.21.11
  compilation was not separately executed in this task. The optional adapter itself
  was also compiled to Java 8 in the cross-repository test.
- **Measured:** 5,000 warmed batches of 32 pure assessments: P50 11.9 us, P95 38.9 us,
  P99 51.9 us, maximum 10,034.5 us; 21,063.9 allocated bytes per batch from the JVM
  thread allocation counter. The outlier was not profiled. This benchmark used
  normal tiered compilation with two active processors and a 512 MiB test heap;
  do not compare it causally to the live C1-only run. No performance improvement or
  hard latency guarantee is claimed.
- **Measured limitations:** live stale-result/truncation counters were zero in the
  recorded simple scene. With zero live PPE requests, prediction-cache effectiveness
  and prediction latency are not measured. Scenario assertions passed, but real-world
  false-positive/false-negative rates and behavior near score boundaries remain
  uncalibrated. The no-AI live fixture tests geometric exposure, not AI attack accuracy.
- **Built:** `fabric/build/libs/baritone-fabric-1.26.0-dirty.jar`; verified it contains
  Fabric metadata and the actual threat API/service classes. `artifact.txt` records
  its absolute path and SHA-256. This is a local review build including the existing
  uncommitted PPE, not a published release or replacement of TenorClef's pinned jars.
- **Reviewed:** no task/combat/movement calls in the observer or adapter; game-state
  reads stay on tick/event callbacks, public values are immutable, pending tickets
  cancel on invalidation, and disconnect releases retained player/world references.
  `git diff --check` passed for the implementation changes. No commits or pushes.

Evidence files: `threat-validation/live.txt`, `checks.txt`, `tests.json`,
`benchmark.txt`, `tenor-build.txt`, and `artifact.txt`. Build logs/crash dumps from
the native-memory failures remain local diagnostics, not implementation sources.
