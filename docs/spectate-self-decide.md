# Self-deciding spectate fights

`spectateAutoDetect` is off by default. Off, the bot does not decide that nearby players are fighting, and it does not record those fights. `spectateLearn` behaves as it did before this setting existed. Pathfinding does not read the setting, so the pathbench fingerprints stay the same and the Pi search heap does not change.

On (`#set spectateAutoDetect true`), the bot watches players the client has loaded, out past `spectateRadius`, and records a fight on its own. No spectate command and no server menu. On a multiplayer server `spectateLearnOnServers` still has to be on. The bot never attacks.

A fight starts only when two players are close and one of these is true:

- one swings while facing the other, in reach
- one is hurt with the other beside them
- one takes a knockback shove (a sharp jump in horizontal speed) with the other beside them

Standing near each other is not a fight. The recording continues through short pauses (strafing, eating, a pearl). It stops when the fight is over, when the two walk more than 16 blocks apart, or when a fighter leaves render distance.

A sumo ledge counts. If the last attacker landed a hit or a shove within 5 seconds (100 ticks), that player wins when the victim:

- drops 8 blocks below the y where the fight started
- falls and dies
- drops into the void

An older opponent does not inherit that death. The result is written through the same fight book as any other spectated fight, including a bout that was only the one shove. A normal hit-death that is not a fall still has to be a real fight (three hits and a second of fighting) before it teaches anything.

`#set spectateAutoDetect false` turns it off again.
