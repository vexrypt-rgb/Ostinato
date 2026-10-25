# Ladder clutch

`allowLadderClutch` is off by default. Off, fall costs match the previous planner: an existing ladder or vine is caught only when the drop into that cell is 11 blocks or less, and nothing is placed. Pathbench goldens stay on that setting, and the search does no extra work.

On:

- **Grab.** A ladder or vine already in the fall column is caught when a tick of the fall starts inside its cell, including drops past 11 blocks. A cell the fall skips is still skipped. Once the bot is on that climbable it holds no keys, so it slides down instead of climbing back up.
- **Place.** A ladder or vine on the hotbar can be placed on a wall beside the last blocks of the fall, same as before. A water bucket still wins when it is no more expensive. The placed ladder is broken and picked up when `pickupLadders` is on. Vines are left on the wall.
- **Miss.** A place is not offered when the plain fall would leave you below 1 health, or below `experimentalMinHealth` while `experimentalMovement` is on. A miss is the whole fall.
