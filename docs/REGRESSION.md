# Regression suite

Ostinato: per-commit pass/fail report for parkour, pathfinding and (later) combat, compared against a stored baseline.
Foundation item #1. Headless stages first; live-client stages plug in when headless clients are trusted.

## Run it

```bash
# one commit, the checkout you are in (unit tests + headless stages + report)
./gradlew regress -Pregress_worlds=/workspace/pk/w

# per commit: each commit of a range, in one reused worktree, at nice 10 with 2 Gradle workers
scripts/regress.sh --worlds /workspace/pk/w origin/main~5..origin/main
scripts/regress.sh HEAD~1 HEAD --stages pathbench,simdrill

# accept the current numbers as the new baseline (after an intended change), then commit regress/baseline/
./gradlew regressBaseline -Pregress_worlds=/workspace/pk/w
```

On the box use JDK 25 (`JAVA_HOME=/workspace/tools/jdk-25.0.4.1+1`) and `nice -n 10 ./gradlew --no-daemon --max-workers=2`;
on the PC the usual rule holds (one Gradle process, BelowNormal, `--no-daemon --max-workers=2`). A full run takes about
6 minutes on the box (unit tests ~2.5 min, path benchmark ~1.5 min, sim drills ~1.5 min).

Output: `build/regress/report.md` (and `report.json`), the per-stage files in `build/regress/metrics/`. The task fails
when the verdict is FAIL unless `-Pregress_nofail` is given. `scripts/regress.sh` writes `build/regress-runs/<sha>/` and
a table in `build/regress-runs/summary.md`, and overlays the suite onto commits older than it, so a range can be bisected.

| property | default | |
|---|---|---|
| `regress_stages` | `unit,pathbench,simdrill,combat` | stages to run |
| `regress_worlds` | `$OSTINATO_REGRESS_WORLDS` | folder holding the map saves (`pk_practice_parkour`, `pk_ore_parkour_of_patience`); without it the map courses are skipped, not failed |
| `regress_courses`, `regress_modes` | all, per manifest | limit the sim drills |
| `regress_engines` | `BARITONE:astar,KINEMATIC:game` | path benchmark `engine:finder` pairs (`game` = far-segment weighted A* as PathingBehavior uses it; also `astar`, `wa:<w>`, `hpa...`) |
| `regress_planMs` | 4000 | sim drill search budget per goal |
| `regress_benchReps` | 1 | timed benchmark reps after the warm-up |
| `regress_baseline` | `regress/baseline` | baseline folder |
| `regress_extra` | | extra metrics folders (live stages), `:`-separated |

## Stages

| stage | what | gates on |
|---|---|---|
| `unit:junit` | every JUnit test of the normal `test` task (read from its XML; a red test no longer stops the other stages) | any failing test |
| `pathbench:<engine>:<finder>` | PathBench's seeded synthetic terrains (flat, hills, caves, water...), the short and medium queries the golden-cost test uses | failures (searches that ended without the goal before the timeout), path cost over the queries solved in both runs (+0.5%); nodes (+10%), plan ms warn |
| `simdrill:<course>:<mode>` | the PkTest course scripts (`src/test/resources/regress/courses/`, copied from pk/goals) built headlessly from their `fill`/`setblock` commands or read from the map save's region files, every goal planned by the real A* with PkTest's settings for that mode. The jump movers' costs run PlayerSim against the course's real collision boxes | goals planned to the goal; a goal that was planned and now is not; path cost over the goals planned in both (+0.5%) |
| `combat:vexbench` | slot for the combat bench; records itself skipped until VexBench is on main or a live runner writes it | |
| `live:*` | live-client stages, see below | per their metrics |

The sim drills check the plans the bot would get, not their execution: a goal planned here but failed live points at
the executor (kinematic/physics controller), a goal not planned here is a planner gap. Plans are classed by step
geometry (`gap2`, `up`, `drop3`, `climb`), since Movement objects need a live player. Courses: `pk_drill` (68 jump
types), `pk_drill_hh` (17 head hitters), `pk_oreroute`, `pk_ore` (ore parkour of patience level goals),
`pk_practice` (practice parkour, 95 goals). The manifest is `src/test/resources/regress/courses.txt`.

## Verdict rules

Per stage: an error fails. A metric past its tolerance (`abs + rel x baseline`) in its bad direction fails or warns by
its severity. A case that was `ok` and is not now fails; one that turns into `timeout` only warns (machine speed). A
case now `fail`/`error` that was not fails. A case that becomes `ok` is listed as IMPROVED. Cost or node changes on
cases that stayed `ok` are listed as INFO; their summed cost gates (+0.5% fails, -0.5% is an improvement) and their
summed node count warns past +10%. Means over each run's own solved set are shown but do not gate: a search that ends
near its timeout can drop in or out of the set. Some jump movers keep wall-clock budgets and retry windows
(MovementTas, MovementChainJump), so node counts can move by a few between identical runs; in three runs on the box
costs and outcomes (other than searches cut off by their timeout) did not. A stage in the baseline that was skipped or not run warns. Timing metrics
never fail: the box is shared.

## Metrics file (how any stage plugs in)

```json
{ "stage": "live:pktest:pk_drill:kinematic", "kind": "live", "status": "ok|skipped|error", "note": "", "commit": "sha",
  "metrics": [ { "name": "goals_reached", "value": 15, "better": "higher|lower|info", "severity": "fail|warn",
                 "abs": 0, "rel": 0, "unit": "" } ],
  "cases": { "goal003": { "outcome": "ok", "cost": 23.0, "nodes": 10, "ms": 1.2, "detail": "" } } }
```

## Live stages

`scripts/regress.sh --live` runs `regress/live.d/*.sh` first (see the README there) and merges what they write.
`pktest.sh.example` runs one PkTest course in a headless client and converts `pk-results` with
`scripts/regress/pk_results_to_metrics.py`. The in-client harnesses are gated by the `testHarness` setting (or
`-Dostinato.testHarness=true` for one launch) plus their own property (`-Dostinato.pktest=<save>`); the dev client's
heap is `-PclientXmx=4G` on `runClient`. Live numbers should wait for the headless/real parity check (P0.1).

## Baseline

`regress/baseline/` holds one metrics file per stage, written by `regressBaseline` from a run of the commit named in
each file. Only `ok` stages are stored. Re-baseline after an intended change and say why in the commit.
