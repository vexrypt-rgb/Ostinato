# Swim / water bobbing port

## Sources
- cabaletta/baritone#2377 — pathfinding in water can get stuck bobbing up/down
- cabaletta/baritone#3988 — sprint-swim via pitch ≈ -30; skip unconditional JUMP on `MovementTraverse` when `swimInWater` is on; same pitch/sprint for `MovementDiagonal`

## What landed (branch `1.16.1`)
- `Settings.swimInWater` (default true)
- `Movement.update`: do not always hold JUMP in liquid; skip JUMP override for traverse when swimming
- `MovementTraverse`: detect water→water swim, clear thrash JUMP, force pitch -30
- `MovementDiagonal`: when `isSwimming()`, sprint + pitch -30

## Build / wire into TenorClef
```bat
cd C:\Users\redfa\Documents\MinecraftDev\Ostinato-1.16.1
git pull
gradlew.bat build
copy /Y dist\baritone-unoptimized-fabric-ostinato-1.16.1.jar ..\altoclef\libs\
cd ..\altoclef
gradlew.bat :1.16.1:compileJava
```

## Real sprint-swim controller (main)
The port above never actually entered the swim pose. Vanilla only starts swimming when the player
sprints with its eyes under water; `Movement.update` held JUMP in liquid (head stays above the
surface), and the fixed pitch -30 could only rise. `Movement.applySwim` now runs for every movement
type in water two or more blocks deep:

| State | Pitch | Inputs |
|---|---|---|
| not swimming yet | +35 (dip eyes under) | sprint, forward, no jump |
| swimming, target above / air < 1/3 | -35 (rise) | sprint |
| swimming, target below | +30 (dive) | sprint |
| swimming, level | -8 (cruise under surface) | sprint |

Climbing out onto a bank is left to the normal JUMP path. Needs food > 6 (vanilla sprint rule).
Port to the 1.16.1 branch: same method, 1.16 Yarn names (`isTouchingWater`, `getAir`, `getMaxAir`,
`hasVehicle`).
