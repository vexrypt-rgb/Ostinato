# Rust acceleration (ostinato-native)

Ostinato can run some of its hottest code in a small Rust library, `ostinato-native`, called through Java's
Foreign Function & Memory API (FFM). Every native piece has a pure-Java twin that gives exactly the same answer. The
Java path runs whenever the library is missing, fails to load, is switched off, or hits a case it does not handle,
so the mod never depends on the native code.

This document records why we chose what we chose, and what was measured. Speedups claimed here and in the README
are measured; anything not measured is marked as such.

## 1. Where the time goes

Measured or read from the code on main (26.3):

| Code | When it runs | Cost today (Java) | Native? |
|---|---|---|---|
| `TasBrute.search` (beam search over `PlayerSim`) | after a failed path search (`MovementTas.frontier`, up to 12 searches, 8 s budget); **on the client thread** when a head-hitter jump has no template plan (`MovementJump`, beam 2500 x 22 ticks) | 150 ms to 5.5 s per search in `TasBruteTest`; 345 ms for the executor's head-hitter lip case | **yes, first** |
| `PlayerSim.tick` | inside every search above, and in small per-node rollouts during A* (`MovementJump.partialLands`, `headHitterLands`, `MovementSlime`) | millions of ticks per brute force; tens of ticks per A* rollout | inside batched searches only |
| `JumpSearch` (two-yaw template plans) | A* costing of chain/slime jumps and the jump executor | 23 ms full, 1.9 ms local in `JumpSearchTest` | candidate, later |
| A* loop (`AStarPathFinder.calculate0`), open set, node map | every path search | dominated by `Moves.apply` cost functions | **no** (see 1.1) |
| Chunk/block lookups (`BlockStateInterface.get0`) | every cost function call | a chunk-cache hit plus a palette read | **no** (see 1.2) |

### 1.1 Why the A* loop stays in Java

A node expansion calls up to ~40 movement cost functions (`Moves.apply`). Those are 10,000+ lines of Java that read
`BlockState` objects, Minecraft tags, collision shapes, mining speeds from the player's tools, and the
`AltoClefSettings` hooks that TenorClef registers as Java lambdas (`avoidWalkThrough`, `forceWalkOn`,
`shouldAvoidBreaking`, `shouldAvoidPlacingAt`, the global heuristic). Goals are arbitrary Java subclasses
(`Goal.heuristic`, `isInGoal`), and TenorClef supplies its own.

There are two ways to move A* into Rust, and both lose:

* **Rust search, Java costs.** Every edge would need an upcall from Rust into Java. An FFM upcall costs about as much as
  the work it replaces (tens to hundreds of ns, plus argument marshalling), and the open set and node map are not
  where the time goes: the cost functions are. No speedup is possible this way.
* **Port the costs too.** That means a second copy of every movement, mirrored block properties for every block state,
  and the loss of TenorClef's Java hooks. Two copies of 10k lines would drift apart, and a cost that disagrees with
  its executor makes the bot fail jumps. Not worth it.

### 1.2 Why block lookups stay in Java

`BlockStateInterface.get0` is already a cached chunk plus a palette read. Mirroring chunks into native memory would
cost a copy on every chunk change, for code (the cost functions) that cannot use it anyway. Native code instead gets
a **snapshot of the few thousand cells a search can touch**, copied once per search (section 4).

## 2. The bridge: FFM

| | FFM (`java.lang.foreign`) | JNI (`jni` crate) | jextract |
|---|---|---|---|
| Java 25 status | final since Java 22 | supported, warns since Java 24 (JEP 472) | generates FFM bindings |
| Java glue code | none (method handles) | C ABI names tied to Java class/method names | generated sources |
| ProGuard (our optimized jar renames classes) | unaffected | must `-keep` every native class/method | unaffected |
| Call overhead | a few ns for a plain downcall | similar | same as FFM |
| Restricted-method warning | yes, once, unless `--enable-native-access` | yes, once (Java 24+) | yes |

We use **FFM with hand-written bindings**. The C ABI is a handful of functions taking pointers and counts; jextract
would add a build step and generated code for no gain. JNI would tie Rust symbol names to Java names that ProGuard
renames in our optimized jar, as `scripts/proguard.pro` already has to work around for nether-pathfinder.

Main is Java 25 only. The 1.21.x branches (Java 21, where FFM is still a preview) and 1.16.1 (Java 8) do not get the
native library. The per-version workflow now only builds 26.3.

**Call granularity.** Per-node calls are ruled out (section 1.1). Native code gets **whole searches**: one downcall per
`TasBrute.search`, with the world snapshot and parameters in off-heap memory and the result written back into
caller-provided buffers. We do not use `Linker.Option.critical`: it lets native code read Java heap arrays directly,
but it holds off garbage collection for the whole call, and a search can take tens of ms.

