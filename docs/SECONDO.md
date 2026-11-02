# Secondo

Secondo is a companion coordinator. It follows one primary player, Primo, and can step in to help. It decides what to ask for. It does not replace the systems that move, mine, or fight.

This document is the v1 design. The code on `feature/secondo-v1` is the first slice: follow, a small assist, and gates for features that are not on main yet. There is no live multiplayer benchmark in this pass.

## Roles

| | Who | What they do |
| --- | --- | --- |
| Primo | Another player, named or the nearest other player | The person being helped. Secondo never treats the local player as Primo. |
| Secondo | This client | Binds to Primo, picks a mode each tick, and asks Baritone for a goal. |

A bind is a session (`#secondo follow`). The master switch is `secondoEnabled` (default **off**). Both are required before any goal is set. A bind while the switch is off is remembered as a session only until stop or cancel; pathing does not change.

## What Secondo owns

- The bind (nearest player, or a name).
- The mode for this tick: off, follow, path to a break, path onto a drop, or stay with Primo during a fight.
- The settings and the `#secondo` command.
- The feature gates below. A gate that is absent or off changes nothing. A gate that is ready still does not call the feature in v1.

## What Secondo composes

These already exist on main. Secondo calls them, or uses the same goal types they use. It does not copy their search, their combat, or their movement math.

| Need | Existing piece | How v1 uses it |
| --- | --- | --- |
| Stick to Primo | `GoalNear`, the goal `FollowProcess.towards` builds | Secondo sets `GoalNear` at `secondoFollowDistance`. It does not change `followRadius` or `followOffsetDistance`. |
| Walk into a drop | `GoalBlock`, the goal `FollowProcess.pickup` uses (`into`) | When assist and pickup are on, the goal is that block. Walking into the item is the pickup. |
| Retarget a moving player | `PathingCommandType.REVALIDATE_GOAL_AND_PATH` | Same command type `FollowProcess` returns. |
| Get somewhere | Baritone pathing, then the travel stack already configured (`travelStack`, kinematic, physics) | Secondo only sets a goal. It does not pick a movement backend. |
| Mine a typed block | `IMineProcess` | Not called. `mine` searches by block type and would wander. v1 paths next to the one cell Primo is breaking. |
| Fight | `IPvpProcess` (priority 2) | Not called. Only one process owns pathing, and PvP taking over would cancel the Secondo session (`onLostControl`). v1 records the request and stays on the follow goal. |
| Chat | `#secondo` | The control surface, next to `#set`. |

`#follow` is unchanged. Secondo is a separate process with priority `-0.5` (stock processes sit at `-1`, PvP at `2`). While it is inactive it loses no tie and sets no goal.

## Follow modes

1. **Off.** `secondoEnabled` is false, or no session is bound, or Primo is not loaded. `isActive` is false. No goal.
2. **Follow.** Bound, Primo is loaded, assist does not apply. `GoalNear` around Primo's feet, radius `secondoFollowDistance` (default 4, clamped to 0..16).
3. **Assist break.** `secondoAssist` is on, Primo is swinging into a block, and that block is within `secondoAssistRadius` of Secondo (default 6). `GoalNear` around the block, radius `secondoAssistStand` (default 2). A farther break does not pull Secondo off follow.
4. **Assist pickup.** Assist is on, `secondoPickup` is on (default true), and a dropped item is within `secondoPickupRadius` (default 6). `GoalBlock` on that item.
5. **Assist combat.** Assist is on, `secondoCombatAssist` is on (default **false**), and Primo was just hurt or is swinging at a living entity. Goal stays the follow `GoalNear`. The threat name is recorded. No new fight controller starts.

Order when several are true: combat, then break, then pickup, then follow. Assist flags do nothing while `secondoEnabled` is off.

Breaking vs fighting is a read of Primo, not a new combat model: a swing toward a living entity within 4.5 blocks counts as a fight; otherwise a swing into a block within 5 blocks counts as a break. `hurtTime` with `getLastHurtByMob` also counts as a fight.

## Assist: live vs stub

