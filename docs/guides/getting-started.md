# Getting started

## Which version
Ostinato keeps one branch per Minecraft version (see the compatibility table in the [README](../../README.md#compatibility)):
`main` (1.21.11), `1.21.4`, `1.16.1`, `26.3`. Releases publish a jar per version. Pick the one
that matches your game; a jar for one Minecraft version will not load on another. TenorClef builds must be
paired with the matching Ostinato jar ([TenorClef's wiring guide](https://github.com/vexrypt-rgb/TenorClef/blob/main/docs/OSTINATO_WIRING.md)).

Several features in these guides (freecam, `#tasks`, the Ostinato screen, `#pvp`, combat survival) are on the
modern lines. Run `#help` in your build to see which commands it really has.

## Using it
Chat commands start with `#` (setting `prefix`). `#help` lists every command, clickable, with tab completion.

```
#goto 100 64 -200
#mine diamond_ore
#set kinematicTravel true
#stop
```
- `#stop` / `#cancel` ends the current process; `#forcecancel` is the forceful version.
- Type a setting name to read or toggle it; see [Settings](settings.md).

## With TenorClef
TenorClef decides what to get or do; Ostinato does the walking, mining and fighting. Install the Ostinato jar
that matches your TenorClef build and do not add a second Baritone jar. TenorClef's own guides:
<https://github.com/vexrypt-rgb/TenorClef/blob/main/docs/guides/README.md>.

## Building from source
`gradlew build` (Java 21 on the modern lines; Java 8 and Gradle 4.9 for 1.16.1). Outputs land in `dist/`.
See the [README](../../README.md#build).
