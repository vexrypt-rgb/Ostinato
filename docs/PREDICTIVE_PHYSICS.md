# Predictive physics engine (PPE)

Status: implemented for the 26.3 main branch; validation scope and measurements are below. This is a prediction service, not an autonomous consumer. No changes are committed or pushed.

## Audit and boundaries

Evidence labels: **Verified** = inspected source or executed check; **Reported** = prior session claim; **Unknown** = not validated; **Contradicted** = source disagrees with a claim.

- **Verified:** `pathing/kinematic/PlayerSim` and its native counterpart already support planner movement. They remain untouched. Their 1.16-era mechanics are not a drop-in 26.3 entity simulator: the current game has gravity/drag/friction attributes, generalized bounce restitution, and candidate-height stepping.
- **Verified:** `health/DamageMath` provides armour/magic damage helpers reused here. PPE prices fall damage separately using the 26.3 safe-fall-distance and damage-multiplier attributes and the vanilla floor/epsilon rule.
- **Verified:** frozen collision boxes in existing snapshot code establish a repository precedent. `FallBudget`, `TickSim` and damage budgets are planning heuristics, not substitutes for entity simulation.
- **Verified:** the global Baritone executor is unsuitable for bounded PPE scheduling. PPE owns one lazy daemon worker, a bounded priority queue, and separate caller/worker simulators.
- **Reported:** Claude's initial compile and PlayerSim baseline passed before implementation (36 seconds). This is a build baseline, not a client performance baseline.
- **Verified:** `versions/README.md` says main is 26.3/Java 25; older versions have separate branches. No older-version compatibility is claimed.
- **Unknown:** client-predicted block edits before server confirmation, modded entity overrides, and server/client interpolation can alter outcomes. Snapshot age and assumption flags are required inputs to a consumer's decision.

Ostinato owns low-level prediction, collision, movement primitives and telemetry. TenorClef retains goals, tactics, task orchestration and action choice. No Grace Note, Secondo, Threat Assessment, Scherzo or Bombard behavior is implemented here.

## Architecture and lifecycle

`IBaritone.getPredictivePhysics()` returns `IPredictivePhysics`. Its world-facing calls run on the client thread. `capture(Entity)` reads a detached `BodyState`; unsupported entity families throw. `predict(request)` captures terrain and computes immediately; `submit(request)` captures terrain immediately and queues pure simulation. Capture is not moved off-thread. A `PredictionTicket` can be polled or cancelled; one cancelling consumer does not cancel another consumer's shared work.

The core under `baritone.physics` contains no live Minecraft world references. `Body` performs collision/step/landing mechanics; `LivingSim`, `ProjectileSim` and `ExplosionSim` implement family-specific rules. `Predictor` dispatches and records events. `physics.mc` owns block/entity capture, event invalidation, and service lifecycle. Minecraft defaults and formulas currently target **26.3**; MC-free does not mean version-independent physics. A port must audit these four simulation classes, `PhysMath`, body defaults and the capture layer against the new game, then rerun the vanilla and live oracles. Keep that port on its version branch/overlay rather than adding speculative providers today.

The worker starts only on first async submission. World replacement/disconnect cancels queued and running work, invalidates terrain and clears results. Restart uses a separate worker-local simulator, so an interrupted old generation cannot corrupt its successor. Inline work remains single-caller only. Callers should set deadlines and never block the game thread waiting for a ticket.

## Sharing, limits and performance

- Queue: 64 waiting jobs, FIFO per priority: EMERGENCY, COMBAT, MOVEMENT, COMPANION, PLANNING. More urgent arrivals may evict less urgent queued jobs. A running job is not priority-preempted; use inline `predict` for emergency work that cannot wait. Cancellation/deadline checks are cooperative.
- Cache: 256 results, LRU. A hash selects candidates; full request fields and exact section-copy identities/entity boxes establish equality. Consumer labels and scheduling metadata do not change the physics question. Hash collisions cannot establish equivalence.
- Cache and deduplicated deliveries own separate path/block arrays. Mutation by one consumer cannot poison another consumer or the cache. Result scalar fields are final.
- Snapshot cache: 1024 sections; each capture copies/rechecks at most 64, nearest first. Region capped at 512 sections (normal reach caps are smaller). Missing coverage stops prediction as PARTIAL/OUT_OF_SNAPSHOT. Existing cached sections can be shared; unchanged rechecks preserve copy identity.
- Block/chunk events invalidate copies; age cap is 20 game ticks. Eviction conservatively marks outstanding copies stale. Staleness is checked again when reading a ticket, including cache-hit tickets. Snapshot age describes terrain at request capture, not a promise that a retained result is current forever. Entity state is frozen at capture; refresh requests when the subject changes.
- Horizon capped at 1200 ticks, explosion power at 16, target list at 256. Supported body collision envelope: width <=32, height <=64, step height <=16, per-axis initial velocity <=256 blocks/tick. These are input/resource limits, **not measured performance guarantees**. Invalid non-finite position/velocity inputs are rejected.
- SCREENING computes endpoints/events/exposure without a path or block-destruction bounds. STANDARD records every fourth tick. HIGH records every tick and computes explosion block bounds/chain TNT. VALIDATION currently has HIGH's mechanics/output; the opt-in harness does the comparison. Under queue pressure MOVEMENT and lower priorities may lose one fidelity level; DEGRADED is explicit and those answers are not cached as full fidelity.
- A main-thread capture budget is per request, not a global tick-time budget. Many distinct requests can still exceed a frame budget. The service is idle until requested. The counters do not imply that every possible workload is safe.

