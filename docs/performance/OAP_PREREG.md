# OAP Stage 1 pre-registration (written before any sweep data)

Written 2026-11-09, worktree `Ostinato-oap`, branch `perf/oap` (local, uncommitted) at `706c1b473`.

## Question

The A* plan-ahead search runs until it reaches the goal or `planAheadPrimaryTimeoutMS` (4000 ms) passes with a usable
partial path. Does a shorter plan-ahead primary budget save planner CPU without a meaningful loss in trip time or
reliability? Does a longer one buy trip time?

Only the plan-ahead **primary** budget moves. The first-search budgets, every failure timeout and the escalation cap
(`failureTimeoutMaxMS`) stay at their defaults in every arm: they decide whether a hard route is found at all.

## Instrument

`LongRouteSimTest.corpus` (Phase 07 trip simulator, Tier S, headless), arm PROD (production `createPathfinder`), modes
`known,horizon,change,goal`. Test-only additions: per-search thread CPU time, and `OSTINATO_LONGROUTE_BUDGETS`, which runs
named budget arms on the same routes, interleaved, alternating order per route. Budgets are
`primary/failure/aheadPrimary/aheadFailure/failureMax` in ms.

| arm | budget |
|---|---|
| base | 500/2000/4000/5000/16000 (production defaults) |
| a2000 | 500/2000/2000/5000/16000 |
| a1000 | 500/2000/1000/5000/16000 |
| a500 | 500/2000/500/5000/16000 |
| a8000 | 500/2000/8000/8000/16000 |

Test heap capped with `-PbenchXmx=1536m` (machine commit headroom ~2.3 GB; the cap applies to every arm alike).

## Sets

- **Dev:** seed 2000, 2 trips per length (the Phase 07 dev seed). Used to look, choose and fix the candidate.
- **Held out:** seeds 4101, 4102, 2 trips per length. Never used by Phase 07. Run once, after the candidate is fixed.

## Metrics (per trip, paired on route key)

- `cpuMs`: planner thread CPU over all the trip's searches (the cost OAP may cut).
- `total`: travel + wait in planner ticks (what the user waits for).
- `ok`: trip reached the goal.

## Pre-registered gates for an ECO candidate (held out only)

- **G1 reliability:** trips not reached, candidate ≤ base. No trip that base reaches may fail in the candidate, unless
  it also fails in base.
- **G2 saving:** pooled `cpuMs` ratio candidate / base ≤ 0.75, bootstrap 95% CI upper bound (resampling trips,
  10,000 resamples, `random.Random(20261109)`) < 0.90.
- **G3 trip time:** pooled `total` ratio candidate / base ≤ 1.02, and CI upper < 1.04; no family's pooled `total` ratio
  above 1.05.

ECO is implemented only if one candidate passes G1–G3. Among passing candidates, the one with the lowest pooled
`cpuMs` ratio wins.

## Pre-registered gate for MAX (held out only)

MAX (`a8000`) is implemented only if pooled `total` ratio ≤ 0.98 with CI upper < 1.00, and G1 holds. Otherwise MAX
is not distinct from the baseline and is not shipped.

## Candidate fixed after dev (written before any held-out output was read)

Dev (seed 2000, 58 routes per arm, all reached in every arm), pooled ratios versus base with 95% CI:

| arm | cpu ratio | total ratio | worst family total | dev gates |
|---|---|---|---|---|
| a2000 | 0.793 [0.695, 0.907] | 1.0006 [0.9985, 1.0036] | MAZE 1.0082 (erratum) | G2 fails |
| a1000 | 0.666 [0.556, 0.800] | 1.0009 [0.9986, 1.0041] | MAZE 1.0088 | pass |
| a500 | 0.548 [0.440, 0.672] | 1.0045 [0.9995, 1.0119] | MAZE 1.0419 | pass, small margin |
| a8000 | 0.998 | 1.0004 | — | MAX gate fails |

Erratum (2026-11-10, after held out): the a2000 worst-family cell first read "MIXED ≈1.006", which is a1000's MIXED
figure; `raw/oap-dev-summary.txt` gives MAZE 1.0082. The choice did not use that cell (a2000 had already failed G2).

**Fixed candidate: `a1000`** (plan-ahead primary 1000 ms). a500 cuts more CPU, but its MAZE ratio (1.042) is within
0.01 of the 1.05 family limit and its trip-time CI reaches 1.012. One held-out set would decide on noise. a500 is still
run on held out and reported, but it cannot ship from this study whatever its held-out numbers. ECO ships only if
`a1000` passes G1–G3 on held out. a2000 and a8000 are run on held out for the record and for the MAX gate.

The held-out run was launched with all five arms just before this note was written. Its output was not read before
this note.

## What this cannot show

Tier S only: planner ticks, not live ticks; no executor, mobs or chunk loading. A pass here is a reason for a live check,
not a substitute for one. Wall-clock budgets make node counts machine-dependent: these numbers are this machine
(i5-1145G7, 4C/8T, JDK 25).

## Held-out result (added after the run, 2026-11-10)

Seeds 4101 and 4102, 116 routes per arm, run once (the first launch was a Gradle build-cache hit and ran nothing; the
second, with `--no-build-cache`, is the run). Raw: `docs/performance/raw/oap-ho-*`. Summary: `raw/oap-ho-summary.txt`.

| arm | G1 lost | cpu ratio [95% CI] | total ratio [95% CI] | worst family total | verdict |
|---|---|---|---|---|---|
| a2000 | 0 | 0.934 [0.845, 0.998] | 0.9991 [0.9969, 1.0009] | CAVES 1.0056 | G2 fails |
| **a1000** (fixed candidate) | 0 | **0.851 [0.733, 0.939]** | 1.0005 [0.9989, 1.0023] | CAVES 1.0101 | **G2 fails** |
| a500 (not eligible) | 0 | 0.767 [0.624, 0.871] | 1.0002 [0.9986, 1.0019] | CAVES 1.0101 | G2 fails |
| a8000 (MAX) | 0 | 1.108 [1.019, 1.210] | 0.9996 [0.9980, 1.0010] | — | MAX gate fails |

**Decision: ECO is not implemented. MAX is not implemented.** The candidate kept trips and trip time (G1, G3 pass) but
saved less CPU than the pre-registered bar. A production `planningEffort` setting had been drafted while the run went;
it was removed and nothing outside test code changed.
