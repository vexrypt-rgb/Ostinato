# Per-version overlays

**Not in use.** Each Minecraft version is built from its own branch (see the
[compatibility table](../README.md#compatibility)). The overlays here were written
against a 1.21.4 tree and have not kept up with the branches: applying one does not
give a tree that builds, and the CI job that tried (`versions.yml`) was removed for
that reason. What follows describes the scheme as it was designed.

A shared tree holds the 1.21.4 source. Other Minecraft versions are built from the same
tree by applying an overlay:

    scripts/use-version.sh 1.21.11
    ./gradlew build

Each `versions/<ver>/` may contain:

- `version.properties` – Gradle property overrides (`minecraft_version`, `java_version`, ...) and an optional `parent=<ver>`; parents are applied first.
- `files/` – files that replace or add to the shared tree at the same relative path.
- `delete.txt` – shared files that do not exist on that version.

The script edits the working tree in place; run `git checkout -- . && git clean -fd src` to go back to 1.21.4.