Telemetry reports requests, completions, cancellations, rejection/degradation, cache/dedup hits, queue depth/high water, section capture/reuse/invalidation, compute/wait histograms and service time per active tick. Histogram percentiles are upper bucket bounds (roughly 1.25x granularity); maxima are recorded. `tick*` measures **PPE service time**, not total game tick duration. The live harness separately measures PRE-to-POST client tick time. The standalone benchmark uses exact sorted samples and JVM allocation/CPU/GC counters. Windows CPU accounting can quantize short samples to zero.

## Results and safe consumption

Read `status`, `failure`, `horizonTicks`, `fidelity`, `snapshotAgeTicks`, and `assumptions` before using coordinates. `ok()` includes PARTIAL: it does not mean the requested horizon completed. OUT_OF_SNAPSHOT is a prefix only. EXPIRED, CANCELLED, REJECTED and FAILED have no usable prediction. Wrong-thread/no-world service calls return explicit failures. Unsupported capture types and malformed builders throw; unexpected simulation exceptions return FAILED/INTERNAL.

Confidence is categorical, not a probability. EXACT means no outcome-changing approximation was encountered **in this model**, conditional on held input, captured terrain and supported mechanics. It is not a claim of universal live parity. Random blast bounds give BOUNDED; unmodelled context, moving/frozen targets, stale/client state and other outcome-changing assumptions give APPROXIMATE. No usable answer gives UNKNOWN.

`landing` reports first landing, surface coordinates, distance, damage, lethality and a geometric reach tick. Reach does not establish that an inventory action, packet, line of sight or clutch will succeed. `hit` reports projectile impact; target bodies are frozen. `explosion` reports exposure, damage/knockback and optional certain/possible block sets. Chain TNT membership is reported; subsequent chain detonations are not recursively simulated.

## Integration example

On the client thread, retain the ticket and poll it on subsequent ticks:

```java
IPredictivePhysics physics = baritone.getPredictivePhysics();
BodyState subject = physics.capture(player);
PredictionTicket ticket = physics.submit(PredictionRequest.fall(subject, 60)
        .priority(PredictionRequest.Priority.MOVEMENT)
        .fidelity(PredictionRequest.Fidelity.STANDARD)
        .consumer("example")
        .deadlineIn(40_000_000L) // consumer's useful-time window; not an engine guarantee
        .build());
// Later, on the client thread:
PredictionResult result = ticket.result();
if (result != null && result.status == PredictionResult.Status.OK
        && !result.has(PredictionResult.Assumption.STALE_SNAPSHOT)) {
    // Inspect all remaining assumptions, age and landing; decide elsewhere whether to act.
}
// If the situation changes:
ticket.cancel();
```

For a velocity change use `subject.toBuilder().vel(vx, vy, vz).build()` and request a trajectory. For arrows/throwables/hurting projectiles use `projectile` with captured per-family drag/gravity and target bodies. For explosions specify centre, power, difficulty and target list; HIGH requests block bounds. Equal concurrent requests share computation.

Grace Note can consume fall/landing/reach information; Secondo can consume trajectories; Threat Assessment can consume contextual damage/exposure; Scherzo can consume impacts and hypothetical changed velocities; Bombard can consume projectile paths and TNT/blast bounds. Their policy, optimization/search and execution stay outside PPE. General impulse input is supported; reconstructing every damage-source-specific attack knockback rule is not.

## Validation and reproduction

From this worktree in PowerShell (exact class names avoid wildcard expansion by the Windows Gradle launcher):

```powershell
.\gradlew.bat test --tests baritone.physics.PpeCoreTest --tests baritone.physics.PpeSchedulerTest --tests baritone.physics.mc.PpeRegionTest --tests baritone.physics.PpeVanillaTest
.\gradlew.bat test -Postinato_bench --tests baritone.physics.PpeBench
$env:JAVA_TOOL_OPTIONS='-Dostinato.noMouseGrab=true -Dostinato.windowPos=2600,40 -Dostinato.testHarness=true -Dostinato.ppetest=true -Dostinato.ppetest.exit=true'
.\gradlew.bat :fabric:runClient -PclientXmx=1024M '-Dorg.gradle.jvmargs=-Xmx1536M'
Remove-Item Env:JAVA_TOOL_OPTIONS
```

