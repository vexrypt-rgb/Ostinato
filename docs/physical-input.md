# Physical mouse and keys

`physicalInput` is off by default. Off, every look and every key the bot already emits is left as it is. Pathfinding does not read the setting, so the pathbench goldens stay the same and the search heap does not change.

On, a tick (50 ms) can only emit what a hand can do:

- A look speeds up. From rest, yaw changes by at most 25 degrees on the first tick and never by more than 60. Pitch is 20, then 45. A fast flick cannot reverse in that same tick, and the aim cannot snap to a new point. The step is a whole number of mouse counts at the current sensitivity.
- At most two keys change state. Keys being released go first, then the change that has waited longest. Anything else waits for a later tick. Pressing or releasing every key at once is spread over several ticks.

Turning it on does not write the other settings. `humanInputCaps` (a hard 120 degree yaw cap, on by default) still applies on its own. `physicalInput` is the stricter gate, and only when it is on.

A parkour tick that wants sprint, forward and jump together is three key changes, so the third waits one tick. Leave the setting off when that one-tick chord is required.

`#set physicalInput true` turns it on.
