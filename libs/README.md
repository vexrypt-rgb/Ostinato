# libs

`conductor-contract-0.5.0.jar`: the contract of [Conductor](https://github.com/vexrypt-rgb/Conductor)
0.5.0 (pure Java 21, no dependencies), built from Conductor's `contract/` project. It is a **compile-only**
dependency for `baritone.conductor.OstinatoModule` on the `conductor-module` branch: Conductor provides these
classes at runtime, and without Conductor installed they are never loaded.

SHA-256: `4c582ceb067af699a0e29beb0ebda7188f181cf54156c4402cef5f2f3760bfae`

To update: build Conductor (`./gradlew :contract:jar`), copy `contract/build/libs/contract-<v>.jar` here, and
change the file name in `build.gradle`.
