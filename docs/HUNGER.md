# Hunger planning (Ostinato)

Branch `hunger-plan`. Code in `baritone.utils.hunger`, `behavior/HungerBehavior`, `utils/PassableBlocks`.

## Vanilla 26.3 numbers (decompiled FoodData, FoodConstants, ServerPlayer, Player, Block, Foods, Consumables)
- Exhaustion: sprint 0.1/m (ground only), walk/crouch 0, swim / under water / on water 0.01/m, jump 0.05,
  sprint-jump 0.2, block mined 0.005, attack that hits 0.1 (misses 0), damage = damage type's exhaustion
  (0.1 mobs/players/arrows/lava/in_fire/hot_floor/cactus/explosion; 0 fall/on_fire/starve/drown). Hunger effect 0.005*(amp+1)/tick.
- Exhaustion caps at 40; each tick above 4 (strict) takes 4 off and 1 saturation, or 1 food once saturation is 0 (not peaceful).
- Saturation-fast regen: sat > 0, hurt, food == 20: every 10 ticks heal min(sat,6)/6, add that as exhaustion (1 hp / 6 exh).
- Normal regen: food >= 18, hurt: every 80 ticks 1 hp, 6 exhaustion.
- Starvation: food 0, every 80 ticks 1 damage while hp > 10 easy, > 1 normal, always hard.
- Sprinting needs food > 6. Spawn: 20 food, 5 saturation -> 76 exhaustion = 760 m of sprinting before it stops.
- Eat 1.6 s = 32 ticks; dried kelp 0.8 s = 16; honey bottle 2 s = 40. Saturation = nutrition * modifier * 2, capped at food.
- Golden apple 4 / 9.6, Regeneration II 5 s (4 hp) + Absorption I; enchanted 4 / 9.6, Regen II 20 s (16 hp),
  Absorption IV, Resistance + Fire Resistance 5 min. Full table: `VanillaFoods`.

## Settings
`hungerPlanning`, `healthFloor` (8 = 4 hearts, also the bastion DamageBudget floor; -Dostinato.bastion.hpfloor still wins),
`hungerExposureWeight`, `autoEat`, `foodSavingMode` (manual), `autoFoodSaving`, `foodSavingPenalty`.

## Drills (not run yet)
1. Sprint run: flat 1000 block line, start 20 food / 5 sat, `#goto` 900 blocks east with autoEat off. Log real food every
   20 ticks vs `HungerBehavior.prediction()`; expect sprint to stop near 760 m (~2710 ticks).
2. Drop-and-eat vs safe: 9 block cliff with a 40-block staircase round. experimentalMovement on, steak in hotbar,
   20 food: planner should take the drop and eat at the next pause; with no food it should take the stairs. Time both.
3. Pause eating: food 12, steak on the hotbar, `#goto` far target: eats during the first path search, on ladders, in long falls.
4. Food-saving: food 10, no food, 600 block goal: chat logs "Food-saving mode on", no sprint, fewer jumps; give 3 steak,
   eat to 20: mode off, sprinting resumes. `foodSavingMode true` forces it.
5. Passable blocks: path across a tall-grass/flower/torch/rail field (no breaking, no slowdown); fight a zombie standing
   in tall grass (aims around the grass, breaks it only if no angle); pillar-place with grass on the target face.
