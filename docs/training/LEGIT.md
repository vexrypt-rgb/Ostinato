# Legit training track

`trainingLegit` and `compassFollow` default off. Off, pathing still uses the world cache and the seed predictor, and the full-information circuits are unchanged. Pathbench goldens do not read these settings.

Turn `trainingLegit` on and pathing answers only from client chunks inside the render-distance square (Chebyshev distance in chunks). The world cache and the seed predictor are not consulted. Training decisions on that track may use:

- Blocks inside that view. The headless circuits use an 8-block look cone, which is the same rule at circuit scale.
- F3 biome name and base temperature at the player's feet.
- The debug pie's top slice, and only while the chart is open. A closed chart contributes nothing.
- Sounds the client is playing: footsteps, mining and breaking, mob ambient, mob hurt, water, lava. Each one carries a cardinal, not an entity id.
- The compass needle, below.

`#train legit` and `#train full` call the same `ITrainingHarness.run` and write the same `TrainingResult`. The mode field is `LEGIT` or `FULL`. Compare those rows. The harness itself is not rewritten.

## Compass

`compassFollow` follows a compass in the main hand, then the offhand, then hotbar slots 0–8. It stays idle unless `trainingLegit` is also on, and it loses to an explicit goal such as `#goto`. The goal is a 2-block neighbourhood of the block the client uses to draw the needle. `#compass` prints that reading, plus the F3 line, the pie, and recent sounds when the legit track is on.

| Compass | Where the target comes from | When there is no goal |
| --- | --- | --- |
| Lodestone | `lodestone_tracker` on the item, including `tracked: false` | Empty tracker, or the position is in another dimension. The needle spins. |
| Manhunt, lodestone | The same tracker, rewritten to the runner's block. That component is what the client draws. | Same as lodestone. |
| Manhunt, named player | Custom data `player`, `target`, `manhunt`, `tracked`, or `TrackedPlayer` (a name or a canonical UUID), resolved only against players loaded on this client. | The name is not a loaded entity. The tab list is not a position. |
| Unbound | The client's respawn position. | Respawn data is missing. |
| Recovery | The client's last death position. | The player has no death location, or it is in another dimension. |

A coordinate that is not already on the client is not used. The live goal is the block. The `compass-hunt` circuit (seed 71004) only gives the model the cardinal `COMPASS@S` until the lodestone enters the look cone. Full mode also lists `LODESTONE@4,1,16`. Both are scored by the same run.

```
#set trainingLegit true
#set compassFollow true
#compass
#train legit compass-hunt
#train full compass-hunt
```