| Piece | v1 |
| --- | --- |
| Path next to Primo | Live. `GoalNear`. |
| Path next to the block Primo is breaking | Live. `GoalNear`. |
| Path onto a nearby drop | Live. `GoalBlock`. Vanilla picks the item up on contact. |
| Left-click the block | Stub. The decision is `Request.BREAK`. No swing is sent. |
| Start PvP / aim a weapon | Stub. The decision is `Request.COMBAT` plus the threat name. `IPvpProcess.attackPlayer` is not called. |
| Share a mine plan, pass items, cover Primo | Not in v1. |

## Settings

All of these are normal settings (`#set`, the settings screen, Advanced, with the other Ostinato switches).

| Setting | Default | Role |
| --- | --- | --- |
| `secondoEnabled` | false | Master switch. Off means no goal, including during path benchmarks. |
| `secondoFollowDistance` | 4 | `GoalNear` radius around Primo. |
| `secondoAssist` | false | Allow break and pickup paths. |
| `secondoAssistRadius` | 6 | Ignore a break farther than this from Secondo. |
| `secondoAssistStand` | 2 | How close to stand to that block. |
| `secondoPickup` | true | Used only while assist is on. |
| `secondoPickupRadius` | 6 | Drop range from Secondo. |
| `secondoCombatAssist` | false | Record a fight and keep the follow goal. |

`#cancel` and any higher-priority process that takes pathing call `onLostControl` and clear the bind, the same way `#follow` is cleared. Turning `secondoEnabled` off does not. The session stays, and pathing pauses until the switch is on again or the session is stopped.

## Chat

```
#secondo follow              nearest other player
#secondo follow <name>       that player, once they are loaded
#secondo stop
#secondo status              bind, mode, and the feature gates
#secondo assist [on|off]     sets secondoAssist (alone flips it)
```

While `secondoEnabled` is off, follow still stores the bind and the reply says pathing is unchanged.

## Dependency map

Secondo is a policy layer. The rows are the systems it is allowed to ask. "On main today" is this branch (`origin/main` at the maze-tunnels merge). The PPE-stack types are the ones on the Ostinato-ppe branch. This tree does not modify that branch. Adapters no-op until those types exist here.

### On main today

| System | Owned by | Secondo |
| --- | --- | --- |
| Path search, costs, Pi heap | Baritone A* and the pi-cheap flag | Does not touch them. An idle process allocates no search nodes. |
| `FollowProcess` / `GoalNear` / `GoalBlock` | Baritone | Uses the goal classes. Does not run a second follow process. |
| Kinematic / physics / `travelStack` | Movement engine | Inherited. A follow goal travels by whatever is already selected. |
| `IMineProcess`, builder, inventory pauser | Baritone processes | Not called in v1. Later assist should ask them for a typed job, not reimplement gathering. |
| `IPvpProcess` | Combat | Record-only in v1. Later, a yield has to hand off and resume without a second combat stack. |
| Settings and chat | Baritone | `#set` and `#secondo`. |

### PPE stack (not on main)

Gates live in `SecondoFeatures`. State is `absent` (class not on the classpath), `off` (class present, its own flag off), or `ready` (class present and flag on). `invoke` returns false in every state. `ready` means the next phase may call it. It does not mean v1 calls it.

