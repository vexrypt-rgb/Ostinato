# Ostinato 2.0 — review of Phases 00–03

Reviewer: Claude, on local branch `ostinato2/claude-review`. Date: 2026-11-04.

Evidence labels: **VERIFIED** means I reran it or read the code at the named commit myself. **REPORTED** means it is Grok's claim and I did not reproduce it. **UNKNOWN** means nobody measured it.
Tiers: **U** is unit or contract, **S** is headless simulation, **L** is a live Minecraft client or server.
Nothing in this review is Tier-L unless a live log is named.

This file was committed before any code change (Step 4). The post-fix status is added at the end, in a separate commit.

## 1. What exists (Step 1)

| Item | Value | Label |
|---|---|---|
| Ostinato remote | `vexrypt-rgb/Ostinato`; `origin/main` = `67e12b96e` | VERIFIED |
| Grok's working branch | `feature/secondo-v1`, later combined into the `Ostinato-combined` tree; Phase 02 HEAD `a1e9817cd` | VERIFIED |
| Baseline commit | `67e12b96e` (`origin/main`), same tree as `2f4176049` on secondo where Phase 00 was written | VERIFIED |
| Phase 00 | docs only, committed together with Phase 01 in `878606eb2` | VERIFIED |
| Phase 01 | `878606eb2` | VERIFIED |
| Phase 02 | `a1e9817cd` | VERIFIED |
| Phase 03 | **uncommitted** in Grok's tree; I snapshotted it as `09631d874` (parent `a1e9817cd`) as the first commit of `ostinato2/claude-review` | VERIFIED |
| Commits after Phase 03 | none | VERIFIED |
| Between baseline and Phase 01 | owner-authorized merges (maze, PPE `ab615b7f3`), 274 files, about 97k lines. They are not 2.0 phase work. | VERIFIED |
| Handoff files | `STATUS.md`, `DECISIONS.md`, `BASELINE.md`, `BENCHMARKS.md` and `raw/` exist | VERIFIED |
| Remote safety | no force-push and `main` untouched. Grok's branch is not modified by this review. Push is disabled in my clone (`remote.origin.pushurl = DISABLED-no-push`). | VERIFIED |

The phases are not cleanly separated: Phase 00 and Phase 01 share a commit, and Phase 03 was never committed. The endpoint is still unambiguous, because Grok's working tree had only Phase 03 changes on top of `a1e9817cd` and the snapshot reproduces that tree exactly (CRLF differences only). Because of that, I did not use the "interleaved phases" stop condition.

Toolchain (VERIFIED from the build files and logs):
- MC 26.3, fabric loader, Unimined 1.4.2, `mod_version` 1.24.0.
- Gradle 9.4.1 running on JDK 21. The Java 25 toolchain is Temurin 25.0.4.1.

| Build | Command | Result |
|---|---|---|
| Baseline `67e12b96e` (worktree `Ostinato-o2base`) | Phase 00 command below | `BUILD SUCCESSFUL in 48s` |
| Endpoint `09631d874` | `gradlew --offline --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2G test -x nativeHost` | `1465 tests completed, 1 failed, 18 skipped` (see F-M6) |
| Endpoint fabric jar | `gradlew --offline --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2G :fabric:build -x test -x nativeHost` | `BUILD SUCCESSFUL in 13s`, `fabric/build/libs/baritone-fabric-1.26.0-7-g09631d874.jar` |
| TenorClef `983cf317` against the endpoint jar | `gradlew --offline --no-daemon --max-workers=2 :26.3:compileJava -PostinatoJar=<endpoint jar>` | see §7 |

## 2. Verdicts before fixes

| Phase | Verdict | Main reason |
|---|---|---|
| 00 baseline | **PASS WITH FIXES** | Reproduces exactly. Fixes: one rep only (no variance), and the benchmark version is implicit. |
| 01 input ownership | **PASS WITH FIXES** | Arbiter is correct for what it covers. Missing: timeout-without-ack test, exclusivity invariant, release on executor discard and disconnect. |
| 02 contracts | **PASS WITH FIXES** | Types are minimal and used. Fixes: "observed" is self-consistency only, and stock navigation rows are not executed. |
| 03 lattice | **PASS WITH FIXES** | Isolated and default-off. Missing: comparison against current navigation, p95/p99, CIs, and ground truth for prediction. The REVISE decision must be **provisional**. |

