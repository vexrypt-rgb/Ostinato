# Ostinato 2.0 — Phase 05 audit of the existing A*

Written before any change to search-state identity. Sources: `AStarPathFinder`, `AbstractNodeCostSearch`,
`PathNode`, `MaestroPathFinder`, `Path`, `Moves`, `MovementTraverse/Ascend/Diagonal`, `PathExecutor`,
`GoalXZ`/`GoalYLevel`, branch `ostinato2/claude-review` at `57c6099a7`.

## 1. Hashing and identity

| Search | State | Key | Store |
|---|---|---|---|
| `AStarPathFinder` (legacy) | a cell | `BetterBlockPos.longHash(x, y, z)`; `PathNode.equals` is x/y/z only | `AbstractNodeCostSearch.cells`: 2-level array map; pi-cheap mode uses a slice map |
| `MaestroPathFinder` | (cell, incoming heading in 16 sectors or NONE) | `longHash` → `PathNode[17]`, slot = heading | its own `Long2ObjectOpenHashMap<PathNode[]>`; the slot of a popped node is found by a linear scan |

- `PathNode.hashCode` truncates the long hash to int. No hash set of nodes uses it on the hot path (both
  searches index by the long hash), so the truncation is harmless.
- `PathNode.heapPosition` is per node, so several states of one cell may be open at once. Maestro relies on this.
- Consequence: a second state per cell needs no change to `PathNode`, `BinaryHeapOpenSet` or `Path`. Maestro already
  proves that pattern.

## 2. Equivalence

- Legacy: two arrivals at one cell are the same state. That is exact only while an edge's cost depends on nothing
  but its two cells. Every legacy move cost (`Moves.apply`) is a function of the world and the two cells. Velocity,
  facing and the position inside the cell never enter it. The model is consistent with itself, and blind to momentum.
- Maestro: arrivals with the same heading sector are equal. The turn charge depends only on the two sectors, so this
  equivalence is exact for Maestro's own cost model.
- **A simulated edge breaks both.** Its cost depends on the continuous body state at the start of the edge: position
  inside the cell, velocity, yaw, ground/air. Two arrivals in one cell are then different states. Any finite key is an
  approximation unless the edge cost is a function of the key alone.
- Decision for Phase 05 (hybrid-A* style, Dolgov et al. 2008):
  - The key is (cell, motion class). The class is rest, or moving with (velocity heading sector of 16) × (speed
    band: walk / sprint) × (ground / air). That is 65 slots per cell, created lazily.
  - Each state carries **one representative continuous body state**: the end state of the cheapest arrival so far.
    When a cheaper arrival replaces it, the representative is replaced too, and the state is re-expanded from the
    new body state.
  - Approximation: within one class, a cheaper arrival with slightly worse momentum replaces a dearer one with better
    momentum. The bounded error is a fraction of a sector and of a speed band. It is measured, not assumed away: see
    `SimEdgeTest` and the A/B.

## 3. Dominance

- Legacy: none beyond `cost` itself (one state per cell).
- Maestro: exact. A state dearer than a sibling heading by `maxTurn = 6 · maestroTurnCost` is skipped, because no
  turn costs more than that.
- Simulated edges: the largest cost a worse body state can add, compared with a better one at the same cell, is
  bounded by the time to undo it: stop and reach full speed in the other direction. That bound (`SimEdge.MOMENTUM_BOUND`)
  is measured in PlayerSim at class load: full sprint reversed against steady sprint over the same blocks.
  - Pruning with it is **not exact**, because two body states also differ in position inside the cell. It is a
    safe-side bound in practice and is checked by `pruningKeepsTheObjective` (pruned vs unpruned objective).

## 4. Pruning

| Rule | Where | Valid with simulated edges? |
|---|---|---|
| `minimumImprovementRepropagation` (0.01) | both | yes, unchanged |
| pi-cheap peek prune: skip an edge into a node already reached for ≤ current cost | A* only, `piCheapPathing` | **no**: a cheaper arrival can have worse momentum. Not used by the new search |
| dead set (positions an exhausted search proved unreachable) | both | yes: reachability does not depend on momentum, because the sim falls back to the legacy edge |
| `nodeFilter` corridor | hierarchical only | not used |
| lazy jump checks (`res.check`, pending offers) | A* only | not used; the new search verifies at once, like Maestro |
| Theta* (`legatoSearch`) | A* only | not used; Legato smoothing still post-processes the path |

## 5. Heuristic validity

- `GoalXZ.calculate`: (√2 · diagonal + straight) · `costHeuristic` (3.563 ticks/block). `GoalYLevel`: ascending
  costs `JUMP_ONE_BLOCK_COST` per block, descending `FALL_N_BLOCKS_COST[2] / 2` per block.
- Legacy costs: a sprint traverse is `SPRINT_ONE_BLOCK_COST` = 20/5.612 = 3.5638 ticks ≥ 3.563, a diagonal √2 of that.
  So on flat ground the legacy heuristic is consistent, by 0.0008 ticks per block.
- Baritone's heuristic is already not admissible everywhere. A fall or a parkour jump can cost less than its
  heuristic share, and `costHeuristic` is described in `Settings` as tuned, not proven.