**Safety.**

* Every exported function wraps its body in `catch_unwind` and returns an error code; a Rust panic never crosses into
  the JVM.
* The library has no global mutable state (one thread-local flag aside), so concurrent searches on several threads
  are safe.
* A search may expand each beam layer on several threads of its own (`NativeTas.threads`, half the cores, at most 4).
  Each thread expands a contiguous slice of the layer and the children are merged back in the single-threaded order,
  so the answer does not depend on the thread count; `NativeParityTest` runs with 1 to 4.
* `ost_abi_version()` must match the Java side's expected version, or the library is not used.

## 3. Shipping and loading

Built for six targets:

| OS | Arch | Rust target | Built on |
|---|---|---|---|
| Windows | x64 | `x86_64-pc-windows-msvc` (static CRT) | `windows-latest` |
| Windows | arm64 | `aarch64-pc-windows-msvc` (static CRT) | `windows-latest` (cross) |
| Linux | x64 | `x86_64-unknown-linux-gnu` (glibc 2.17+) | `ubuntu-latest` + cargo-zigbuild |
| Linux | arm64 | `aarch64-unknown-linux-gnu` (glibc 2.17+) | `ubuntu-latest` + cargo-zigbuild |
| macOS | arm64 | `aarch64-apple-darwin` | `macos-latest` |
| macOS | x64 | `x86_64-apple-darwin` | `macos-latest` (cross) |

They go into the jar as resources at `natives/ostinato/<os>-<arch>/<lib>`, where `<os>` is
`windows`/`linux`/`macos`, `<arch>` is `x86_64`/`aarch64`, and `<lib>` is `ostinato_native.dll`,
`libostinato_native.so` or `libostinato_native.dylib`. Each is under 1 MB.

Loading happens in `baritone.natives.NativeLib`, once and lazily, on first use:

1. Skip if `-Dostinato.native=false` is set. (The `nativeAcceleration` setting, on by default, decides per search
   whether to ask the library; turning it off never loads it.)
2. Map `os.name`/`os.arch` to the resource folder; give up quietly on anything else (e.g. 32-bit, FreeBSD).
3. Extract to `<java.io.tmpdir>/ostinato-native/<sha-256 prefix>/<lib>`. A content-addressed path means two game
   instances or two Ostinato versions never overwrite a DLL that another process has locked (Windows), and an
   existing identical file is reused.
4. `SymbolLookup.libraryLookup(path, Arena.global())`, bind the downcall handles, check `ost_abi_version()`.
5. Any `Throwable` on the way disables native code for the session, with one log line saying why.

The Fabric jar loads its resources through Knot's class loader, and jar-in-jar (TenorClef bundling Ostinato) works
the same way. TenorClef compiles against the unoptimized jar; the natives are resources and do not affect its
compile classpath.

Java prints one "restricted method" warning when the library loads, unless the game is started with
`--enable-native-access=ALL-UNNAMED`. This is a warning, not an error, on Java 25.

## 4. How Rust sees the world: snapshots

`PlayerSim` asks its `World` for collision boxes in an area and for a few per-cell properties: friction, slime,
water, climbable, speed factor, jump factor, honey. A native search gets a dense snapshot of a box of cells around
the start and landing:

* per cell: an offset into one flat array of collision boxes (world coordinates, in the same order `collect` returns
  them), a flag byte (slime, water, climbable, honey), and three floats (friction, speed factor, jump factor);
* the snapshot is taken through the same `World` interface the Java search uses. `World.cellBoxes` is the single
  source of a cell's boxes, and `collect` is built from it, so the two paths cannot see different worlds.

The bounds are a guess, padded generously. Every native world access is bounds-checked; a search that would leave the
snapshot stops with `OUT_OF_REGION`, and Java retries with a larger snapshot, then falls back to the Java search.
Worlds that cannot list one cell's boxes (ad-hoc test worlds, the controllers' private worlds) always use Java.

## 5. Identical results

The Rust port is bit-for-bit with the Java code, and tests check that it is. That needed three changes to the Java
side, none of which changes what the bot does in practice:

1. **Trig.** `PlayerSim` used `Math.sin`/`Math.cos`, which HotSpot is free to compute with platform intrinsics that
   may differ in the last bit. It now uses `StrictMath`, whose results the Java spec pins to fdlibm. The Rust side
   carries a port of the same fdlibm routines.
2. **Beam ordering.** `TasBrute` kept each tick's candidates in a `HashMap` and sorted them by score; ties kept the
   map's iteration order. Ties are now broken by the state key, so the order no longer depends on hash-table layout.
