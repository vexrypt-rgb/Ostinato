# Autotune Combat System: measurements and the spsa mode

Ostinato: this page holds what was measured about how fast ACS can learn, and why the `spsa` mode exists.

## Phase 0: what the fight logs say (measured)

Corpus: 5108 PvpRecorder logs (86.7 MB gz, 5054 VexBot fights, 2026-10-03..08, 1.21.11 bench), read by
`tools/pvpsummary.py study`. Windows are rebuilt the way `CombatOutcome` counts them (200 active ticks, foe within
`CombatGeometry.DRIVE`, a drop credited to a click within 2 ticks). Raw numbers: `docs/acs-phase0-study.json`.

| kit | fights | win rate | windows/fight | CV dealt/tick per window | lag-1 autocorr of windows | design effect | fight score SD (bars) |
|---|---:|---:|---:|---:|---:|---:|---:|
| mace+shield | 1211 | 0.533 | 1.18 | 1.40 | 0.09 | 1.06 | 0.52 |
| mace+spear+shield | 908 | 0.324 | 2.31 | 1.62 | 0.41 | 1.64 | 0.95 |
| sword+bow+shield | 795 | 0.543 | 4.70 | 0.45 | 0.27 | 2.04 | 2.45 |
| sword+shield | 500 | 0.640 | 3.96 | 0.47 | 0.37 | 2.38 | 2.52 |
| anchor+sword+totem | 402 | 0.037 | 2.73 | 3.26 | -0.01 | 1.80 | 1.39 |
| axe+sword+shield | 355 | 0.625 | 1.36 | 0.40 | 0.64 | 2.25 | 1.37 |
| crystal+sword+totem | 322 | 0.003 | 4.04 | 2.17 | 0.55 | 2.86 | 1.22 |
| axe+shield | 269 | 0.721 | 2.93 | 0.39 | 0.11 | 1.21 | 1.85 |
| mace+sword+shield | 239 | 0.820 | 2.05 | 1.20 | 0.22 | 1.34 | 0.63 |

What follows from it:
- The rebuilt sword+shield window CV (0.468) matches the 0.47 `CombatOutcome.Rules.WINDOW` was set from, so the
  rebuild counts like the game does.
- Windows inside one fight are correlated (lag-1 up to 0.64). Treating them as independent understates the variance
  of a rate by the design effect (1.2x to 2.9x). The coordinate tuner's z-scores are therefore optimistic on the
  kits with high design effect.
- Crystal and anchor kits win 0.3% and 3.7% of their fights against VexBot: that is a missing skill, not a tuning
  problem, and no number moved by ACS can fix it.
- The live coordinate-mode run (2026-10-12, 400 rounds, 3 h 34 min, about 112 rounds an hour, 89% of wall time in
  rounds) reached 10 verdicts, all drops, no keeps. One trial took the last 2.2 hours.
- A compressed summary (`tools/pvpsummary.py summary`) averages about 2.8 k characters a fight against the raw
  per-tick log, and keeps every event's absolute tick and the gap to the one before.

## Phase 1: the spsa mode (`pvpAutotuneMode spsa`)

