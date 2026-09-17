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