- With simulated edges, and these are new findings:
  - Steady PlayerSim sprint covers 0.2806 blocks/tick, which is 3.564 ticks/block. That matches, so flat steady sprint
    stays consistent.
  - A single edge ends when the body **enters** the next cell, not at its centre. One edge can cover less than one
    block of travel, and then cost less than h(parent) − h(child). Along a path this telescopes. The error is bounded
    by about one block at the start and at the end, ≈ 3.6 ticks of total inadmissibility.
  - A sprint-jump ascend can cost less than 3.563 + `JUMP_ONE_BLOCK_COST`, so `GoalYLevel`'s ascend term can
    overestimate. For goals above the start, the search then behaves like weighted A*.
  - Correctness is unaffected: Baritone has no closed set, and a node popped with a stale cost is re-expanded when a
    cheaper arrival appears. The new search keeps that. Optimality is approximate, as it already is.
  - The new search counts consistency violations (`SimEdgeStats.inconsistent`) so the size of the effect is
    measured.

## 6. Reopening

- Legacy and Maestro reopen freely. A node whose cost improves is re-inserted whether or not it was expanded.
  `expandedCost` exists only for pending offers and Cadenza's terminal charge.
- With simulated edges, reopening is **required**, not optional. A state that improves gets a new representative
  body state, so its successors' costs change. Re-expanding recomputes them, and the sim runs again from the new body.
  This is the main CPU cost of the new search, and it is measured (`SimEdgeStats.sims`).

## 7. Reconstruction

- `Path` follows `previous` to the start and rebuilds every step with `runBackwards`. That takes the cheapest move,
  by legacy cost, whose destination is the next cell. It then sets `movement.override(min(legacyCost, nodeDelta))`.
  - So the executor runs the same `Movement` classes whatever search found the cells. A simulated edge only changes
    **which cells** are chosen, never how they are walked.
  - `override(min(...))`: a simulated edge dearer than its legacy cost (for example accelerating from rest) shows the
    legacy cost to the executor. The executor's cancel threshold (`cost + movementTimeoutTicks`) is therefore never
    tighter than legacy because of a simulated edge. A cheaper simulated edge does tighten it, by at most a few
    ticks against a 100-tick allowance.
  - Planning accuracy must therefore be read from the search's own end cost, not from the sum of movement costs.
    The live harness records both.
- Several `Moves` can share an offset. A diagonal that the search simulated is rebuilt as `MovementDiagonal`, which is
  the same movement family. No mismatch was found for the three simulated families.

## 8. Edge start state (the task's chaining requirement)

- Legacy: an edge has no start state.
- New: every simulated edge starts from its parent state's representative body. That body is the end state the
  parent's own incoming edge predicted. Position, velocity, yaw, sprint, ground and jump cooldown are carried.
- Legacy (non-simulated) edges, such as breaking, placing, falls, parkour, ladders and water, produce an **unknown**
  body. The next simulated edge from an unknown body starts at the cell centre at steady speed along its own direction.
  That is exactly the legacy steady-state assumption, so mixing families is never charged more than legacy would.
- The start state of a search is the real player's body when the search starts at the player's feet. A
  next-segment search starts unknown.

## 9. Execution policy the sim must copy (from the executor)

- Traverse and diagonal: forward plus sprint toward the destination centre, yaw capped at 32°/tick
  (`LookBehavior.calculateMouseMove`).
- Ascend (`MovementAscend.updateState`):
  - It jumps at once when the head is clear (`headBonkClear`) and lateral motion is ≤ 0.1. Otherwise it jumps within
    1.2 of the destination centre and 0.2 to the side.
  - It sprints only on "sprintable ascends" (`PathExecutor.sprintableAscend`, `sprintAscends`).
  - The sim copies the jump rule. Its sprint rule is simpler: it keeps sprinting when the previous edge was a
    traverse and `sprintAscends` is on.

## 10. Risks recorded before implementation

- R1. The edge cost becomes path-dependent, so the search has more states and runs sims. Plan time can grow. A cap
  falls back to legacy A* (Maestro's pattern).
- R2. The executor ignores the sim. If the sim's controller differs from the executor's, a route chosen for the sim
  can be slower live. Only the live A/B can show this.
- R3. Thread safety: sims run on the planner thread over `BsiWorld(context.bsi)`. `MovementJump` already does this
  in A*, so there is no new risk class.

## 11. Changes made during implementation (recorded after the fact)

These are deviations from the plan above, made while the unit tests and the first headless bench ran. All three are
part of the algorithm under test, not baseline fixes. Nothing in legacy A* or the executor was changed.

- **Corridor.** An edge first failed when the body left the {source, destination} columns. A sprint reversal
  overshoots into the cell behind the source, so every turn edge failed. The body may now also pass through any safe
  cell (walkable floor, two clear blocks) in the source's 3×3 neighbourhood.
- **Half-block correction.** An unknown body starts at the source cell centre, so its edge covers half a block less
  than legacy's centre-to-centre cost assumes. Such an edge now adds `0.5 · hypot(ddx, ddz) / steadyDisp` ticks. A
  steady sprint edge from an unknown body then prices within 1 tick of legacy (`steadySprintMatchesLegacyCost`).
- **Horizon (`simEdgeHorizon`, default 32).** Simulating every edge made the search about 3.7× slower per node and
  allocated about 120× more. Twenty of 60 headless queries timed out, against 4 for A* (BENCHMARKS.md, Phase 05,
  headless run 1). Edges are now simulated only out of cells within Chebyshev distance 32 of the search start. Beyond
  that every edge is legacy and every body unknown. The far part of a plan is still executed, but its shape is chosen
  by legacy costs, so any gain is limited to roughly the first 32 blocks of each search.