No Phase 01 or 02 finding needs a redesign, so Phase 04 may proceed after the fixes.

## 3. Findings, ranked by severity

### High

**F-H1 (Phase 03, and Phase 02's stock row): no comparison against current navigation.**
- The Phase 03 "physics" baseline is `PhysicsPathfinder`, an experimental Ostinato planner. It is not the production navigation (`AStarPathFinder` over `Moves`, executed by `PathExecutor`).
- Phase 02's `stock-astar` row is labeled NOT_EQUIVALENT and is never run (D8).
- The gate asks for beam search and weighted A* "compared vs current navigation".
- Stock A* can be run headlessly at planner tier through `PathBench`'s `SyntheticWorld` + `calculate0` path (VERIFIED, `PathBench` lines 237–260). That makes a planner-tier comparison on the same worlds possible: success, latency and expansions. Executed travel ticks remain impossible headlessly because of D2.
- **Fix:** add stock A* rows on the same lattice worlds, labeled as a planner-tier comparison that is NOT_EQUIVALENT on travel ticks.

**F-H2 (Phases 02 and 03): "observed" is the predictor itself.**
- Phase 02 `physics-search`: the plan comes from `PhysicsPathfinder`, which searches with `PlayerSim`, and it is replayed through `PlayerSimPhysics`, which is also `PlayerSim`.
- Phase 03 `LatticeSearch.divergence()` replays the same `PlayerSimPhysics`.
- So `max divergence: 0.000000` holds by construction. It is self-consistency (and determinism), not prediction accuracy.
- The "prediction failure vs planner failure" split therefore cannot fire. Its only ground truth would be a different model or the live game.
- No predicted-vs-observed check per movement mode exists in Phases 00–03.
- Real Tier-L traces do exist outside these phases:
  - `Ostinato-bastion-263/fabric/run/client/simtrace/` holds 26.3 live SIMTRACE runs from 2026-10-14: 14,225 tick rows and 934 movement runs (jump 869, climb 49, chain 16).
  - I checked `PlayerSim` from that commit (`eb3b33dbe`) against the endpoint. The only changes are a refactor into `tickImpulse`, documented as bit-identical, and `StrictMath` sin/cos. **VERIFIED by diff, Tier-U**. Those traces remain valid ground truth for the sprint-jump and ladder modes.
- **Fix:** add the per-mode prediction check: Tier-L from the historical traces where they exist, Tier-S cross-model (PlayerSim vs PPE) elsewhere, and missing Tier-L recorded as a blocker.

**F-H3 (Phase 03): the statistics do not support a firm decision.**
- The sample is 12 dev and 16 held-out hand-built worlds. They are deterministic, so seeds do not apply.
- The 3 "trials" repeat identical searches and only re-sample timing.
- Held-out success is beam 16/16 vs physics 14/16. That difference is not significant: two-sided Fisher exact p ≈ 0.48. The Wilson 95% intervals are [0.806, 1.0] and [0.640, 0.965].
- No p95/p99 latency is reported, only medians. "Tick overhead" is not measured at all: the lattice never runs in a tick.
- REVISE follows mechanically from the D9 rule. The rule's expansion clause is unevaluable (`expansions=-1` for physics), so CONTINUE was unreachable whatever the data showed.
- **Fix:** report p50/p95/p99 and Wilson CIs, expose the `PhysicsPathfinder` expansion count if that is trivial, and mark D9 **PROVISIONAL**.

### Medium

**F-M1 (Phase 01): lease leak when the executor is discarded mid-fall.**
- `FallController` lives inside each `PathExecutor` (`PathExecutor` line 117). The `InputArbiter` lives on `Baritone` and outlives every executor.
- The lease ends only in `FallController.tick` (the `r < 0` path or the catch path, lines 57–71).
- A `PathExecutor` cancelled, replaced or spliced while `fall` holds MOVE/JUMP/SNEAK/CLICK leaves that lease live in the arbiter. Nothing releases it on world change or disconnect either.
- Effect today is latent. `fall` is the only arbiter client, the next `FallController` re-claims under the same id, and `PathingBehavior` clears the raw keys separately. Once a second client exists, though, a dead executor's lease would block lower-priority claimants.
- The flag defaults to off, so production is unaffected.
- **Fix:** add an arbiter `releaseAll()`/owner release, call it on executor cancel and on disconnect, and test both.

**F-M2 (Phase 01): two required tests are missing.**
- There is no "timeout without ack is not treated as stopped" test. `cancelRequestHoldsKeysUntilRelease` checks one seal. The arbiter has no timeout at all, so the property holds, but it is untested over many ticks.
- There is no "never two owners in one tick" invariant test over arbitrary claim orders. Only the specific merge and preemption cases are tested.
- Release on exception is tested (`controllerFailureClearsMovementWithoutCancelling`). Release on disconnect is not tested because it does not exist (F-M1).
- **Fix:** add both tests.

**F-M3 (Phase 01): bypass writes.**
- 31 main source files besides `InputOverrideHandler` call `setInputForceState`/`clearAllKeys` directly (`grep -rln` count, VERIFIED). Only `FallController` goes through the arbiter.
- With the flag on, `FallController.writeApplied` calls `clearAllKeys()` and then writes its frame. Any other writer in the same tick still bypasses ownership.
- This is acceptable for a first migration step behind a default-off flag, and Grok documents it (D7). The arbiter is not yet authoritative, and the flag must not be turned on for production on the strength of Phase 01. **Recorded, not fixed** (migration is later-phase work).

**F-M4 (Phase 03): the test overwrites committed evidence.**
- `LatticeExperimentTest.benchIsReproducibleAndWritesTheRawTable` rewrites `docs/ostinato2/raw/phase03-*.{csv,txt}` on every `gradlew test`.
- My full-suite run silently changed the committed timing columns. I restored them with `git checkout -- docs/ostinato2/raw/`.
- **Fix:** write to `build/` by default and to `docs/` only on an explicit opt-in property.

**F-M5 (Phase 00): no variance.**
- The baseline is `OSTINATO_BENCH_REPS=1`. The gate asks for a rerun "within its reported variance", and no variance was reported.
- The "median" column is the harness's lower-middle element for even n. This is not a bug, but it should be labeled.
- **Fix:** rerun at 5 reps and record the per-terrain spread.

**F-M6 (cross-cutting): the endpoint test suite is red.**
- The one failure is `SecondoTest.plannedApisAreAbsentOnThisBranchAndInvokeIsANoop`, with `AssertionError: baritone.api.physics.IPredictivePhysics`.
- It asserts PPE is absent. The owner-authorized PPE merge `ab615b7f3` made it present.
- This is not caused by a 2.0 phase, and Grok's `DECISIONS.md` header acknowledges the stale premise. The test is left as is: fixing it means editing Secondo feature bookkeeping, which is outside this review's gate fixes. Phase-relevant tests are run by class name.

### Low

- **F-L1 (git hygiene):** Phase 00 and 01 share a commit, and Phase 03 was left uncommitted. Mitigated by the snapshot commit.
- **F-L2 (version):** no decision record names the benchmark version.
  - Facts: the build is 26.3. `PlayerSim` is a 1.16 model (D5). PPE targets 26.3. The repo handoff calls 26.3 "the main test version".
  - **Fix:** record **MC 26.3** as the benchmark version in DECISIONS.md, with the caveat that PlayerSim numbers come from a 1.16 model.
- **F-L3 (attribution):** Grok's documents label PPE and maze facts as REPORTED where they are not his work. No misattribution found.
- **F-L4 (scope):** the Phase 03 `PlayerSimPhysics.face(yaw)` addition only records yaw (VERIFIED). No production default changed: `inputArbitration=false` (Settings line 956) and `latticeExperiment=false` (line 963). No legacy code deleted.

## 4. Gate audits

### Phase 00

- **Claims spot-checked: 14** (more than the required 10). Each was checked against the code at `67e12b96e` and at the endpoint.
  - The 14 claims: GotoCommand 46–51; `PathingBehavior.onTick` IN/OUT 167–181; PathingControlManager priority sort 185–217; PathExecutor built only at line 701; `TravelStack.order` 235–238; safe-to-cancel KINEMATIC/SIM-on-ground 298–304; `PathExecutor.cancel` 1019–1024; `Movement.update` key writes 139–167; PlayerSim 1.16 class comment; ClientWorld refuses levitation 38–40; PathBench `calculate0` + `Favoring(null)`; NativeLib ABI refusal 141–146; Secondo priority −0.5; search cancel does not dispatch CALC_FAILED.
  - **13 hold at both commits.** The PPE-absent claim (BASELINE.md line 92, `SecondoTest` passes) holds at the baseline and is **false at the endpoint** (F-M6). Grok's DECISIONS header flags it.
- **Baseline rerun**, with Grok's command on the baseline worktree:
  ```
  OSTINATO_BENCH=phase00-short OSTINATO_BENCH_REPS=1 OSTINATO_BENCH_ONLY=SHORT \
  gradlew --offline --no-daemon --no-build-cache --max-workers=2 cleanTest test -x nativeHost -Postinato_bench \
    --tests baritone.pathing.calc.PathBenchTest --tests ...PlayerSimTest ... --tests ...SecondoTest
  ```
  - Result: 66 passed, 0 failed, 2 skipped, the same as Grok's run.
  - The node/cost/path **fingerprint is identical** to `raw/phase00-short-fingerprint.txt`. **VERIFIED, Tier-S.**
  - Timing, mine vs Grok's (REPORTED), median ms:

    | Terrain | Mine | Grok |
    |---|---|---|
    | FLAT | 0.34 | 0.56 |
    | HILLS | 4.32 | 4.59 |
    | CAVES | 1.48 | 1.42 |
    | WATER | 2.82 | 2.84 |
    | BREAK_PLACE | 77.4 | 81.5 |
    | All (median / total) | 1.72 / 315 ms | 1.69 / 334 ms |

  - The timings are close, but "within reported variance" cannot be judged with n = 1 (F-M5).
- **Captured before behavior change:** yes. The baseline commit predates Phase 01, and the Phase 00 docs change no code (VERIFIED by diff).
- **One MC version documented:** implicit only (F-L2).
- **What the harness simulates:**
  - It runs the stock A* planner (`calculate0`) over `SyntheticWorld` terrains (FLAT, HILLS, CAVES, WATER, BREAK_PLACE) with `HeadlessToolSet`.
  - It reports nodes, cost, time, allocation and a travel-first score, where travel ticks are the planner's cost estimate.
- **What it leaves out:**
  - `PathExecutor`, movement execution, `InputOverrideHandler`, look/rotation, and `RouteReuse`/`createPathfinder` (weight overrides, `Favoring`).
  - Chunk loading, other entities and mobs, real block shapes.
  - Any 26.3 physics: PlayerSim-based tests use a 1.16 model.
  - It is **not an execution-tier baseline**, and no execution baseline exists.

### Phase 01

- **Implemented** (VERIFIED by reading `InputArbiter.java` in full):
  - Per-tick frames (`submit`/`seal`/`applied`).
  - Resource leases (MOVE/JUMP/SNEAK/CLICK).
  - Epochs: `claim` always bumps; a stale `submit` returns `STALE` (lines 113–128, 173–175).
  - Preemption: emergency first, then priority, then smaller id.
  - Cancel/ack: `requestCancel` keeps keys until `confirmRelease`; `fail` clears.
- **Tests:** 8 in `InputArbiterTest`, all passing. **VERIFIED, Tier-U.**

  | Required test | Status |
  |---|---|
  | Stale lease | present |
  | Preemption | present |
  | Timeout without ack not treated as stopped | **missing** (F-M2) |
  | Release on cancel | present |
  | Release on exception | present (`FallController` catch path; arbiter `fail`) |
  | Release on disconnect | **missing** (F-M1) |
  | Never two owners in one tick | partial (F-M2) |

- **Bypass writes:** see F-M3.
- **Navigation tests pass and defaults are unchanged:** the Phase 00 suite passes at the endpoint, the fingerprint is identical and the flag is off. **VERIFIED.** With the flag off, `FallController.tick` takes the pre-Phase-01 code path unchanged (lines 47–55).

### Phase 02

- **Minimal and used:** the contract types (`PhysicsTransition`, `ControlTick`, `PlayerState`, `Trajectory`, `Scenario`, `Measurement`, `Equivalence`, `InputSequence`, `WorldAccess`) are small records or interfaces. `PlayerSimPhysics` implements `PhysicsTransition`. `ComparisonHarness` and Phase 03's `LatticeSearch` both consume it. **VERIFIED.**
- **Equivalent conditions:** each row carries an explicit `Equivalence` label.
  - `physics-search` and `trivial-sprint` share world, start, goal, radius and budgets.
  - `ppe-held-sprint` and `stock-astar` are honestly labeled NOT_EQUIVALENT (D8).
- **Ground truth for "observed":** the same predictor. Self-consistency only (F-H2).

### Phase 03

- **Flag off by default and isolated:**
  - `latticeExperiment=false`.
  - Nothing in `PathExecutor`, `AStarPathFinder`, `PathingBehavior` or `KinematicController` references `baritone.lattice`. Checked with `grep -rn "baritone.lattice" src/main/java` outside the package. **VERIFIED.**
- **Beam and weighted A* compared:** yes, against `PhysicsPathfinder` only. Not against current navigation (F-H1).
- **Raw per-trial data, seeds and repeats:**
  - `raw/phase03-trials.csv` holds 252 rows, which is 28 cases × 3 methods × 3 trials.
  - There are no seeds, because the worlds are deterministic and hand-built.
  - **My rerun reproduced every discrete column** (success, ticks, searchTicks, expansions, divergence). Only the timing and allocation columns differ. Command:
    ```
    gradlew ... test -x nativeHost --tests baritone.lattice.LatticeExperimentTest ...
    ```
    Output was compared with `diff` after stripping `medianLatencyMs`/`medianAlloc`: `IDENTICAL_EXCEPT_TIMING`. **VERIFIED, Tier-S.**
- **Planning time and memory:** medians and thread-allocated bytes are present. p95/p99 and tick overhead are missing (F-H3).
- **The decision follows from the numbers:** yes, as a mechanical application of the D9 rule. But the rule could not reach CONTINUE (F-H3).
- **Prediction failures separated from planner failures:** only nominally (F-H2).
- **Verdict provisional:** no. D9 states REVISE without "provisional".
- **Latency** (VERIFIED, Tier-S, single workstation, medians):
  - Lattice search is roughly 10–100× faster than `PhysicsPathfinder` on these tiny worlds. For example, gap-2: beam 0.44 ms vs physics 41.3 ms; held-out gap-2-far: physics fails after 53.6 ms.
  - Travel ticks: beam needs equal or more ticks on turns, for example turn-180 22 vs 14.

## 5. Cross-cutting

- **Test integrity:** no test was weakened in Phases 01–03: `git diff --diff-filter=DM 878606eb2^ 09631d874 -- src/test` is empty, so the phases only add tests (VERIFIED). The one red test predates the phases (F-M6). The flag-default source-scan test in `InputArbiterTest` is weak, because it checks strings, but it is correct.
- **Evidence labels and tier claims:**
  - Grok labels claims consistently, and STATUS self-reports Phase 00 as PARTIAL.
  - No Tier-L claim is made for Phases 01–03. None is earned either: no live run was made in Phases 00–03.
- **Statistics:** see F-H3. No confidence intervals appear anywhere in Phases 00–03.
- **Version consistency:** see F-L2.
- **Defaults and legacy:** unchanged and nothing deleted (F-L4).
- **Scope:** Grok stayed inside Phases 00–03. No Phase 04 work was found.
- **Git hygiene:** see F-L1. No pushes from this review (§1).

## 6. Fix plan (Step 5)

Each fix is a separate `review-fix(phase-0X): ...` commit:

1. Phase 00: a 5-rep rerun with per-terrain spread, and the benchmark version recorded in DECISIONS.
2. Phase 01:
   - an arbiter `release(id)` and `releaseAll()`, called on executor cancel and on disconnect;
   - a timeout-without-ack test;
   - an exclusivity invariant test;
   - a disconnect-release test.
3. Phase 02/03:
   - stock A* planner-tier rows on the lattice worlds;
   - p50/p95/p99 and Wilson CIs;
   - the lattice test writes to `build/` by default;
   - D9 marked PROVISIONAL.
4. Prediction check per mode (F-H2), at Tier-L where the historical traces exist and at Tier-S elsewhere. Missing Tier-L is listed as a blocker.

## 7. TenorClef

TenorClef consumes Ostinato as a committed jar (`libs/baritone-unoptimized-fabric-ostinato-26.3.jar`), with a `-PostinatoJar` override. Compile result against the endpoint jar: *pending at the time of this commit; recorded in §8.*

## 8. Post-fix status (added after the fixes)

### TenorClef (§7 result)

`gradlew --offline --no-daemon --max-workers=2 :26.3:compileJava -PostinatoJar=<Ostinato-o2review>/fabric/build/libs/baritone-fabric-1.26.0-7-g09631d874.jar`, run on TenorClef `983cf317`: `BUILD SUCCESSFUL in 12m 18s`, exit 0. **VERIFIED, Tier-U (compile only).**

The Phase 04 commits add one `Settings` field and one class. No API is removed or changed, so the compile was not repeated against the Phase 04 jar. That is an inference, not a run.

### Correction to §4

The Phase 02 audit said `ComparisonHarness` and `LatticeSearch` "both consume" `PhysicsTransition`. They consume the concrete `PlayerSimPhysics`; no code is typed against the interface (`grep -rn PhysicsTransition src` finds only the interface and its one implementation). The types are still minimal and used, so the verdict holds. The interface itself was not promoted in Phase 04 (DECISIONS D11).

### Fix outcomes

| Finding | Outcome | Commit | Evidence |
|---|---|---|---|
| F-H1: no comparison with current navigation | **Fixed at planner tier.** Stock A* rows on all 28 lattice worlds, default and parkour, 20 trials. Execution tier is impossible headlessly (D2), so it is a blocker (STATUS B4). | `83f8a134d` | `raw/review/phase03-stock*` |
| F-H2: "observed" is the predictor | **Fixed as far as headless allows.** A per-mode check now exists: Tier-L from the historical 26.3 SIMTRACE traces for sprint, sprint-jump and climb-jump, and Tier-S PlayerSim-vs-PPE cross-model for walk, sprint, sprint-jump, fall, water and ladder. Missing Tier-L is recorded as a blocker (STATUS B1). | `1edef6005` | `raw/review/phase04-*` |
| F-H3: weak statistics | **Fixed.** Expansion counter, p50/p95/p99, Wilson, Fisher, a seeded paired bootstrap and 20 trials. D9 is marked PROVISIONAL in DECISIONS. | `b1df1fc41` | `raw/review/phase03-summary.txt` |
| F-M1: lease leak | **Fixed.** `releaseAll` runs on world change, and `releaseInputLeases` runs on executor cancel and segment discard. | `665b8dc7e` | `InputArbiterTest`, 12/12 |
| F-M2: missing tests | **Fixed.** Timeout-without-ack (1200 ticks), never two owners (200 seeds × 400 steps), and release tests. | `665b8dc7e` | same |
| F-M3: bypass writes | Recorded, not fixed (later-phase migration). | — | STATUS B6 |
| F-M4: test overwrote docs | **Fixed.** Writes go to `build/` unless `OSTINATO_WRITE_DOCS=1`. | `b1df1fc41` | full suite leaves `docs/` clean |
| F-M5: no variance | **Fixed.** 5 reps at the endpoint, with per-terrain spread; the fingerprint is identical. | `1443357d3` | `raw/review/phase00-review*` |
| F-M6: SecondoTest red | Not fixed. It is out of scope and an owner decision. | — | STATUS B5 |
| F-L1: git hygiene | Mitigated by the snapshot commit. | `09631d874` | — |
| F-L2: implicit version | **Fixed.** MC 26.3 is recorded. | DECISIONS D10 | — |
| F-L3, F-L4 | No action needed. | — | — |

### Verdicts after fixes

| Phase | Before | After | Remaining limit |
|---|---|---|---|
| 00 baseline | PASS WITH FIXES | **PASS** | Planner tier only. No execution baseline exists (D2). |
| 01 input ownership | PASS WITH FIXES | **PASS** (flag off) | Flag-on behavior in a world is UNKNOWN (L), and there are 31 bypass writers. |
| 02 contracts | PASS WITH FIXES | **PASS WITH FIXES** | The `PhysicsTransition` interface has no consumer besides its one implementation. "Observed" in `ComparisonHarness` is still self-consistency; the per-mode check lives in Phase 04. |
| 03 lattice | PASS WITH FIXES | **PASS, decision PROVISIONAL** | CONTINUE at planner tier, but the evidence is weak (Fisher p = 0.48, ratio CIs include 1.0), and stock A* solves everything too. Execution tier is a blocker. |

The suite and benchmark reruns after the fixes are recorded in BENCHMARKS ("Claude review reruns").

### physics-263 (owner request after Phase 04)

- The owner asked that every physics predictor follow 26.3, not 1.16.1. This reverses the review's earlier stance that constants wait for Phase 09 (D5, D10).
- What changed, what was left alone and why, and the evidence: DECISIONS D12.
- Two findings came out of the audit beyond PlayerSim itself:
  - PPE's sprint speed was one float ulp low.
  - The spectate decoder's client diagonal matched neither version.
- Consequence for the review: the Phase 04 Tier-L bounds now describe the old model. STATUS B1 records that.
