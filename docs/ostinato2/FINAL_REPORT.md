# Ostinato 2.0 — final report (Phase 10)

Branch `ostinato2/claude-review`, local clone `Ostinato-o2review`, 2026-11-09. Phase start `bda049360`; end: the
commit that last touched this file (`git log -1 -- docs/ostinato2/FINAL_REPORT.md`). Nothing pushed, merged, or opened as a PR. Decision record: DECISIONS
D21. Evidence: BENCHMARKS "Phase 10" and `docs/ostinato2/raw/phase10/`.

Evidence labels: **VERIFIED** (measured or read in code in this branch), **REPORTED** (stated by an earlier phase
or by Grok, not re-measured here), **UNKNOWN**, **FAILED** (measured and missed its criterion).

## 1. Executive verdict

- **Phase 10: PARTIAL.** The production build is validated on 26.3 Fabric, the suite is green (1645 / 0 failed / 24
  skipped), one Critical production-reachable input-ownership defect (B6, magma SNEAK) is fixed and held out
  (12/12), B5 is closed as a stale test, and every experiment has a disposition. The one promotion assessment,
  `verifiedGetToBlock`, came out **DEFER** under its pre-registered rule. A second suspected key leak (the AirProcess surfacing JUMP) did not reproduce live (CLEAR 6/6) and was left unchanged.
- **Release readiness: CONDITIONAL.** The production defaults (every 2.0 experiment off) are releasable on 26.3
  Fabric as validated, on the conditions in section 14.
- No production default was changed and no experiment was enabled.

## 2. Verified architecture (production defaults)

- Minecraft 26.3, Java 25, Fabric only (`available_loaders=fabric`), `mod_version=1.24.0` (VERIFIED).
- Planning: legacy Baritone A* (`simEdgeSearch`, `pathingHierarchical`, `pathingStateIdCache`, `latticeExperiment`
  off) (VERIFIED, `Settings.java`).