`CombatSpsa` moves every number of a kit at once (simultaneous perturbation, as Stockfish's Fishtest tunes), in
pairs of whole fights fought back to back, and puts a setting in force only after a sequential probability ratio
test on fresh paired fights. A fight is worth 1 for a win, 0 for a death, 1/2 otherwise, plus 0.1 times its score
as the tie-break. Nothing changes inside a fight, so one arm's play cannot spill into the other's. Aim and click
humanisation are not tuned numbers and are never moved.

The constants were chosen with the race in `CombatSpsaTest` (`gradlew test -Postinato_bench --tests "*CombatSpsaTest"`):
made-up fights whose win chance is a hill of +10 points two steps off the baselines on three numbers, with and
without the per-style win rates of the 2026-10-12 bench as blocks. Mean true win chance of the trusted setting, 60
seeds, hill width 1.5 steps (`docs` numbers are copied from that run):

| fights (hours at 112/h) | 1000 (8.9) | 2000 (17.9) | 4000 (35.7) | 8000 (71.4) |
|---|---:|---:|---:|---:|
| three whole-fight numbers, spsa | 0.511 | 0.519 | 0.531 | 0.555 |
| same, coordinate per-fight learner | 0.500 | 0.500 | 0.501 | 0.501 |
| two per-window + one whole-fight, spsa | 0.508 | 0.511 | 0.525 | 0.545 |
| nothing to find (baseline is the top), spsa | 0.500 | 0.500 | 0.500 | 0.500 |

On the null landscape neither mode kept anything in 60 seeds. With a wider hill (2.5 steps) spsa reaches 0.565 at
4000 fights where coordinate reaches 0.503. These are made-up fights: they rank the two learners, and say nothing
about how much a real kit can gain. A live bench run is needed for that.

## Parity: same code, same fight, same ticks

A headless and a real client running the same build gave different fight results. Before any headless number counts
for tuning, the same drill has to play out tick for tick on both. The drill: a 1000-HP still husk, fixed seed, the same
world copy and options.txt, two 400-tick rounds.

Tools (all inert unless their property is set):

- `-Dostinato.seed=N` (`OstinatoRandom`): every Ostinato RNG that steers play is seeded from N plus its own name
  (look hand noise, aim, fight sampling, SPSA, hotbar pick, kinematic wander). Without it they stay clock-seeded.
- `-Dostinato.trace=FILE` (`ParityTrace`): one line per client tick with position, velocity, rotation at tick start and
  end, keys and forced inputs, the fight decision, slot and cooldown, hand-noise draws, look target, path, the
  target's state, and (in lockstep) the server's view of both.
- `-Dostinato.lockstep=true` (`Lockstep` plus three mixins): the integrated server runs exactly one tick per client
  tick. The client sends its packets and waits until the server has read them, then lets one server tick run and waits
  for it (each go is numbered, and only a gated tick answers it). It then waits until the client has read all the
  replies, and handles them at the start of its next tick.
- `tools/paritydiff.py A B --ignore fr,t,lm,st`: lines the two traces up on the first fight tick and reports the first
  tick that differs, with context.

Root causes found, in the order they showed up (first divergence on the box, run against run):

| cause | effect | fix |
|---|---|---|
| per-frame rotation smoothing | view at tick start depended on frame count | `frame()` is cosmetic; the per-tick model owns the rotation (`tickStart`/`planFrameStep`) |
| unseeded RNGs | hand noise, aim, sampling differ run to run | `OstinatoRandom` |
| zombie sunburn / mob RNG | dummy took fire damage at random | drill dummy is a NoAI husk |
| free-running integrated server | hits landed a tick apart (div. at fight tick 0-1) | lockstep |
| async path search | a chase started a tick early or late | lockstep waits for the search |
| packets handled once per frame | at <20 fps two ticks ran on one frame's packets | `processQueuedPackets` at every tick start (`-Dostinato.tickPackets=false` restores vanilla) |
| stale "done" from an ungated server tick | server one tick behind the client (div. at fight tick 4) | numbered go/done handshake |
| server gate timing out during slow frames / `submit().join()` | the server ticked on its own; mob tracker phase shifted (div. at fight tick 39, then 450) | gate waits 60 s and runs this tick's tasks while it waits (`shouldRun` override) |

Result on the box, lockstep on: two runs side by side match on every field over all 900 fight ticks, with 0 lockstep
misses. So does a run at maxFps 260 / render distance 12 against one at maxFps 30 / render distance 6. Lockstep also
halves the drill's wall time (68 s vs 131 s). Lockstep off, the results depend on timing by design; that is the
vanilla behaviour a real client has.
Real client: the same drill through Gradle `runClient` on the Windows PC (OpenGL, real GPU, window on the second
monitor, no focus) against the box's headless Vulkan/lavapipe client matched on every field over the 860 compared fight
ticks, with 0 lockstep misses on either side. With lockstep off, two box runs of the same build already part at fight
tick 7 (a jump one tick apart, path ready a tick early or late). That is the "same code, different results" the
headless runs showed, so bench numbers are taken with `-Dostinato.lockstep=true`.
The PC kit (`run-parity.ps1`: clone from a bundle, bench hooks, world copy, options.txt, trace) is in
`MinecraftDev\acs-parity-kit`.

## Opponents on 26.3 and what the bench measures (2026-10-18)

| opponent | mod (license) | role | style |
|---|---|---|---|
| Carpet PvP fake players, modes sword/mace/crystal/anchor/ranged/smp × beginner..expert | carpet-pvp 26.3-19 (Apache-2.0) | tuning pool | planner bot, strafes, crit-jumps, shield, shieldbreak |
| SickSheet bot, easy..impossible | SickSheets (MIT on Modrinth, CC0 in jar) | held out | blocks almost nonstop, combo attack |
| SMP PvP bots, easy..perfect | smp-pvp-bot 1.2 on fabric-carpet (ARR, local use only) | held out | axe-swaps on our shield, eats apples in openings |
| VexBot | 26.2 jar (ARR) | not usable | crashes on 26.3 (missing ServerPlayer APIs, IllegalAccessError) |
| Mineflayer + mineflayer-pvp | MIT | not usable | minecraft-data stops at 26.1 |

Bot-vs-bot to spectate: `/workspace/acs-farm/w5` + `spectate.sh`, datapack `ospec`
(`/function ospec:mixes {i:0..5}`, `ospec:duel {a,am,ad,b,bm,bd}`, `ospec:ffa`, `ospec:teams`, `ospec:stop`).

Bench bugs found and fixed before any numbers counted:
- Carpet PvP's fake player never dies. `die()` sets 1 HP, stamps `diedTick()` and resets to 20 the next tick, so
  every win was scored as a loss (0/25). The bench reads `diedTick()`, and `PvpProcess.foeKilled` closes the fight
  record so the learner sees the win too.
- With `immediate_respawn`, a fake player's death swaps its ServerPlayer object; that also counts as a kill now.
- Bots that fight on their own (SickSheet, SMP) hit us during the 20-tick settle before our kit, so rounds began at
  ~11 HP. Both are healed after the kits.

Baselines (sword kit, lockstep, no autotune):

| opponent | wins |
|---|---|
| Carpet PvP, all five difficulties | 32/33 |
| SickSheet | 8/12 (impossible 0/3, ~0 damage dealt: it blocks nonstop) |
| SMP bots | 1/12 |

SPSA against Carpet PvP, exploring, no verdict yet: c0=3/a=2 won 82% (78/95), c0=1/a=0.5 won 90% (91/101).
Probing costs wins, and the default probe is too wide. Carpet is near its ceiling for this kit, so the next tuning pool
adds the harder Carpet modes and kits.

Rule A/Bs on the held-out bots (kept only if they win):
- Cut a bite under fire (`-Dostinato.biteCut=true`): 12/32 on vs 14/26 off. Not kept (opt-in only).
- Shield-break sooner against a blocking foe (`-Dostinato.axeGap=8` vs 25), first run: SickSheet 9/12 vs 8/12, SMP 3/12 vs 1/12.
  Paired confirmation (SMP, same seed, 24 rounds each): 2/24 vs 4/24. Combined on SMP it's 5/36 for each, so the default stays 25.

### SPSA verdicts (2026-10-18, Carpet PvP, sword kit)
- c0=1, a=0.5: 277/300 (92.3%). One candidate tested, rejected by GSPRT (21 pairs, -0.252 a pair, llr -1.61). Nothing kept.
- c0=3, a=2: 243/284 (85.6%), stuck at round 285 (Carpet spawn). One candidate rejected (25 pairs, -0.193 a pair, llr -1.66). Nothing kept.
- Both runs cost wins while exploring, and neither found a better point: on this pool the hand-tuned defaults are at the ceiling.
  Each run spent ~150 pairs on gradient steps for one test, so a run of this size tests one or two points.

### Our axe vs the SMP bots' shield
Not a disable bug. The SMP bots tap the shield: about 240 of 370 uses last 1-4 ticks, which is under 26.3's 5-tick
warm-up, so the shield never blocks. CombatMelee counted any shield in use as raised. It held the sword and swapped
to the axe (the swap resets the charge) on every tap. Only 31 of 334 axe swaps met a real block.
`-Dostinato.raiseIsBlock=false` makes the warm-up count as no block: 3/24 vs 2/24 on paired seeds, axe swaps
6.8 -> 4.0 per round, damage taken 46.3 -> 43.2. Directionally better but not significant, so it stays opt-in.
The SMP bots out-trade us: they land 272 hits to our 194, at about the same swing count and distance.

### Footing guard (movement-combat item 1) vs SMP bots in a lava ring (`-Dostinato.vex.arena=lava`, bench-only)
A 19x11 pad ringed by lava at floor level. `-Dostinato.footing=true` (CombatFooting) plays our keys forward on PlayerSim,
with and without the foe's knockback. On the ground it picks keys that leave us where the next hit can't push us in,
and it won't start a bite (or cancels one) while a hit would put us in the lava.
48 rounds each, paired seed 41: off 4/48, 0.90 lava entries/round; on 3/48, 0.75 entries/round (36 vs 43, not
significant). Not kept: it stays opt-in.
What the logs showed:
- A hit carries us 2.5-4 blocks through the air, and no key steers there. We jump-strafe most of the time, so a hit
  near the rim is too late to correct.
- Most early entries came from eating at the rim: at a fifth of the pace, hit after hit walked us in.
- The old floor scan (ledgeGuard) never saw the lava, because the dirt under it counts as floor.
SMP bots win almost every round in this arena either way, so a safety gain shows in the entries count before the
win rate. Next: no crit-jumps toward a rim within knockback range.

### Wider tuning pool (Carpet PvP, harder levels and other kits, no autotune unless noted)
- mace kit, all five levels: 14/20 (expert 0/4).
- smp kit, all five levels: 12/19 (expert 0/3); the run stopped at 19 rounds.
- SPSA, mace kit vs skilled+expert (c0=1, a=0.5): running, 61/90 at 91 pairs (expert 21/44, skilled 40/46).
This is the new tuning pool: plenty of headroom, where the sword pool sat at 92-97%.
- Shield-tap fix, larger A/B (SMP bots, flat, paired seed 42, 48 rounds each): raiseIsBlock=false 7/48 (dealt 34.7, taken 42.5)
  vs default 8/48 (dealt 32.2, taken 40.4). Over both runs it's 10/72 for each. Mechanically correct, but it doesn't change
  results, so the default is kept.

### Why we lose to SMP bots (flat, sword kit, 48-round logs plus server-side counters)
| per side, 48 rounds | SMP bot | us |
|---|---|---|
| swings | 577 | 607 |
| hits that drew health | 487 (84%) | 407 (67%) |
| avg health per hit | 4.12 | 3.54 |
| crits | 0% | ~35% (falling at the swing) |
| gapples | 63 | 54, 53 of them begun within 4 blocks |
- Shield: server-side the bot blocks only 5-6% of ticks, and 0.6-1.2 of our attacks per round hit a block.
  Not the gap.
- The axe was a leak: 233 of 607 swings, 120 of them at low charge, about 90% into no block. They were triggered by
  shield taps and by `hiddenShield`, whose window only grows. `-Dostinato.hiddenShield=false -Dostinato.raiseIsBlock=false`
  (axe only on a visible block), 48 paired rounds: 11/48 vs 10/48, axe swings 6.4 -> 0.8 a round, damage taken
  43.4 -> 36.7, dealt 32.8 -> 29.1, damage per hit 3.54 -> 3.91. Fewer wasted swings, but the trade ratio barely
  moved, so it stays opt-in.
- Server truth: about 72% of our attacks hurt the bot (9.4 of 13.1 a round), at about 2.3-2.8 health per hurt by the
  damage_dealt stat. It deals ~3.7 per hit back with the same kit. The bot always swings full (gaps of 13-15 ticks).
  Half our sword swings are at 0.80-0.95 charge (`melee.duelCharge` 0.90), which loses damage and the crit.
  Next A/B: duelCharge 1.00.

### Footing v6 (adds: no crit-jump that a hit would carry off the rim), lava ring vs SMP, 48 paired rounds, seed 46
on: 6/48, 0.62 lava entries/round (30), taken 28.4. off: 4/48, 0.92/round (44), taken 32.4.
Pooled with v5: 66 vs 87 entries over 96 rounds each (-24%, p ~0.1). Consistently fewer, not yet significant. Opt-in
for now; one more 48-round pair would settle it.

### Bench artifact fixed: round-start saturation
The heal after the kits gave `saturation 1 20` (20 s, longer than a round), so both sides regenerated at full speed
all round. It's now 1 s. SMP numbers before 11:10 (UTC-7) used the 20 s version. A/Bs within one run stay comparable;
absolute win rates do not (an early fixed-bench arm runs ~46% vs SMP, where it was 10-20% before).

### Bench bug: respawned fake players were immune for ~40 ticks of every round
26.3 treats a player as invulnerable until its client reports loaded (`ServerGamePacketListenerImpl.hasClientLoaded`,
60-tick timeout). Carpet fake players have no client, so after each round's respawn the bot ignored our hits until
the timeout. The bench waits only 20 ticks, so about 40 ticks were left. Server-side miss logging: 96 of 166 attacks
that drew no hurt landed on a bot at 20 HP, fully charged, in reach and not blocking, at round ticks 12-42, with
`loaded=false`. The bench now marks the fake connection loaded at round start, after which there are no such misses.
This bias was in every SMP and SickSheet number before 11:40 (UTC-7): our opening swings were wasted while the bot
hit freely. The bias was the same in both arms of an A/B, but absolute held-out win rates were understated.
Carpet PvP bots die in place and never respawn, so the tuning pool wasn't affected.

### Clean SMP numbers on the fixed bench (both bench bugs fixed), 48 rounds each
| arm | seed | wins | taken | dealt | axe/round | server hit rate |
|---|---|---|---|---|---|---|
| defaults | 48 | 22/48 (46%) | 27.9 | 46.5 | 3.2 | 88% |
| visible shield only, duelCharge 0.90 | 48 | 20/48 | 31.5 | 44.7 | - | 96% |
| visible shield only, duelCharge 1.00 | 48 | 29/48 | 25.8 | 46.0 | - | 93% |
| visible shield only, duelCharge 1.00 | 49 | 27/48 | 30.2 | 47.9 | 0.9 | 93% |
| visible shield only, duelCharge 0.90 | 49 | 30/48 | 25.8 | 52.5 | 1.1 | 95% |
- duelCharge 1.00 vs 0.90: 56/96 vs 50/96 over two paired seeds. Not significant, so the default stays 0.90.
- Axe fix (hiddenShield/raiseIsBlock off) vs defaults, seed 48: 20/48 vs 22/48. The server hit rate rises 88% -> 96%,
  but wins don't. Stays opt-in.
- The "85-90% losses" against SMP were mostly the bench: on the fixed bench we win about half.
- Server-side hit rate is 88-96%, so the old 67% "hit rate" gap was mostly the spawn-immunity bug.
- SPSA: mace kit vs Carpet skilled+expert ran 317/457 (69%) over two runs, no verdict (the one test was tied, LLR
  0.02 after 117 pairs, and was lost on restart; fixed in cfbde690).
- Footing v6, third pair (fixed bench, lava ring, seed 50): on 25/48, 1.23 entries/round (59), taken 27.2;
  off 19/48, 1.44/round (69), taken 31.0. Pooled over three pairs: 125 vs 156 entries in 144 rounds each (-20%,
  p ~0.06), wins 34 vs 27. Every pair points the same way, but it's not significant yet, so it stays opt-in.
- SickSheet, clean baseline (defaults, seed 51): 33/48 (69%). easy 12/12, normal 11/12, hard 9/12, impossible 1/12.
