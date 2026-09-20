# TenorClef architecture docs

TenorClef (AltoClef fork) is the high-level agent that consumes this Ostinato `1.16.1` lineage as its legacy movement jar.

See TenorClef:

- https://github.com/vexrypt-rgb/TenorClef/blob/main/docs/OSTINATO_WIRING.md
- https://github.com/vexrypt-rgb/TenorClef/blob/main/docs/DEVELOPMENT.md

## Build requirement (Phase 1)

This branch uses **Gradle 4.9** and must be built with **JDK 8**.

```bat
set JAVA_HOME=<JDK8 home>
gradlew.bat build -Pbaritone.fabric_build
```

Copy `dist/baritone-unoptimized-fabric-*.jar` to TenorClef's sibling `../Ostinato/dist/` (or `altoclef/libs/`) as `baritone-unoptimized-fabric-ostinato-1.16.1.jar`.

Do **not** use JDK 21 for this checkout. Tungsten is not part of the 1.16.1 lineage.

## MovementEngine (Phase 2)
Tip Ostinato owns MovementEngine. This 1.16.1 line stays Baritone-only — see `docs/MOVEMENT_ENGINE_1161.md`.
