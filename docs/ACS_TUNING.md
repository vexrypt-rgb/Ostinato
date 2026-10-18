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