3. **Java arithmetic quirks** are reproduced in Rust rather than changed: `Math.round` for doubles and floats,
   float-precision steps (`slip^3`, speed factors), the `0.3f`/`1.8f` box size, `Math.min`/`max` on signed zero,
   saturating `(int)` casts, and wrapping `long` arithmetic in the state key. (The distance field is a Dijkstra
   over a grid; the order in which equal distances leave the queue cannot change the distances, so Rust's
   `BinaryHeap` stands in for `java.util.PriorityQueue`.)

`NativeParityTest` runs every `TasBruteTest` course and a set of randomized worlds both ways and requires the same
plan (every tick's yaw, input and jump), the same end state to the bit, or the same "no plan".

## 6. Java fallback

`TasBrute.search` asks `NativeTas.search` first. It returns the native result, or `null` meaning "not handled":
library unavailable, unsupported world, out of region twice, or any error. On `null` the original Java search runs.
The Java code is the reference implementation and stays the default everywhere native code is not proven faster.

## 7. Builds and CI

* `native/` holds the Cargo crate. Gradle's `nativeHost` task runs `cargo build --release` when `cargo` is on the
  path and puts the host library under `build/generated/natives`, which is a resource folder of the main source set.
  Without cargo the build still works and the jar has no natives (Java fallback).
* `-Postinato_natives_dir=<dir>` makes Gradle package prebuilt libraries from `<dir>/<os>-<arch>/` instead, which is
  how CI ships all six.
* `release.yml`: a `natives` job builds the six libraries on Linux, Windows and macOS runners and uploads them; the
  26.3 build job downloads them and passes `-Postinato_natives_dir`. The other branches ignore them.
* `-Postinato_native_require` makes the parity tests fail instead of skip when the library does not load; the
  release's 26.3 build passes it, so every release proves parity on the Linux x64 library it ships. The release
  also checks that each 26.3 jar holds all six libraries.
* Any other build on a machine with cargo (the preinstalled Rust on GitHub's Ubuntu runners included) builds the host
  library and runs the parity tests against it.

## 8. Measurements

See section 9 for the numbers; the method is `NativeBench`, a JUnit class that runs only with `-Postinato_bench`:

```
./gradlew test -Postinato_native_require -Postinato_bench --tests "*NativeBench"
```

Each course is searched by Java, by Rust on one thread and by Rust on `NativeTas.threads` threads; after two
warm-ups the median of five runs is reported, and every Rust answer is checked against the Java one. Pieces that
are not measurably faster are not shipped.

## 9. Results

`TasBrute.search` is the one piece shipped native. Measured with `NativeBench` on an Intel Core i5-1145G7 (4 cores,
8 threads, Windows 11, JDK 25.0.4), median of 5 runs after 2 warm-ups. Rust times include taking the snapshot and
the FFM call. Every Rust plan matched the Java plan bit for bit.

| Course (beam x ticks) | Java ms | Rust, 1 thread | Rust, 4 threads | Speedup (4 threads) |
|---|---:|---:|---:|---:|
| gap3 (3000 x 45) | 699 | 331 | 179 | 3.9x |
| neo2 aligned (3000 x 45) | 605 | 249 | 198 | 3.1x |
| neo3 aligned, no plan (3000 x 45) | 1499 | 575 | 421 | 3.6x |
| neo3 momentum, no plan (8000 x 36) | 6496 | 2277 | 1337 | 4.9x |
| course neo3 east edge (6000 x 48) | 2592 | 915 | 604 | 4.3x |
| head-hitter lip, executor (2500 x 22) | 472 | 194 | 103 | 4.6x |
| candle to candle (3000 x 45) | 275 | 119 | 81 | 3.4x |

On one thread Rust is 2.3x to 2.9x faster than Java. The rest comes from expanding each beam layer on 4 threads,
which gains 1.3x to 1.9x more; small layers (under 48 nodes per thread) stay on one thread. Timings vary between runs
by up to about 40% on this laptop (thermal limits), so compare the columns of one row, which were measured together.

Where the single-thread gain comes from: a simulated tick costs about 75 ns in Rust and about 200 ns in Java. The
snapshot is flat arrays, so collision lookups do not go through `HashMap`s, `List<double[]>` allocations or
`BlockState` objects. Choosing each layer's best candidates by selection (`select_nth_unstable`) instead of sorting
all of them added about 10%.

Not native, and why:

* `JumpSearch` takes 2 to 23 ms per search, so taking a snapshot would eat much of any gain. Not attempted; no claim
  either way.
* The A* loop and block lookups: see sections 1.1 and 1.2.
