# Per-version overlays

`main` holds the 26.3 source (official names, Java 25). The 1.21.11 and 1.21.4
lines are no longer overlays on this tree; they live on their own `1.21.11` and
`1.21.4` branches with their own CI.

A future Minecraft version can still be built from the same tree by adding an
overlay and applying it:

    scripts/use-version.sh <ver>
    ./gradlew build

Each `versions/<ver>/` may contain:

- `version.properties` – Gradle property overrides (`minecraft_version`, `java_version`, ...) and an optional `parent=<ver>`; parents are applied first.
- `files/` – files that replace or add to the shared tree at the same relative path.
- `delete.txt` – shared files that do not exist on that version.

Keep overlays small and refresh them whenever the shared files they replace change:
an overlay copy is a snapshot, and a stale one silently rolls the shared file back.

The script edits the working tree in place; run `git checkout -- . && git clean -fd src` to go back to 26.3.
