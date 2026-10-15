# Credits

## Soprano

Parts of Ostinato are ported from [Soprano](https://github.com/AverWasTaken/soprano) by AverWasTaken,
licensed under the GNU Lesser General Public License v3.0 (the same license as Ostinato and Baritone).
Each ported file names its Soprano source in a comment.

| Ostinato file | From Soprano |
|---|---|
| `pathing/calc/openset/BinaryHeapOpenSet.java` | `BinaryHeapOpenSet.java` (heap with a parallel key array) |
| `pathing/calc/AbstractNodeCostSearch.java` | `AbstractNodeCostSearch.java` (cell-based node map, inverse heuristic coefficients) |
| `pathing/calc/AStarPathFinder.java` | `AStarPathFinder.java` (cached world bounds, per-node favoring cache) |
| `pathing/calc/PathNode.java` | `PathNode.java` (cached favoring) |
| `utils/pathing/SearchCache.java` | `SearchCache.java` (per-search mining-cost memo; mining buffers only) |
| `pathing/movement/CalculationContext.java`, `pathing/movement/MovementHelper.java` | `CalculationContext.java`, `MovementHelper.java` (memo hookup, cached minY/maxY) |
| `process/BoatProcess.java` (sail steering) | `pathing/path/BoatTrip.java`: `ride()`, `smoothLane()`, `stepBoat()`, `lateralAt()`, `pointAlong()`, `headingAlong()`, `hullClips()` |

The movement-tech ideas credited in the README (ladder and vine jumps, chained jumps, ladder clutch)
also come from Soprano.
