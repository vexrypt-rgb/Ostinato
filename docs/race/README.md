# Every block and every item

Opt-in race circuit `every_block_item`. It extends the training harness and the race recorder from the agent API. It does not run inside pathfinding. With `raceEveryBlockItem` off (the default), `#train` and the default suite stay on the stock circuits, and this package is not asked to read the registry.

The circuit is not in `trainingTasks`. Turning the switch on does not add it to a plain `#train`. Name it.

## What one race covers

1. **Items.** Every item in the item registry, minus the exclusion list below.
2. **Blocks.** Every block that has an inventory item, minus the same list. A block whose item form is air is not a target. That drops portals, fluids, fire, piston heads, moving pistons, and farmland. Water and lava count as the bucket items, not as blocks.
3. **Enchant.** Five checklist steps: `enchanting_table`, `bookshelves` (15 around the table), `lapis`, `levels` (level 30), `enchant` (one enchant applied).
4. **Netherite.** `ancient_debris`, `netherite_scrap`, `netherite_ingot`, `netherite_upgrade_smithing_template`, then `upgrade` (any netherite smithing result).
5. **Interactions.** Place, break, and use for door, button, lever, chest, furnace, crafting, and bed. That is 21 steps (`door.place` through `bed.use`).

Spawn eggs are items, so they are targets. Potion types, enchanted-book variants, and other component data are one registry id each (`potion`, `enchanted_book`). They are not expanded.

Overall progress adds the five stages. A netherite material therefore counts once as an item or block and again when that checklist step is completed. Enchant steps are not completed by obtaining the item. `bookshelves` and `levels` are counts, not ids.

## What is wired

`stone-step` already crafts at a crafting table. A race plays that existing circuit (no second world, no `runs.jsonl` row for it) and, when `craftTicks` is set, credits `crafting.use`. Inventory crafts such as `log-planks` do not count. Time on the race row is that circuit's tick count. Registry enumeration is not added as ticks.

Everything else is a score hook. `RaceProgress.collectItem`, `collectBlock`, `noteObtained`, and `complete(stage, step)` update the score. Unknown ids are ignored. `noteObtained` also completes a netherite material step when the id is one of the four items. `upgrade` stays explicit.

TODO for the overnight runner: collect the registry, place 15 bookshelves, reach level 30, enchant once, perform the netherite upgrade, and score the other interaction steps (door, button, lever, chest, furnace, bed, and crafting place/break).

Seed `71006` is only the id written on the race row. It is not a training-world layout.

## Exclusion list

A target is dropped when its path (the part after `:`) is one of these. `light` does not drop `light_blue_wool`. `barrier` does not drop `barrel`. The same paths apply to mod namespaces.

Air: `air`, `cave_air`, `void_air`.

Unobtainable or creative-only blocks: `bedrock`, `barrier`, `reinforced_deepslate`.

Command: `command_block`, `chain_command_block`, `repeating_command_block`, `command_block_minecart`.

Structure: `structure_block`, `structure_void`, `jigsaw`.

Spawners and vaults: `spawner`, `trial_spawner`, `ominous_trial_spawner`, `vault`, `ominous_vault`.

Portals: `end_portal`, `end_portal_frame`, `end_gateway`, `nether_portal`.

Technical: `light`, `moving_piston`, `piston_head`.

Creative and dev items: `debug_stick`, `knowledge_book`, `petrified_oak_slab`, `test_block`, `test_instance_block`.

Blocks with no inventory item are also dropped, even when the path is not in this list.

## How to run one race

In game, both switches have to be on. `trainingTask` gates `#train`. `raceEveryBlockItem` gates this circuit.

```
#set trainingTask true
#set raceEveryBlockItem true
#train every_block_item
```

Headless, from the repository (the property is the switch; there is no settings file):

```
-Dostinato.raceEveryBlockItem=true
```

`TrainingMain` args are `[outDir] [suite|legit|full|every_block_item] [circuit] [modelId] [modelFile]`.

```
build/race every_block_item
```

`legit` still only changes the stone-step slice. The full collection is not played in either mode.

While the switch is off, `#train every_block_item` prints `raceEveryBlockItem is off`. A default suite run does not enumerate the registry and does not write `races.jsonl`.

## Scoring format

The race appends one JSON line to `{outDir}/races.jsonl` through `JsonlRaceRecorder`. There is no API-key field. Stock training files (`runs.jsonl`, `runs.csv`, buckets, `path-signals.jsonl`) are not written for this circuit.

```json
{"runId":"every_block_item-71006-FULL-heuristic","hypothesis":"collect every block and every item","agent":"harness","provider":"local","model":"heuristic","metrics":{"circuit":"every_block_item","collected":"1","total":"1812","progress":"1/1812","timeTicks":"65","failures":"0","stage.items":"0/1400","stage.blocks":"0/380","stage.enchant":"0/5","stage.netherite":"0/5","stage.interact":"1/21","credited":"crafting.use"},"outcome":"incomplete"}
```

The numbers above are the shape, not a frozen registry size. `collected` and `total` are the sums of the five stages. `outcome` is `pass` only when `collected == total` and `failures` is 0. Otherwise it is `incomplete`. `credited` lists checklist steps that completed (enchant, netherite, interactions), not every item id. A foundation run credits `crafting.use` and leaves the other stages at 0.

`#train` prints the same summary after `progress=`.

## Worlds

This circuit does not create a Minecraft world folder. Cleanup is unchanged: `RaceWorldCleanup` deletes direct children named `race_*`, `hyp_*`, or `agentrun_*` only when `ostinato-race-root.txt` is present, and it keeps `vexflat` (any name starting with that), `pk_*`, `pkrun_*`, `SigilTest`, and `gs_world`.

Overnight A/B races use the same recorder and the same cleanup. They are off unless `overnightRaces` is on. See [overnight.md](overnight.md).

## Pathfinding

Nothing under `baritone.pathing` references this circuit. The registry scan runs only after the switch is on and the circuit is named. The Pi search heap is unchanged.
