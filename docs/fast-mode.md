# fastMode

`fastMode` is off by default. Off, the planner and the executor use the other settings as they stand. Pathbench goldens stay on that, and the search does nothing extra.

On, it is a preset. It does not write the other settings, so turning it off hands those choices back. While it is on, these behave as if they were on:

- parkour, including the jump that places its own landing, neos, momentum jumps and climb jumps
- head-hitter jumps, which are parkour templates, so they are considered because parkour is on
- diagonal ascends and descends
- sprint jumping on a clear straight run
- ladder clutch
- swimming and boats
- an elytra takeoff from the ground (`elytraAutoJump`; the elytra process still needs its gear and terms)

Jumps are priced with `experimentalJumpBias` (0.9). Block placement costs at most `experimentalBlockPlacementPenalty` (5). A fall that hurts is allowed when nothing safer exists and health afterwards is still at least `fastModeMinHealth` (10, five hearts). If `experimentalMovement` is also on, the higher of the two floors wins. A half-heart of that damage costs `fallDamageCost` ticks.

While a path is being walked, and only then:

- a clear straight run aims at a point a few blocks ahead instead of the next block
- a turn of 60 degrees or less aims a third of a block past the corner and keeps sprinting
- a flat bend up to a right angle is walked straight across when the whole 0.6-wide body has air and a floor on every sample

Those three stay off in the nether, near lava, at 6 health or less, and while the step places a block or breaks one.

Not included: god bridging (`allowGodBridge` is its own movement), kinematic travel, and the TAS jump search. Those stay on their own settings.

`#set fastMode true` turns it on.
