# Training circuits

Headless task harness for later pathfinding autotune, PvE autotune, and a swappable task model. It does not run inside A*. With `trainingTask` off (the default), `#train` returns immediately and pathing is unchanged. `trainingGearChoice` also defaults off. Off, circuit rows stay on the baseline below.

## Worlds

Each run creates and resets an in-memory world. The seed selects an authored layout. These are not Minecraft `level.dat` saves.

| Seed | Circuit | Biome | What the bot must do |
| --- | --- | --- | --- |
| 71001 | `log-planks` | plains, 0.8 | Punch an oak log, craft planks in the inventory grid, place one on the deposit. |
| 71002 | `stone-step` | hills, 0.2 | Punch two logs, craft sticks and a wooden pick at the crafting table, step up, mine stone, place cobble. Hotbar starts with 8 dirt. |
| 71003 | `night-listen` | forest, 0.7, night | Same plank deposit. The log starts to the east, outside the look cone. A zombie, water and lava are off to the side. |
| 71004 | `compass-hunt` | plains, 0.8 | Walk to a lodestone that starts outside the look cone. Legit mode has the compass cardinal. Full mode also has the block. |
| 71005 | `gear-camp` | mesa, 2.0, night | Opt-in, not in the default suite. No logs. A locker supplies the kit. Stone is south, a pit is west of the path, a zombie stands east of it. |

No creative give. The dirt on 71002 is the starting kit and is not a tool. A wooden pick is a crafting-table recipe: 2 planks make 4 sticks, 3 planks and 2 sticks make the pick. Stone does not break by hand.

`#train` and `TrainingMain` both call this harness. They do not open a game window.

```
scripts/training-suite.sh
```

On the box, the same test is:

```
nice -n 10 ./gradlew --no-daemon --max-workers=2 test --tests baritone.training.TrainingSimTest
```

`TrainingMain` args are `[outDir] [suite|legit|full|circuit] [circuit] [modelId] [modelFile]`.

Headless scores from `TrainingSimTest` on 2026-10-25 (no client):

| Circuit | Mode | Ticks | Mine | Craft | Place | Gear |
| --- | --- | --- | --- | --- | --- | --- |
| log-planks | full | 21 | 11 | 15 | 21 | hand |
| stone-step | full | 65 | 10 | 41 | 65 | wooden pick, 1 durability |
| night-listen | full | 28 | 12 | 16 | 28 | hand |
| night-listen | legit | 38 | 14 | 18 | 38 | hand |
| compass-hunt | full | 12 | — | — | 12 | hand |
| compass-hunt | legit | 12 | — | — | 12 | hand |

The legit night run is the same deposit with the log outside the look cone, so it spends turns on the mob bearing before the log enters view. Compass-hunt starts facing west of a lodestone to the south. Both modes turn onto `COMPASS@S` and walk 11 blocks. The arrival tick is the place column. Deaths and path failures were 0. The task file does not contain `riseBlocks`; that count is only in `path-signals.jsonl` (1 on the hill circuit, 0 on the others).

## Legit mode

`trainingLegit` defaults off. Full mode lists every task block and entity for pathfinding autotune that needs ground truth. Legit mode leaves those lists empty. Live pathing, the compass needle, the pie chart and sound cues are described in [LEGIT.md](LEGIT.md). The model then gets:

- Blocks inside 8 blocks and about 50 degrees of look, plus the block directly in front.
- Its own feet, facing, biome name and temperature (the F3 readout).
- The pie-chart top: `chunkBuilder` while mining, `soundEngine` when a sound is playing, otherwise `gameRenderer`.
- Sounds with a compass bearing and no entity id: footsteps, block break, mob ambient, mob hurt when a mob is adjacent, water, lava, and `COMPASS@N` (or E, S, W) while a circuit has a needle.

## Scores

Each run writes three things under the output directory:

- `runs.jsonl` and `runs.csv` — task score: checkpoint ticks, deaths (fall, void, knock-off), path failures, blocks broken and placed, durability, gear, bucket, cues.
- `buckets/<bucket>.jsonl` — the same task row. A file refuses a row from a different bucket, so a later autotune cannot overwrite one bucket with another.
- `path-signals.jsonl` — terrain, majority jump, rise and gap counts. Joined to the task row only by `runId`.

The bucket key is `biome|jumpMajority|terrain|mobPressure|inventoryPressure`.

## Gear choice

`trainingGearChoice` defaults off. Off, `#train` and `TrainingMain` play each circuit once, and `runs.jsonl` has no `gearScore` field. The scorer is not referenced from pathfinding, so search fingerprints and the Pi heap are unchanged.

On, the harness plays every kit that circuit can actually supply. A plank task has a hand. A cobble task with logs has a hand and a wooden pick. `gear-camp` has a locker: hand, wood, stone and iron picks, and no armor, leather, iron or diamond. A stock kit is played with no grant, so crafting stays inside the run. A locker kit spends equip ticks (wood 4, stone 14, iron 22, leather 6, iron armor 18, diamond 30), then the same policy runs.

The score is `ticks + deaths * 1000 + durability * 2`. Lower wins. Ties keep the earlier, cheaper kit. The winner is the `TrainingResult` (`gearScore` is added to that JSON line only). Every kit is a row in `gear-rows.jsonl` with `time`, `deaths`, `durability`, `score` and `chosen`. On the camp, no armor is knocked into the pit. Leather and a wooden pick win: heavier kits cost more equip ticks than their faster mining saves, and a death costs more than the whole job.

Headless opt-in: `-Dostinato.trainingGearChoice=true`. In game the setting is `trainingGearChoice`.

## Model hook

`ITrainingModel` is loaded by id. A file of `id=` and `delegate=heuristic` lines replaces the id. The built-in delegate is the gear heuristic. `ITrainingHarness` is the API seam for a later task matrix. This change does not depend on TenorClef.

## Pathbench

The harness is not referenced from `PathBench`, `AbstractNodeCostSearch`, or `PathDecisionMatrix`. Idle cost is the settings objects. Do not run `scripts/pathbench-phase1.sh` on a Surface Pro. The bench machine should confirm 5 reps and the 512 MB Pi heap against main before this is treated as heap-neutral in production numbers.
