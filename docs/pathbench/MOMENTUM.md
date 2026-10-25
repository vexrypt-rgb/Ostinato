# Momentum jump costing (Ostinato, `momentumJumpCosts`, default off)

Idea source: Soprano's public feature notes (neo and momentum jumps that use a sprint run-up). Ostinato already
has neos and run-up jumps (`allowNeos`, `allowMomentumJumps`: `MovementJump`, `MovementChainJump`, the 4 block gap
in `MovementParkour`). What it still underprices is the common case: a plain 1 or 2 block flat parkour gap
reached at a sprint. Stock Baritone prices those at walking speed (`WALK_ONE_BLOCK_COST * dist`) and does not
sprint them, so a sprint chain across gaps looks slower to A* than an equal walk around.

Ostinato code only; nothing is copied from Soprano.

With `momentumJumpCosts` on, a flat 2 or 3 block parkour (1 or 2 block gap) whose take-off has a walkable run-up
block straight behind it is priced as a sprint jump:

* `TickSim.sprintJumpTicks(dist)`: steady sprint to the edge, jump with the 0.2 sprint kick, air sprint control and
  drag until back at take-off height, then sprint to the landing centre.
* floored at `dist * SPRINT_ONE_BLOCK_COST` so an edge never undercuts the heuristic's per-block cost.

| gap | stock (walk) | sim | priced (on) |
|---|---|---|---|
| 1 block (dist 2) | 9.27 | 5.04 | 7.13 |
| 2 block (dist 3) | 13.90 | 7.42 | 10.69 |

The executor sprints the same jumps when the player is already sprinting (it already brakes any landing that
carries momentum past the centre). Ascends, tall tops, ladders, extensions and placing jumps keep their old
prices. The prices are two constants computed once per JVM: no per-node allocation, no heap change.

Off: `CalculationContext.momentumJumpCosts` is false, the expression falls through to `costFromJumpDistance`, and
the executor branch is not taken, so costs and execution are bit-identical to main.

A/B: `OSTINATO_BENCH_MOMENTUM=1 scripts/pathbench-phase1.sh <label> normal|pi`.

`CostCalibration` keys these moves by the parkour family, so `#timings` (#123) will show whether live
actual/expected ticks for parkour drift once this is on.
