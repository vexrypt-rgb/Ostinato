# Mining, building and farming

## Mining
- `#mine diamond_ore` mines every reachable block of that kind it can find; give several blocks to mine all.
- `legitMine` settings limit mining to what is plausible (see `#set l legitMine`).
- `#tunnel` digs straight ahead (1x2); `#tunnel <height> <width> <depth>` sets the size.
- `#find <block>` searches the cache only, so only previously seen blocks are found.
- `#blacklist` drops the block you were heading to if it is unreachable.

## Farming and pickup
`#farm` harvests mature crops and replants; `#farm <range> [waypoint]` limits it. `#pickup [items]` collects drops.

## Building
`#build <file> [x y z]` loads a schematic from the `schematics` folder at your position or the given origin
(accepted extensions depend on the version; the built-in help mentions `.schematic`). Blocks already correct are
skipped, so it is safe to re-run. `#litematica` and `#schematica` build what is open in those mods. Useful
settings: `buildSchematicRotation`, `buildSchematicMirror`, `buildSubstitutes`. To split a build over bots see
[Swarm and region builds](swarm-and-region-builds.md).

## Selections (`#sel`)
Set corners with `sel pos1` / `sel pos2`, then `sel set <block>`, `walls`, `shell`, `sphere`, `hsphere`,
`cylinder <axis>`, `hcylinder`, `cleararea`, `replace <blocks> <with>`, `expand`/`contract`/`shift`, `undo`, `clear`.
These change blocks by mining and placing.

## Waypoints
`#wp save [tag] [name] [pos]`, `#wp list [tag]`, `#wp info|delete|goal|goto <tag/name>`, `#wp restore <n>`,
`#wp clear <tag>`.

## Exploring
`#explore [x z]` roams outward; `#explorefilter <json> [invert]` tells it which chunks are done. The JSON
format is `[{"x":0,"z":0},...]`.
