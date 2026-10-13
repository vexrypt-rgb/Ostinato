# Combat, clutch and survival

## `#pvp`
| Command | Effect |
| --- | --- |
| `#pvp <name>` | fight that player or entity |
| `#pvp players` | fight any nearby player |
| `#pvp stats` | show combat statistics |
| `#pvp enemies` | list marked enemies |
| `#pvp clear` | clear marked enemies and stop |

The fight process does the following (from `PvpProcess`): critical hits, W-taps, hit select, jump resets,
strafing, axe against shields, shield against bows, a bow at range, golden apples, and an offhand totem.
Enemies can also be marked with the middle mouse button in [freecam](gui-freecam-tasks.md). TenorClef's
`@pvp` runs this same process.

## `#pve` (mobs)
Mobs are fought by their own process, `PveProcess`, not the duel logic used for players. `#pvp hostiles` now
just starts it.
| Command | Effect |
| --- | --- |
| `#pve` / `#pve hostiles` | fight every hostile mob in range |
| `#pve <mob id>` | fight one kind, e.g. `#pve zombie` |
| `#pve stats` | kills, hits, damage taken, shield blocks, meals |
| `#pve clear` | stop |

Each mob is classified from its vanilla class (`MobProfile`): melee mobs are hit on a full charge with a short
back-off, creepers are kept at distance and avoided while fusing, shooters (skeletons, strays, pillagers,
drowned, blazes, ghasts) are charged at sprint speed with a weave, and heavy hitters are met with the shield.
It will not eat in the open while something is shooting at it, gives up on mobs it cannot reach (burrowed
silverfish), and runs from wardens, ravagers, guardians, evokers and the bosses.

## Survival layer (`CombatSurvival`)
While fighting, the bot will: pop a totem, eat a golden apple, eat other food when it is safe to, and flee or
throw an ender pearl when health is low. Eating in combat was reworked in the latest release; if the bot stops to
eat at a bad moment, report it with the fight log.

## Water and ladder clutch
- Water bucket clutch: stock behaviour, needs a water bucket.
- Ladder clutch (`allowLadderClutch`, default off): during a long fall, place a ladder or vine on a wall
  beside the last blocks. Needs a ladder or vine on the hotbar. The controller keeps a sticky aim at the wall
  and a carrot ahead of the route so it does not wobble.
- Ladder pillar: climbing with ladders re-aims only after the bot drifts off the column.

## Limits
Combat tuning was validated mostly against one test opponent; other opponents can behave differently. TenorClef's
[combat guide](https://github.com/vexrypt-rgb/TenorClef/blob/main/docs/guides/combat-and-survival.md)
covers mob defence settings.
