# God bridge

`allowGodBridge` is off by default. Off, a traverse that has to place against the block it is standing on still sneaks out to the edge, and the planner still prices that sneak. Pathbench goldens stay on that setting, and the search does nothing extra.

On, and only when that block is the only face to place against:

- The bot turns to face back along the bridge and walks backward at walking speed. It does not sneak and it does not stop on the block.
- It right-clicks on the tick the far face of the block underfoot is under the crosshair. That face is the one on the destination side. The new block lands in the gap and the walk continues onto it.
- The camera catches up a tick later, so the pitch is chosen for where the next tick will be. `GodBridge` replays that walk with the same ground friction as `TickSim` and checks that a tick falls inside the window, still supported by the block (feet centre at most 0.3 past the face).
- If the click would be late, the bot is still turning when the edge is close, or it has already gone past the edge, that tick sneaks and the old placement finishes the block.

Left on the old path: ice, slime, honey, magma, soul sand, half slabs, water, ladders, vines, and blocks a click opens (chests, furnaces, crafting tables). A neighbouring block to place against is unchanged. `assumeSafeWalk` also keeps the sneak, because this walk has to step off the edge.

The planner prices the block as one block of walking plus the usual placement penalty, so a bridge costs less than a sneak and is chosen more often.

The camera faces backward while this runs. A click that does not place a block is a fall. Stopping on the block can coast off the edge, which is why a late click sneaks instead of freezing.

`#set allowGodBridge true` turns it on.
