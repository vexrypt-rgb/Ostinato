# Movement and travel

Ostinato paths with Baritone's A* planner and executes the path with per-move controllers. On top of that it
adds a **kinematic** driver for plain walking, humanised camera steering and several optional moves.
Change any setting here with `#set <name> <value>` ([Settings](settings.md)).

## Kinematic travel
| Setting | Default | Effect |
| --- | --- | --- |
| `kinematicTravel` | true | Drive plain walking stretches with a physics look-ahead controller instead of per-movement logic |
| `slowKinematic` | false | Slower, more careful physics mode that also covers what `kinematicTravel` hands back to Baritone (so far: swimming). Still being worked on |
| `headSteering` | true | Turn by moving the camera like a mouse, W held, rather than snapping rotation to the path each tick |
| `pathWander` | 0.25 | Blocks the bot may drift off the path line on open ground; 0 = ruler-straight. Jumps, edges and stretch ends always follow the line |
| `kinematicTrace` | off | Developer trace of the controller |

Turn `kinematicTravel` off to get classic Baritone movement for a comparison.

## Optional and experimental moves
| Setting | Default | Effect |
| --- | --- | --- |
| `allowParkour` | on (upstream) | Jump gaps |
| `allowMomentumJumps` | true | Longer jumps using run-up momentum |
| `allowClimbJumps` | false | Jump onto/off ladders and vines across a gap. Needs `allowParkour` |
| `allowLadderClutch` | false | Survive a long fall by placing a ladder or vine on a wall beside the last blocks. Needs a ladder or vine on the hotbar |
| `pickupLadders` | true | Take placed ladders back |
| `pitfallAvoidance` | true | Never stand on sand/gravel/concrete powder resting on a block with no collision |
| `sprintJump` | false | Sprint-jump option |
| `allowBoats` | true | Place and ride a boat across big water when faster than swimming |
| `experimentalMovement` | off | Bundle switch for the experimental moves; tuned by `experimentalJumpBias` (0.9), `experimentalBlockPlacementPenalty` (5) and `experimentalMinHealth` (12) |

Experimental moves are not tested as thoroughly as stock Baritone ones; turn them on one at a time.
Not every setting exists on every branch (for example `physicsTravel` is on older versions); `#set list` is authoritative.

## Backends
`movementBackend` is `baritone`, `tungsten` or `auto` (default). Mining, schematics and inventory always stay
on Ostinato. A Tungsten jar is optional and separate; see the [README](../../README.md).

## Elytra
`#elytra` flies to the current goal. Read the `elytra*` settings first; flying requires accepting
`elytraTermsAccepted`, and some features need a native library: `#elytra supported` says whether your PC has one.
`#elytra reset` restarts the process keeping the goal; `#elytra repack` re-queues chunks.

## Commands that move you
`#goto`, `#thisway`, `#axis`, `#surface`, `#follow`, `#come`, `#explore`, `#wp goto`. See [Commands](commands.md).
Use `#eta` to see the estimate and `#proc` to see which process currently controls movement.

## Simulation checks (developers)
Start the game with `-Dostinato.simbench=jump,climb,chain` (or `all`), plus `-Dostinato.simbench.limit=N` and
`-Dostinato.simbench.exit=true`, to build test courses and write `simbench` and `simtrace` CSVs. TenorClef's
[`@show`](https://github.com/vexrypt-rgb/TenorClef/blob/main/docs/guides/showcase.md) builds similar courses
in a creative world.

More detail: [Movement engine](../MOVEMENT_ENGINE.md), [Swim port](../SWIM_PORT.md).
