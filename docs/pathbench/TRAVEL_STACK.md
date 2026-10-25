# Travel stack

The block route is still Baritone's A*. What changed is who presses the keys.

| Order | Layer | When it runs |
| --- | --- | --- |
| 1 | Kinematic | `kinematicTravel` (default on). The walk look-ahead, then the existing swim, fall, ladder, mine, pillar, clutch, extension, takeoff and boat controllers. |
| 2 | Physics | After the kinematic family declines the spot. Auto includes this. `physicsTravel` still forces a physics attempt when the stack did not ask. |
| 3 | Sim probe | `simTravel` (default on). Jump versus no-jump each tick, sprinting toward the landing, checked in `PlayerSim`. One search per spot, then a 128-entry cache. |
| 4 | Baritone movement | Temporary backup. It runs only when the three layers above decline. |

`travelStack` picks the order: `auto` (default), `kinematic`, `physics`, `sim`, or `baritone`. `getMovementBackendPreference()` returns that choice. `baritone` is the backup alone, for a comparison run.

The A* search is unchanged, so path cost, length, node count and peak heap on the Phase 1 bench stay the Phase 1 numbers. Confirm on the bench machine with `scripts/pathbench-phase1.sh travel normal` and `scripts/pathbench-phase1.sh travel pi`. Do not run that on a Surface Pro.

## Fixed goals (headless PlayerSim)

These are the spots the sim probe is measured on. A found plan is replayed in `PlayerSim` and lands. The backup is not used for that spot. An eight-block gap is a miss and is cached, so the search does not repeat.

| Goal | World | Start | Landing | Bound |
| --- | --- | --- | --- | --- |
| one-gap | floor z=-2..0, landing z=2, one-block hole | 0.5, 0, -1.5 facing +z | (0, 0, 2) | at most 24 ticks, 480 expansions |
| step-up | floor under the start, one block up at z=1 | 0.5, 0, 0.3 | (0, 1, 1) | at most 20 ticks |
| eight-gap | single floor block | 0.5, 0, 0.5 | (0, 0, 12) | miss, second call does not search |

Headless run of `SimProbeTest` (PlayerSim only, no client), 2026-10-25:

| Goal | Result | Backup |
| --- | --- | --- |
| one-gap | 13 ticks, 91 expansions, under 1 ms | not used; the replay stands on the landing |
| step-up | 9 ticks, 47 expansions, under 1 ms | not used; the replay stands on the landing |
| eight-gap | miss, 92 expansions, second call does not search | Baritone movement still has the spot |

Before this stack those spots were left to Baritone's per-movement logic. After it, a found plan is what gets flown. Wall-clock travel across a world was not timed here: this PC is a Surface Pro, and the Phase 1 pathbench stays on the bench machine. The A* search was not changed, so the Phase 1 cost, length and peak-heap numbers still apply until that machine re-runs `scripts/pathbench-phase1.sh`.

Run just this layer:

```
scripts/travelstack-smoke.sh
```

That is `nice -n 10 ./gradlew --no-daemon --max-workers=2 test --tests baritone.pathing.travel.TravelStackTest --tests baritone.pathing.kinematic.SimProbeTest`.