- Travel: `travelStack=auto` (kinematic, then physics, then the sim probe, then Baritone's movement as a backup);
  `kinematicTravel` and `simTravel` on; `physicsTravel` off (VERIFIED).
- Movement model: `PlayerSim` and its Rust mirror on 26.3 rules (physics-263, D12), bitwise native parity
  (REPORTED by D12, unit tests green here: VERIFIED).
- Input: direct writes to `InputOverrideHandler` by processes, behaviours and controllers (B6); the arbiter
  (`inputArbitration`) and the unified executor are off. A forced MOVE/SNEAK/JUMP key installs `PlayerMovementInput`
  and the keyboard is ignored until it is released (VERIFIED in code and live).
- Control: `PathingControlManager` asks every process `isActive()` each tick, the winner's `onTick` returns a
  command, then the path executor runs (VERIFIED).
- Interaction: `GetToBlockProcess` records a numbered `ProcessOutcome` per request; container observation is behind
  `ContainerView` / `ClientContainers` (26.3) and the Minecraft-free `OpenVerifier` (D20). With
  `verifiedGetToBlock` off, the legacy "done on arrival" rule decides (VERIFIED).
- Swimming: `swimInWater` on, so `AirProcess` runs for every player, idle included (VERIFIED).

## 3. Phase 00–09 summary

| phase | subject | result | flag (default) | record |
|---|---|---|---|---|
| 00–03 | review of Grok's phases; arbiter lease release; statistics | PASS WITH FIXES; D9 PROVISIONAL | `inputArbitration`, `latticeExperiment` (false) | `REVIEW_00-03.md`, D9 |
| 04 | canonical `PredictionTolerance`; Tier L bounds | PROVISIONAL (overhead inconclusive, no improvement) | `predictionTolerance` (false) | D11, D14 |
| physics-263 | `PlayerSim` and native mirror on 26.3 rules | done; bitwise parity | none | D12 |
| 05 / 05B | simulation-derived A* (SimEdge) | NO IMPROVEMENT (latency) | `simEdgeSearch` (false) | D13, D15 |
| 06 / 06A | unified executor lease; cancel ordering; bounded retry | 06 FAIL, 06A PASS, not promoted | `unifiedExecutor` (false) | D16, D17 |
| 07 | long-distance routing; state-id cache | FAIL (G3 pairing; ~4%, not 30%) | `pathingStateIdCache` (false) | D18 |
| 08 | navigate → interact → verify | PASS (arena) | `verifiedGetToBlock` (false) | D19 |
| 09 | container observation behind a version adapter | PASS (parity) | none | D20 |
| 10 | final validation, disposition, B6 audit | PARTIAL | none changed | D21 |

## 4. Disposition matrix

| system | flag (default) | disposition | evidence |
|---|---|---|---|
| interaction contract (`ProcessOutcome`, `OpenVerifier`, `ContainerView`, `ClientContainers`) | none | **RETAIN** | VERIFIED: D19/D20 PASS; Run V no leak, no exception |
| `AirProcess` magma release (B6) | none | **RETAIN** | VERIFIED: STUCK 2/2 → CLEAR 12/12 held out |
| physics-263 | none | **RETAIN** | VERIFIED unit; REPORTED Tier L (D12/D14) |
| `PredictionTolerance` contract | none | **RETAIN** | REPORTED D11/D14 |
| harnesses (opt-in properties) | none | **RETAIN** | VERIFIED: inert unless a property is set |
| `verifiedGetToBlock` | false | **DEFER** | FAILED: Run V T2/T3/T4 on 90012; T1 0 FS on both seeds |
| `unifiedExecutor` | false | **DEFER** | REPORTED D16 FAIL / D17 PASS; no promotion evidence; B6 Deferred rows unmigrated |
| `inputArbitration` | false | **DEFER** | VERIFIED: direct writers bypass it (B6) |
| `pathingStateIdCache` | false | **DEFER** | FAILED D18 |
| `pathingHierarchical`, `pathDecisionMatrix` | false | **DEFER** | UNKNOWN (pre-2.0, not assessed) |
| `predictionTolerance` | false | **RETIRE** (recommend; keep code) | FAILED to improve (D14) |
| `simEdgeSearch` | false | **RETIRE** (recommend archiving; keep code) | FAILED to improve (D13/D15) |
| `latticeExperiment` | false | **RETIRE** | VERIFIED: no consumer in `src/main/java` |

PROMOTION CANDIDATE: none. RETIRE means "recommend to the owner"; no code was removed.

## 5. Baseline measurements (production defaults, Phase 10)

- Unit suite at `bda049360`: 1642 tests, 1 failed (B5), 24 skipped, 4 min 41 s (VERIFIED).
- Run P, production lap course (`SimEdgeCourse`, seed 90021, 96 laps, all arms production): 96/96 laps reached, lap median 296–298 ticks in every arm (slalom 340, ridges 271, bumps 298, steps 293), tick p50 about 5.5 ms and p95 about 7.6 ms (median of laps), 0 faults, 0 cancels, 0 stuck-key laps, 0 deaths; P1 (harness sanity) PASS, all 16 A/A paired-leg median ratios in 0.996–1.004 (VERIFIED).
- Run V arm A (production interaction, natural terrain, 2 seeds, 84 verdict rows): 47 TS, 24 false successes, 13
  hangs, 0 deaths; runner 7.85 µs per tick (VERIFIED).

## 6. Verified reliability improvements

- **B6 magma SNEAK** (production, always on): an idle player who stood on magma kept SNEAK forced and lost keyboard
  movement. Fixed; 12/12 CLEAR held out (VERIFIED).
- B5: the full suite is green for the first time on this branch (the red test was stale, not a regression) (VERIFIED).
- With `verifiedGetToBlock` on (not default): 0 false successes in 180 natural-terrain laps against 24 with it off;
  VANISH_ONE fails honestly (WORLD_CHANGED) instead of exploring (VERIFIED, Run V). Not promoted (section 10).

## 7. Unresolved safety blockers

- No Critical blocker is open on the production defaults (VERIFIED for the paths staged: magma, surfacing).
- `AirProcess` surfacing JUMP with no path current: Deferred. Read in code; the staged case (open pool, path current) released cleanly, CLEAR 6/6.
- `AirProcess` door air pocket: the same release pattern (Important, open, rare).
- B6 overall: direct key writers bypass the arbiter. Five `onLostControl()` `clearAllKeys()` calls, `BackfillProcess`,
  `ElytraBehavior` and `ServerSpectate` are Deferred (they over-release, or are in off-by-default features). They
  block `inputArbitration` and `unifiedExecutor`, not the production defaults.
- A navigation stall (standing still, no key, RUNNING) is not bounded by any timeout in either arm (Run V, 90012).

## 8. Version matrix

| target | status |
|---|---|
| Minecraft 26.3, Fabric | **VERIFIED live** (Run V, Run P, all Phase 04–10 live runs) |
| 26.3 Forge / NeoForge | UNKNOWN: directories present, not in `available_loaders`, not built |
| 1.16.5 interaction contract | compile only (D20); `ProcessOutcome` is a record and blocks Java 8 |
| other versions | not ported |

## 9. Recommended production configuration

The defaults as committed: every 2.0 experiment off (`verifiedGetToBlock`, `unifiedExecutor`, `inputArbitration`,
`pathingStateIdCache`, `simEdgeSearch`, `predictionTolerance`, `latticeExperiment`, `pathingHierarchical`,
`pathDecisionMatrix`); `travelStack=auto`, `kinematicTravel` and `simTravel` on, `swimInWater` on,
`exploreForBlocks` on, `rightClickContainerOnArrival` on, `sprintJump` off (travel bench faster off, `46dce71f7`).

## 10. Promotion candidates

None this phase. `verifiedGetToBlock` is the nearest: it removes every false success observed (Phase 08 arena 0 vs 40;
Phase 10 terrain 0 vs 24) and fails honestly where the legacy rule explores or hangs. Before it can be re-assessed:
a bound on navigation stalls (or an explicit "stalled" outcome), a retry on another known instance after a refusal,
LAG_LONG's late open reported as UNKNOWN rather than FAILED/TIMEOUT, and a fresh pre-registered terrain run.
Promotion also accepts one behaviour change: `goto <block>` with no known instance fails instead of exploring.

## 11. Retired and deferred

- RETIRE (recommend; code kept): `predictionTolerance` flag, `simEdgeSearch`, `latticeExperiment`.
- DEFER: `verifiedGetToBlock`, `unifiedExecutor`, `inputArbitration`, `pathingStateIdCache`,
  `pathingHierarchical`, `pathDecisionMatrix`.
- Failed experiments are kept as written, with their raw results.

## 12. Final benchmarks

- Suite after Phase 10: 1645 tests, **0 failed**, 24 skipped (PathBench golden ran and passed) (VERIFIED).
- Run V (`verifiedGetToBlock`, Tier L, 180 laps): T1 PASS (B 0 FS, A 24), M1 PASS (12/12); T2/T3/T4 PASS on 90011,
  FAIL on 90012 → DEFER (FAILED).
- Run P: 96/96 laps reached, lap median 296–298 ticks in every arm (slalom 340, ridges 271, bumps 298, steps 293), tick p50 about 5.5 ms and p95 about 7.6 ms (median of laps), 0 faults, 0 cancels, 0 stuck-key laps, 0 deaths; P1 (harness sanity) PASS, all 16 A/A paired-leg median ratios in 0.996–1.004 (VERIFIED).
- SURFACE diagnostic (seed 90031, 6 laps, not pre-registered): CLEAR 6/6, no change made (VERIFIED).

## 13. Technical debt

- B6: 280 raw `grep` matches of `setInputForceState`/`clearAllKeys` in 34 files of `src/main/java` (harnesses included; 279 in 33 at Phase 06A, +1 file for the new harness); no single owner for keys outside the path executor.
- Releases that depend on a path being current (`secretInternalSegmentCancel` clears keys only when `current != null`).
- `ProcessOutcome` exists for `GetToBlockProcess` only; the runner's BUILD and count-less MINE steps still read
  inactive as done.
- `MineProcess.prune` (`avoidBreaking`) hides containers beside liquid from `goto <container>` (upstream).
- Forge/NeoForge unbuilt; the 1.16 port blocked by a Java 16 record.
- Research code kept behind off flags (SimEdge, lattice, hierarchy, decision matrix).

## 14. Release readiness

**CONDITIONAL.** Releasable on 26.3 Fabric with the production defaults, on these conditions:
1. Ship with every 2.0 experiment off (section 9).
2. Run one owner-side smoke session on a real 26.3 client with the release jar (the live runs here used `runClient` with the test harness loaded, off unless its property is set).
3. Release notes name the known limits: navigation stalls are unbounded; Forge/NeoForge unvalidated.
4. The owner pushes, merges and tags; nothing here was pushed.

## 15. Next priorities

1. Push and merge this branch only after reviewing D21 (owner); the B6 magma fix is the one production behaviour change.
2. A navigation-stall bound (or an explicit stalled outcome) for get-to-block, then re-assess `verifiedGetToBlock`
   with a fresh pre-registered terrain run.
3. B6 migration of the Deferred writers if `unifiedExecutor` or `inputArbitration` is to be promoted.
4. Archive the RETIRE items (owner's call).
5. `ProcessOutcome` as a plain class if a 1.16 port is wanted.
