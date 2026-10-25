# Tick-accurate movement costs (Ostinato, `tickAccurateCosts`, default off)

`TickSim` (src/main/java/baritone/pathing/movement/TickSim.java) steps vanilla player physics one tick at a time:
ground drag 0.6 * 0.91, walk accel 0.098 b/t, sprint 0.1274, air accel 0.0196 / 0.0255, air drag 0.91,
gravity 0.08 with 0.98 vertical drag, jump 0.42. Steady state reproduces the stock tables (walk 4.633, sprint
3.564 ticks/block, walk-off 3.706), so flat walking is unchanged. What changes is where momentum matters:

| part | stock table | TickSim (walking) |
|---|---|---|
| ascend walk part | max(jump 3.16, walk 4.63) = 4.63 | 11.0 (blocked at the step face until the feet clear y=1, then air control) |
| descend walk-off | 3.706 | 3.706 |
| descend landing | max(fall 1, centre 0.93) | 5.61 (drop 1 block while air control carries the last 0.2) |
| standstill, 1 block | n/a | +1.17 (exposed as `TickSim.standstillPenalty`, not added in search) |

The sims run once per JVM (constants in `CalculationContext.TickCosts`); the per-node cost is a field read, so no
extra heap. Off: `CalculationContext` gets the stock expressions, bit-identical costs.

A/B: `OSTINATO_BENCH_TICK=1 scripts/pathbench-phase1.sh <label> normal|pi`.

Next (#timings): `CostCalibration.forEach` hands each movement family's sample count and mean actual/expected
ratio to a future command.

## Bench (5 reps, 60 queries; Oct 25 2026)

| run | nodes/s | peak heap | mean len | mean travel (path cost) | failures |
|---|---|---|---|---|---|
| main normal | 135683 | 666 MB | 237.1 | 1202.1 | 6.7% |
| off normal | 142951 | 656 MB | 237.1 | 1202.1 | 6.7% |
| on normal | 136360 | 640 MB | 236.8 | 1273.9 | 6.7% |
| main pi | 146910 | 513 MB | 237.1 | 1202.1 | 6.7% |
| off pi | 142492 | 501 MB | 237.1 | 1202.1 | 6.7% |
| on pi | 144103 | 511 MB | 236.8 | 1273.9 | 6.7% |

Off: per-query fingerprints identical to main on both profiles. On: 43 of 60 fingerprints change, path length
is about the same, and "travel" rises because it is the path cost under the new (higher) ascend/landing prices,
not measured travel; the bench cannot say whether on-mode paths are faster in game. Stays off until #timings data
(live actual vs expected ticks) confirms the sim.