Choose a window position suitable for your display. The live harness is gated by BOTH `testHarness` and `ostinato.ppetest`; it creates a fresh world from the title screen and never runs in an already-open save. It writes `fabric/run/client/ppetest-result.txt`. It changes a small test floor and detonates TNT only in that disposable world. It invokes actual server-side TNT ticks synchronously for reproducible parity, not a real-time network/interpolation test. Client-service timing is sampled separately before the server oracle.

Core tests cover stationary/walk/sprint/jump/fall, floor/wall/ceiling/slab collision, fluid/climb/effects, projectile hits, TNT fuse, blast exposure/damage/bounds and cancellation. Scheduler tests cover priority, sharing, invalidation, deadlines, pressure, restart, collision-safe keys and per-consumer arrays. `PpeVanillaTest` compares 600 deterministic slab/wall/corner moves against the actual 26.3 `Entity.collideWithShapes` method. This does not cover step-up parity or all block contexts.

Measured outputs are recorded in `docs/ppe-validation/`. Do not infer deployment limits from a single workstation run. Benchmarks exclude terrain capture unless explicitly marked live; truncated projectile paths are labelled PARTIAL. The empty-op baseline is a harness control, not a PPE-disabled Minecraft baseline. Idle client timing includes the registered, unused service; a separate disabled build was not measured.

## Remaining limitations

Flowing currents, context-dependent block/entity shapes, sneaking edge back-off, changing poses/input and moving entity trajectories remain approximations with flags. A primed TNT's blast power is not synced to clients (26.3 syncs its fuse and block state only, and has no getter), so `capture` uses vanilla's 4 and marks the body `BodyState.POWER_ASSUMED`, which the answer reports as `Assumption.TNT_POWER_ASSUMED`; a caller that states the power itself does not get the flag, and a server-side override is still unobservable. Portal-altered explosions, special damage calculators, modded enchantments/mechanics, projectile payload behavior (homing/loyalty/return/piercing details), AI controls and world changes after capture need more validation or explicit support. Explosion block bounds assume the default calculator and pre-destruction world. Live tests cover TNT motion/fuse on three dry surfaces, not water TNT, explosion damage/exposure, every projectile family or all living movement mechanics. Fatality estimates do not model future healing, totems or external interventions.

The hardening pass fixed worker-generation reuse, running-job shutdown, stale completed/cache-hit tickets, hash-only equivalence, mutable shared result arrays, explosion deadline checks, fidelity gating and explicit tick-phase handling. A later pass made the unreadable primed-TNT blast power explicit (`Assumption.TNT_POWER_ASSUMED`) instead of leaving an invented value in an answer labelled EXACT; it records the assumption and does not change the confidence rule, matching how `VANILLA_ENCHANTMENTS` is treated. These fixes extend Claude's implementation without changing existing planners or adding consumers. Full performance acceptance and wider version/mechanics validation remain separate evidence requirements; this document does not claim them complete.

## Results from this continuation (2026-10-30)

Environment: Windows, Intel Core i5-1145G7, Java 25. The live run used Minecraft 26.3/Fabric with a 1 GiB client heap. Background load, JIT, GC and OS scheduling were not controlled; these are workstation observations, not statistically isolated engine costs.

The direct vanilla collision oracle passed 600 movements. The live server-side oracle passed stone, slab and slime, 80 TNT ticks each, with **zero measured position/velocity difference** and matching fuse expiry. This supports the tested dry TNT cases only.

In the live client, 200 idle ticks averaged 2.319 ms (P99 3.851 ms). The next 200 ticks, each with five equivalent fall requests, averaged 3.385 ms (P99 7.422 ms). Capture plus those five calls averaged 1.246 ms, P95 0.880 ms, P99 5.089 ms, worst 158.180 ms. The service reported 800 cache hits / 1000 requests and seven copied sections. These sequential phases are not a causal overhead estimate: the active phase includes first use, and the 158 ms outlier has not been isolated to classloading, GC or scheduling. **Performance acceptance remains open; do not promise emergency-tick latency from this run.** Next performance work should profile that outlier with JFR and compare warmed, interleaved idle/active phases and a service-disabled build.

Raw benchmark and live evidence are retained beside this guide; the final 60 tests passed with no failures or skips (including the benchmark); totals are in `ppe-validation/tests.txt`. The standalone benchmark is a warmed harness (even its “cold” label means cache miss, not cold JVM). Cache correctness and per-consumer copies intentionally cost more than hash-only unsafe reuse. No commits or pushes were made.