| Feature | Planned type | Flag Secondo reads | What Secondo will ask for later | v1 |
| --- | --- | --- | --- | --- |
| PPE | `baritone.api.physics.IPredictivePhysics` | No master boolean. Consulted only when Grace Note, Presto, or Bombard is ready. | A prediction for a clutch, a takeoff, or a blast. `predict` / `submit`. | No-op. `ppeConsult=no` on this branch. |
| Grace Note | `baritone.api.gracenote.IGraceNote` | `ostinato.graceNote`, default `observe` when the class exists. `off` stays off. | Water clutch when Primo (or Secondo) is falling. Mode stays that feature's mode. | No-op. Class is absent here, so the default never applies. |
| Cadenza | `baritone.pathing.cadenza.Cadenza` | Setting `cadenza`, default false. | When assist breaks or places: stand cells, aim margin (`REACH_MARGIN`), cache drop via `CadenzaCache.invalidateNear`, and `CadenzaGoal` equality so the same item goals in a new order do not look like a new goal. | No-op. Assist stand stays a plain `GoalNear`. |
| Bombard | `baritone.bombard.Bombard` | `ostinato.bombard`, default `off`. | TNT layer of a fight, only inside the permit PvP already holds. | No-op. |
| Scherzo | `baritone.scherzo.Scherzo` | `ostinato.scherzo`, default `observe` when the class exists. | Where the opponent will be, for the existing fight. Observe mode must not move. | No-op. |
| Presto | `baritone.pathing.kinematic.Presto` | Setting `presto`, default false. | Nothing owned. If kinematic travel is on and `presto` is on, the controller already consults Presto. Secondo may later refuse a gap while Presto is untrusted. It must not reimplement takeoff checks. | No-op. |

If a flag is off, or the class is missing, follow still works. That is the degrade path.

## How this is not Presto

Presto is predictive movement execution: it can veto a jump the kinematic controller was about to press. It owns no plan and no keys.

Secondo is who to help and which goal to ask for. It may run while Presto is off. It may later read Presto's trust bit when Presto is on. It does not fork Presto, PPE, Cadenza, Grace Note, Bombard, or Scherzo.

## Phased plan

**v1 (this slice).** Settings default off. `#secondo follow` / `stop`. `GoalNear` follow. Assist paths for a nearby break and a nearby drop. Combat and the break click are recorded, not executed. Feature gates no-op. Unit tests cover the policy and the gates. No multiplayer bench.

**v1.1.** When `cadenza` is on and the class is on main, assist break uses Cadenza stand cells and invalidates the cache when the block changes. The break click goes through the existing break helper once the feet are in range, still not through a new miner.

**v2.** Combat assist yields pathing to `IPvpProcess` for the recorded threat and resumes the bind when the fight ends, without a second combat implementation. Scherzo and Bombard are read only while their modes say observe; live mode stays their own flags.

**v3.** Teamwork: shared mine target, item handoff, standing so Primo is not in the way. Still a policy over mine, inventory, and follow. Not a swarm AI and not a second pathfinder.

## Acceptance for v1

- `secondoEnabled` defaults false. With it off, `SecondoProcess.isActive` is false and no goal is built. Path cost code is untouched, so stock A* fingerprints and the Pi search heap are unchanged.
- `#secondo follow` and `#secondo follow <name>` bind. `#secondo stop` clears the bind. The parser is covered without a client.
- Enabled follow selects `GoalNear` at `secondoFollowDistance` around Primo, not `followRadius`.
- Assist off ignores a nearby break. Assist on, break inside the radius, selects `GoalNear` on that block and `Request.BREAK` (no swing).
- Assist on, drop inside the radius, selects `GoalBlock` and `Request.PICKUP`.
- `secondoCombatAssist` off stays on follow while Primo fights. On, the mode is combat, the goal is still Primo's `GoalNear`, and `Request.COMBAT` carries the name. PvP is not started.
- Every planned PPE-stack type is absent on this branch. `invoke` is false for absent, off, and ready. A ready Cadenza or Presto flag does not change the goal.
- `docs/SECONDO.md` is linked from `FEATURES.md`.

## Files

- `baritone.api.process.ISecondoProcess` — chat and API handle
- `baritone.secondo.SecondoPolicy` — mode choice
- `baritone.secondo.SecondoGoals` — `GoalNear` / `GoalBlock`
- `baritone.secondo.SecondoLook` — Primo swing, hurt, drops
- `baritone.secondo.SecondoFeatures` — gates, v1 no-op
- `baritone.secondo.SecondoCommands` — `#secondo` words
- `baritone.process.SecondoProcess` — the process
- `baritone.command.defaults.SecondoCommand` — chat
- Settings `secondo*` in `Settings`, the settings screen category, and `setting-descriptions.json`
