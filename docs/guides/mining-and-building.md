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

## Nether portal at a lava pool (`#portal`)
`#portal build` makes a nether portal out of a lava pool the way speedrunners do, without mining obsidian. It
needs a water bucket, an empty bucket, flint and steel and a few solid blocks in the hotbar or inventory; a
pickaxe is needed only where the pool is one block deep, and saves about ten seconds everywhere else.

| Command | Effect |
| --- | --- |
| `#portal build` | walk to the best shore within 24 blocks, build and light the portal, refill the bucket with lava |
| `#portal build enter` | the same, then walk into the portal |
| `#portal find` | list the shores the portal fits on, best first, without building |
| `#portal status` | the current step, or how the last build ended |
| `#portal cancel` | stop |

A shore fits when four lava sources lie in a row against solid ground with room above for the frame. The
process (`PortalBuildProcess`) clears what is in the way, sets the base row with water, raises the two
shapes on the shore, hangs the water from the overhang and casts the frame block by block with the bucket.
Each step waits for the blocks it expects; cobblestone where obsidian should be is dug out and cast again. A
build takes about 20 seconds with a pickaxe and about 30 without. A pool smaller than the frame, or a
one-deep pool with no pickaxe, is refused with a message.

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
