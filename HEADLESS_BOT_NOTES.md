# Ostinato — fully headless bot, survey notes (Phase 0)

Owner: vex rypt. Built by Hy4; Claude reviews/verifies. All times America/Phoenix (MST).
Worktree: `C:\Users\redfa\Documents\MinecraftDev\Ostinato-headless-bot`, branch `headless-bot/main`
from `origin/main` = `fe2021479` (2026-11-13 05:44 MST). Logs: `C:\Users\redfa\Documents\MinecraftDev\headless-bot-logs\`.
Written 2026-11-14 ~08:20 MST.

## 0. What this is, and what it is not

Design doc `SWARM_DESIGN_DOC_v3.tsx` lines 340-400 (option cards) and 600-640 (bullet lists) were read.
This job is **Option 2: "1 JVM, N connections"** — a lightweight Java process that speaks the 26.3
protocol directly, keeps its own chunk/entity state, moves with `PlayerSim`, plans with Ostinato's A*.

It is explicitly **not**:
- `docs/headless-mode.md`'s `runMode headless`, which still boots the whole 26.3 client JVM and only
  stubs GPU/window/render-thread/sound. That doc says so itself: "The client tick still runs. `runTick`
  is not skipped." It is a real client with no screen, measured at ~1.6 GB on 2026-11-10.
- Option 3 (Rust client / Azalea). Nothing in `native/src/sim.rs` is being turned into a client.

Option 2's own cons in the design doc: static `Baritone.settings()`, "49 main files call
`Minecraft.getInstance()` (157 call sites), plus about 100 in TenorClef", and "Needs a Java protocol
client for 26.3 that does not exist in the repo". Section 6 below re-measures both numbers on this
exact revision.

### Ground truth used for the protocol survey

The supplied `C:\Users\redfa\Documents\MinecraftDev\_mc263src` **does not contain any network,
protocol, packet or `ServerGamePacketListenerImpl` code**. It holds 50 decompiled `.java` files and
1,214 `.class` files, all physics/block/voxel/entity related (`LocalPlayer`, `Entity`,
`LivingEntity`, `Block`, `Blocks`, `Shapes`, `VoxelShape`, `AABB`, `WebBlock`, `PowderSnowBlock`,
`HoneyBlock`, ...). Verified with `find _mc263src -type f | grep -iE 'packet|listener|protocol'` → 0 hits.
It is still the right source for the physics gaps (section 5).

For packet order, codecs and the server's movement checks the ground truth is the **Mojmap-remapped
26.3 merged jar produced by Unimined**:

```
C:\Users\redfa\.gradle\caches\unimined\net\minecraft\minecraft\26.3\
    minecraft-26.3-fabric-merged+fixed-official.jar   (38,012,119 bytes, 32,874 entries)
```

All claims below come from `javap -c -p -constants` on that jar. Dumps are kept as evidence in
`headless-bot-logs\phase0\javap\`:

| file | contents |
| --- | --- |
| `signatures.txt` | 70 packet/listener class signatures |
| `listener-interfaces.txt` | `ClientGamePacketListener` etc., method lists |
| `server-game-listener-bytecode.txt` | 7,096 lines, full `ServerGamePacketListenerImpl` |
| `server-common-listener-bytecode.txt` | keep-alive / timeout |
| `login-config-sequence.txt` | client handshake + config, server login + config |
| `protocol-registration.txt` | `GameProtocols` / `ConfigurationProtocols` / `LoginProtocols` |
| `localplayer-bytecode.txt` | `LocalPlayer.sendPosition()` 20-tick reminder |
| `client-tick-packets.txt` | `ClientPacketListener` |
| `bootstrap-apis.txt` | common-side API signatures |

Protocol version: `SharedConstants.RELEASE_NETWORK_PROTOCOL_VERSION = 777`,
`WORLD_VERSION = 5023` (asserted at runtime by the probe).

## 1. Gate 0a — Bootstrap / registries / BlockState / VoxelShape in a plain JVM

**RESULT: PASS.** `src/test/java/baritone/headless/HeadlessBootstrapProbeTest.java`, 8 tests,
0 failures, 0 skipped, ~6.1 s. Run (2026-11-14 08:17 MST):

```
./gradlew test --offline --rerun --tests 'baritone.headless.HeadlessBootstrapProbeTest' \
    -Dorg.gradle.jvmargs=-Xmx1g --max-workers=2
```

Evidence: `headless-bot-logs\phase0\bootstrap-probe.log`,
`build\test-results\test\TEST-baritone.headless.HeadlessBootstrapProbeTest.xml`.

The test JVM is a bare Gradle test worker: no `Minecraft` main, no GL/window, no client tick. Recorded:

```
[HeadlessBootstrapProbe] SUPPRESSES_BOUNCE tag bound after Bootstrap = false
[HeadlessBootstrapProbe] fabricLoaderOnClasspath=false clientMainOnClasspath=true
```

What loads and works with nothing but `SharedConstants.tryDetectVersion()` + `Bootstrap.bootStrap()`:

- `BuiltInRegistries.BLOCK` (>100 entries), `BuiltInRegistries.ITEM` (>100),
  `Block.BLOCK_STATE_REGISTRY` (>1000 states), `byId`/`getId` round trip (the chunk palette path).
- `BlockState` (`Blocks.STONE.defaultBlockState()`, `isAir()`), `Shapes.block()`,
  `Shapes.create(...)`, `VoxelShape.max(Axis)` / `min(Axis)` — i.e. `BsiWorld`'s collision shapes.
- `FriendlyByteBuf` over Netty `Unpooled.buffer()` (`writeVarInt`/`readVarInt`,
  `writeIdentifier`/`readIdentifier`), `RegistryFriendlyByteBuf.decorator(...)`.
- Every protocol template: `HandshakeProtocols.SERVERBOUND`, `LoginProtocols.CLIENTBOUND/SERVERBOUND`,
  `ConfigurationProtocols.CLIENTBOUND/SERVERBOUND`, `GameProtocols.CLIENTBOUND_TEMPLATE` /
  `SERVERBOUND_TEMPLATE`; the `STREAM_CODEC`s of `ClientboundLoginPacket`,
  `ClientboundLevelChunkWithLightPacket`, `ServerboundMovePlayerPacket.Pos`.
- `RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)` and
  `GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(access))` → a working
  PLAY inbound `ProtocolInfo`. `new Connection(PacketFlow.CLIENTBOUND)` constructs.
- `PlayerSim` driven by a hand-written `PlayerSim.World` (pure Java, no client); `BsiWorld` loads.

**What Bootstrap does not do (important for Phase 1/3):** it does **not** bind datapack tags.
`BuiltInRegistries.BLOCK.getTagOrEmpty(BlockTags.SUPPRESSES_BOUNCE)` is empty after `bootStrap()`.
`BsiWorld.bounce()` uses `state.is(BlockTags.SUPPRESSES_BOUNCE)` and `MovementHelper` uses many block
tags, so a bot that skips configuration tag application silently gets wrong physics/planner results.
The server sends tags as `ClientboundUpdateTagsPacket` during configuration; the common-side
replacement for the client-only `RegistryDataCollector` is:

```
TagNetworkSerialization.NetworkPayload.resolve(Registry)  -> TagLoader.LoadResult
Registry.prepareTagReload(LoadResult)                     -> Registry.PendingTags
Registry.PendingTags.apply()
RegistrySynchronization.isNetworkable(ResourceKey)       -> filter
```

(verified from `RegistryDataCollector` bytecode, `login-config-sequence.txt` lines ~692-721 and
`RegistryDataCollector.resolveRegistryTags` / `loadOnlyTags`). Registry *contents* from
`ClientboundRegistryDataPacket.entries()` (`RegistrySynchronization.PackedRegistryEntry`) must be
handled too when the server does not accept our known-packs list; otherwise fall back to the built-in
registries, which for a vanilla server are identical.

**Deviation from the recommended "common classes only" classpath (written reason, as required):**
Unimined gives this project one merged client+server Mojmap jar. `src/main` and `src/api` themselves
declare `net.minecraft.client.*` types (`Minecraft`, `LocalPlayer`, `ClientLevel`, `ClientChunkCache`,
`MultiPlayerGameMode` — see section 6), so any source set depending on `main.output` must have that
jar on its classpath; a strict server-only jar (`minecraft-26.3-server.jar`) would fail to link
Ostinato's own classes. The headless *code* will import nothing from `net.minecraft.client.*` and will
never boot a client object. The probe confirms client bytecode is merely present, not initialised
(`clientMainOnClasspath=true` but no game thread, no Fabric loader). Splitting the build into a
common-only module is deferred; it would require first cutting the client types out of `IPlayerContext`
(section 7), which is Phase 3 work.

### Baseline `gradlew test` is red on untouched `origin/main`

First full-suite run (2026-11-14 00:59-01:08 MST, `headless-bot-logs\phase0\baseline-test.log`;
all times below are MST = `US Mountain Standard Time`, UTC-7, no DST, read from Windows — Git Bash
`TZ=America/Phoenix date` silently falls back to UTC on this machine and must not be used):

```
LongRouteSimTest > tripsReachTheGoalOnBothArms FAILED
    java.lang.AssertionError: fallback:corridor search missed the goal (LongRouteSimTest.java:104)
2033 tests completed, 1 failed, 24 skipped
```

Running that class alone passes (`longroute-isolated.log`, 32 s, BUILD SUCCESSFUL).
`HierarchicalPathFinder.calculate0` uses `System.currentTimeMillis()` deadlines
(`t0 + failureTimeout/4`, `/2`, `Math.max(1, primaryTimeout - used)`), so on a busy machine the
corridor search times out. This is a **pre-existing wall-clock flake on `origin/main`, not caused by
the headless work**. It matters because the hard rules demand a green `gradlew test` at every commit.

Root cause (second finding): `Baritone.settings()` is one static singleton shared by the whole suite,
and `LongRouteSimTest.defaults()` restored only a hand-picked 19 settings before each test. Other
classes leave planner settings modified (`fastMode`, `piCheapPathing`, `legato`, `allowBreak`,
`pathTieBreak`, `simEdgeHorizon`, `maestroMaxStates`, ...), so a corridor search could start from a
foreign configuration and miss the goal regardless of the wall clock. The class was therefore
order-dependent, not just load-dependent.

Fix (`// Ostinato:` in `LongRouteSimTest.defaults()`): restore **every declared default** via
`s.allSettings`, then re-apply the three `PathBench.boot()` overrides for headless synthetic worlds
(`considerPotionEffects=false`, `chatDebug=false`, `desktopNotifications=false`). This is test
isolation only — no assertion, budget or timeout in any existing test was weakened, deleted or
lengthened.

Verification (2026-11-14 01:34-01:43 MST, `headless-bot-logs\phase0\full-test-after-fix.log`):
```
./gradlew test --offline --rerun -Dorg.gradle.jvmargs=-Xmx1g --max-workers=2
BUILD SUCCESSFUL in 9m 23s   FULL_TEST_EXIT=0
2041 tests, 0 failures, 0 errors, 24 skipped (306 suites, aggregated from JUnit XML)
```
2041 = the original 2033 plus the 8 new Bootstrap probe tests. Both the wall-clock sensitivity and
the settings pollution are reported here rather than hidden; the fix generalises (it restores the
declared defaults of the shared singleton, it is not tuned to one test course).

## 2. Exact login -> configuration -> play packet sequence for 26.3

All names are Mojang-mapped 26.3. `ConnectionProtocol` values: `HANDSHAKING`, `LOGIN`,
`CONFIGURATION`, `PLAY`, `STATUS`. Codecs are the packets' own `STREAM_CODEC`; no hand-rolled protocol.

**Connect.** `Connection conn = new Connection(PacketFlow.CLIENTBOUND)` then
`Connection.connect(new InetSocketAddress(...), EventLoopGroupHolder.remote(false), conn)`
(`net.minecraft.server.network.EventLoopGroupHolder.remote(boolean)` is a public static factory —
no `MinecraftServer` needed; `Connection.connect` returns a `ChannelFuture`).
Alternatively `initiateServerboundPlayConnection(host, port, ClientLoginPacketListener)`.
No Microsoft auth / encryption because the live server is `online-mode=false`: in
`ServerLoginPacketListenerImpl.handleHello`, `usesAuthentication()==false` skips
`ClientboundHelloPacket` entirely and calls `startClientVerification(createOfflineProfile(name))`.

**Verified fully-qualified names** (`javap -public` against the 26.3 Mojmap merged jar, evidence
`headless-bot-logs\phase1\api-signatures.txt`). Several of these moved relative to older versions;
using the wrong package is the most likely first compile failure in Phase 1.

| short name used below | actual 26.3 FQN |
| --- | --- |
| `EventLoopGroupHolder` | `net.minecraft.server.network.EventLoopGroupHolder` (**not** `net.minecraft.network.*`) |
| `ClientInformation` | `net.minecraft.server.level.ClientInformation` (**not** `net.minecraft.client.multiplayer.*`) |
| `ServerboundClientInformationPacket` | `net.minecraft.network.protocol.common.ServerboundClientInformationPacket` |
| `ClientboundUpdateTagsPacket` | `net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket` (**common**, not configuration) |
| `ClientboundKeepAlivePacket` / `ServerboundKeepAlivePacket` / `ServerboundPongPacket` / `ClientboundResourcePackPushPacket` | `net.minecraft.network.protocol.common.*` |
| `ClientboundSelectKnownPacks` / `ServerboundSelectKnownPacks` | `net.minecraft.network.protocol.configuration.*`, **no `Packet` suffix** |
| `ClientboundRegistryDataPacket` | `net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket` |
| `ClientboundCodeOfConductPacket` / `ServerboundAcceptCodeOfConductPacket` | `net.minecraft.network.protocol.configuration.*` |
| `ClientboundCookieRequestPacket` / `ServerboundCookieResponsePacket` | `net.minecraft.network.protocol.cookie.*` |
| `ServerboundConfigurationAcknowledgedPacket` | `net.minecraft.network.protocol.game.*` (**game**, not configuration) |
| `ServerboundMovePlayerPacket$PosRot` etc. | `net.minecraft.network.protocol.game.ServerboundMovePlayerPacket$*` |
| `TagNetworkSerialization$NetworkPayload` | `net.minecraft.tags.TagNetworkSerialization$NetworkPayload`; `resolve(Registry<T>)` returns `TagLoader.LoadResult<T>` |
| `GameProtocols.SERVERBOUND_TEMPLATE` | `UnboundProtocol<ServerGamePacketListener, RegistryFriendlyByteBuf, GameProtocols.Context>` — `.bind(decorator, Context)` needs the extra `Context` argument; `CLIENTBOUND_TEMPLATE` is a `SimpleUnboundProtocol` (`.bind(decorator)` only) |
| `ConfigurationProtocols.SERVERBOUND` / `CLIENTBOUND` | already-bound `ProtocolInfo<...>` over `FriendlyByteBuf`; pass directly to `setupInboundProtocol` / `setupOutboundProtocol` |
| `PalettedContainer` | `net.minecraft.world.level.chunk.PalettedContainer`; `read(FriendlyByteBuf)` decodes the wire palette in place |

| # | direction | packet | rule |
| --- | --- | --- | --- |
| 1 | C->S | `ClientIntentionPacket(777, host, port, ClientIntent.LOGIN)` | handshake; `HandshakeProtocols.SERVERBOUND`; `MAX_HOST_LENGTH=255` |
| 2 | S->C | `ClientboundLoginCompressionPacket(threshold)` | optional; server sends it when `network-compression-threshold >= 0` (default 256) from `verifyLoginAndFinishConnectionSetup`, *before* LoginFinished. Client must call `Connection.setupCompression(threshold, true)`; every later frame is compressed |
| 3 | C->S | `ServerboundHelloPacket(name, UUID)` | offline UUID (`UUIDUtil.createOfflineProfile`-compatible); sent immediately after handshake, before compression switch |
| 4 | S->C | `ClientboundLoginDisconnectPacket` / `ClientboundCustomQueryPacket` | handle/ignore; `ServerboundCustomQueryAnswerPacket(transactionId, payload)` if a query arrives |
| 5 | S->C | `ClientboundLoginFinishedPacket(gameProfile, sessionId)` | terminal for LOGIN |
| 6 | C->S | `ServerboundLoginAcknowledgedPacket.INSTANCE` | **must be sent before switching the outbound protocol**. Client does: `setupInboundProtocol(ConfigurationProtocols.CLIENTBOUND, configListener)` -> `send(LoginAcknowledged)` -> `setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND)`. Server's `handleLoginAcknowledgement` requires state `PROTOCOL_SWITCHING` and then switches *its* outbound to `ConfigurationProtocols.CLIENTBOUND` and calls `startConfiguration()` |
| 7 | C->S | `ServerboundCustomPayloadPacket(BrandPayload("vanilla"))` | sent right after the ack; `BrandPayload` is in `net.minecraft.network.protocol.common.custom` |
| 8 | C->S | `ServerboundClientInformationPacket(ClientInformation)` | `ClientInformation.createDefault()` is enough; record = language, viewDistance, chatVisibility, chatColors, modelCustomisation, mainHand, textFilteringEnabled, allowsListing, particleStatus. Also sent again on any change |
| 9 | S->C (config) | `ClientboundRegistryDataPacket(registryKey, entries)` x N | decode into `RegistryAccess`; see section 1 |
| 10 | S->C (config) | `ClientboundUpdateTagsPacket(tags)` | apply with `TagNetworkSerialization`; **required** for `BsiWorld`/`MovementHelper` tag checks |
| 11 | S->C (config) | `ClientboundUpdateEnabledFeaturesPacket(features)` | `FeatureFlags.REGISTRY.fromNames(...)`; store, harmless to ignore for pathing |
| 12 | S->C (config) | `ClientboundSelectKnownPacks(knownPacks)` | reply `ServerboundSelectKnownPacks(selected)`; sending an empty list forces the server to send full registry data |
| 13 | S->C (config) | `ClientboundCodeOfConductPacket` | if `server.properties` enables it; reply `ServerboundAcceptCodeOfConductPacket.INSTANCE` |
| 14 | S->C (config) | `ClientboundResourcePackPushPacket` | reply `ServerboundResourcePackPacket(id, Action)`; required/optional as signalled. Our server will not push packs |
| 15 | S->C (config) | `ClientboundCookieRequestPacket` / `ClientboundStoreCookiePacket` / `ClientboundTransferPacket` | reply with `ServerboundCookieResponsePacket(key, payload)`; ignore transfer (never connect to another server) |
| 16 | S->C (config) | `ClientboundKeepAlivePacket(id)` / `ClientboundPingPacket(id)` | reply `ServerboundKeepAlivePacket(id)` / `ServerboundPongPacket(id)` |
| 17 | S->C (config) | `ClientboundFinishConfigurationPacket` | terminal for CONFIGURATION |
| 18 | C->S | `ServerboundFinishConfigurationPacket.INSTANCE` | client first does `setupInboundProtocol(GameProtocols.CLIENTBOUND bound with RegistryFriendlyByteBuf.decorator(registryAccess), gameListener)`, sends this packet, then `setupOutboundProtocol(GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registryAccess), GameProtocols.Context))`. `Context` = `hasInfiniteMaterials()` + `canUseCommandBlocks()`, both false for a survival bot |
| 19 | S->C (play) | `ClientboundLoginPacket(playerId, hardcore, levels, ..., commonPlayerSpawnInfo, onlineMode, enforcesSecureChat)` | keep `playerId`, dimension, game mode, view/simulation distance |
| 20 | S->C (play) | `ClientboundPlayerPositionPacket(id, change, relatives)` | the spawn teleport. Reply `ServerboundAcceptTeleportationPacket(id, x, y, z, yRot, xRot)` with the *exact* id and the resolved absolute position/rotation |
| 21 | C->S (play) | `ServerboundPlayerLoadedPacket` | **required**: `handleMovePlayer` returns early while `hasClientLoaded()` is false, and the server runs `clientLoadedTimeoutTimer` (`CLIENT_LOADED_TIMEOUT_TIME = 60`) |
| 22 | S->C (play) | `ClientboundSetChunkCacheCenterPacket`, `ClientboundSetChunkCacheRadiusPacket`, `ClientboundLevelChunkWithLightPacket`, `ClientboundSectionBlocksUpdatePacket`, `ClientboundBlockUpdatePacket`, `ClientboundForgetLevelChunkPacket`, `ClientboundChunkBatchStartPacket`/`...FinishedPacket(batchSize)` | decode into the per-dimension chunk store; see below |
| 23 | C->S (play) | `ServerboundChunkBatchReceivedPacket(desiredChunksPerTick)` | one per batch, right after `ClientboundChunkBatchFinishedPacket`; feeds `PlayerChunkSender.onChunkBatchReceivedByClient(float)` |
| 24 | every tick | see section 3 | |

`ClientboundLevelChunkWithLightPacket` carries `ClientboundLevelChunkPacketData` whose
`getReadBuffer()` returns a `FriendlyByteBuf` with per-section palettes. The section format is
`PalettedContainer` (bits-per-entry, palette, packed long array) readable with vanilla's
`PalettedContainer`/`ReadableContainer` codecs and `Block.BLOCK_STATE_REGISTRY`; the light data
(`ClientboundLightUpdatePacketData`) can be dropped (no rendering). `getHeightmaps()` is available
but not needed for pathing. Play<->configuration re-entry exists
(`ClientboundStartConfigurationPacket` -> `ServerboundConfigurationAcknowledgedPacket`); Phase 1-3
should handle it by re-running configuration rather than disconnecting.

## 3. Every serverbound packet a moving player must send, with its rule

From `LocalPlayer.sendChanges()`, `LocalPlayer.sendPosition()`, `Minecraft.tick()`,
`ClientPacketListener` and `ServerGamePacketListenerImpl`. The bot ticks at 20 Hz and sends **one
position packet per tick** (see section 4).

| packet | when | rule / payload |
| --- | --- | --- |
| `ServerboundKeepAlivePacket(id)` | on `ClientboundKeepAlivePacket` | echo the exact `long`. Server checks every `LATENCY_CHECK_INTERVAL = 15000 ms`; if a keep-alive is still pending at the next 15 s check it disconnects with `TIMEOUT_DISCONNECTION_MESSAGE`. Reply well inside 15 s |
| `ServerboundPongPacket(id)` | on `ClientboundPingPacket` | echo the exact `int` |
| `ServerboundClientInformationPacket` | on entry to config / on change | see §2 #8 |
| `ServerboundSelectKnownPacks` | on `ClientboundSelectKnownPacks` | empty list is legal and forces full registry data |
| `ServerboundAcceptCodeOfConductPacket` | if the server sends a code of conduct | singleton |
| `ServerboundResourcePackPacket` | on resource pack push | `UUID` + `Action` (`ACCEPTED`/`DECLINED`/`SUCCESSFULLY_LOADED`/`FAILED_DOWNLOAD`/`INVALID_URL`/`RELOAD_FAILED`/`DISCARDED`) |
| `ServerboundCookieResponsePacket` | on cookie request | `Identifier` + `byte[]` (max 5120) |
| `ServerboundChunkBatchReceivedPacket(desiredChunksPerTick)` | once per `ClientboundChunkBatchFinishedPacket` | controls server chunk send rate; constant e.g. 64 is fine |
| `ServerboundAcceptTeleportationPacket(id,x,y,z,yRot,xRot)` | on `ClientboundPlayerPositionPacket` | must echo `id`; absolute position/rotation after applying `relatives` (`Relative` set). Until the server sees it, `updateAwaitingTeleport()` makes every move packet rotation-only |
| `ServerboundPlayerLoadedPacket` | once after spawn/respawn, when chunks are loaded | otherwise movement is ignored |
| `ServerboundPlayerInputPacket(Input)` | whenever the boolean input vector changes | `Input(forward, backward, left, right, jump, shift, sprint)`; `Input.EMPTY` initially. Vanilla sends it only on change (`LocalPlayer.sendChanges`, `lastSentInput` equality) |
| `ServerboundPlayerCommandPacket(entity, Action, data)` | sprint / sneak / start-fall-flying state changes | `sendIsSprintingIfNeeded()`; needed so the server's `ServerPlayer` sprint flag matches the sim |
| `ServerboundMovePlayerPacket.PosRot(Vec3, yRot, xRot, onGround, horizontalCollision)` | position and rotation both changed | flags are packed: bit0 `onGround`, bit1 `horizontalCollision` |
| `...Pos(Vec3, onGround, horizontalCollision)` | position changed only | |
| `...Rot(yRot, xRot, onGround, horizontalCollision)` | rotation changed only | |
| `...StatusOnly(onGround, horizontalCollision)` | only the flags changed | |
| `ServerboundClientTickEndPacket.INSTANCE` | **end of every client tick**, after the movement packets | `Minecraft.tick()` sends it when `getConnection() != null && !paused`. `ServerGamePacketListenerImpl.handleClientTickEnd` resets `receivedMovementThisTick` and `receivedPositionThisTick`; without it, known-movement stays stale and the second position packet in a tick is treated as the duplicate |
| `ServerboundChatPacket` / `ServerboundChatCommandPacket` | only for the `goto` console command (Phase 3) | chat is optional for an idle bot; use local stdin instead where possible |
| `ServerboundPlayerActionPacket(Action, pos, direction, sequence)` | dig (Phase 3) | `START_DESTROY_BLOCK` -> `STOP_DESTROY_BLOCK`; must acknowledge `ClientboundBlockChangedAckPacket` sequences |

Vanilla's `LocalPlayer.sendPosition()` decision (verified bytecode):
`positionReminder++` every tick; `forcePos = lengthSquared(dx,dy,dz) > (2.0E-4)^2 || positionReminder >= 20`;
`forceRot = dYaw != 0 || dPitch != 0`; then PosRot / Pos / Rot / StatusOnly. A position packet is
therefore sent **at least every 20 ticks** even when standing still. The bot should simply send one
`PosRot` every tick (simpler and stricter than vanilla), plus `ClientTickEnd` at tick end.

## 4. `ServerGamePacketListenerImpl` movement checks (26.3, exact)

Constants from the class header:
`MAXIMUM_FLYING_TICKS = 80`, `MAX_DISTANCE_TO_VALID_POSITION = 1`,
`INVALID_POSITION_CORRECTION_MAX_INTERVAL_TICKS = 20`, `CLIENT_LOADED_TIMEOUT_TIME = 60`,
`ATTACK_INDICATOR_TOLERANCE_TICKS = 5`, `MIN_SUPPORTED_BLOCKS_RESEND_TICKS = 200`.
Fields: `firstGoodX/Y/Z`, `lastGoodX/Y/Z`, `awaitingPositionFromClient`, `awaitingTeleport`,
`clientIsFloating`, `aboveGroundTickCount`, `receivedMovePacketCount`, `knownMovePacketCount`,
`receivedMovementThisTick`, `receivedPositionThisTick`.

**`handleMovePlayer`** (`server-game-listener-bytecode.txt` line 2913):
1. `containsInvalidValues(x,y,z,yRot,xRot)` (NaN/Inf/out of clamp range) → disconnect
   `multiplayer.disconnect.invalid_player_movement`.
2. **Double-move-per-tick kick:** if `packet.hasPosition()` and `receivedPositionThisTick` is already
   true → disconnect `multiplayer.disconnect.invalid_player_movement`. This is the "two position
   packets in one server tick" rule; `handleClientTickEnd` is what clears the flag (line 6708), so
   one `ClientTickEnd` per tick is mandatory.
3. `tickCount == 0` → `resetPosition()`. `hasClientLoaded() == false` → return (movement ignored).
4. `updateAwaitingTeleport()` → rotation only (`absSnapRotationTo`), return. So a pending teleport
   must be acked before any position packet counts.
5. else `handlePlayerPositionChange(x,y,z,yRot,xRot,onGround,horizontalCollision)`.

**`handlePlayerPositionChange`** (line 3019):
- `clampHorizontal(x/z)`, `clampVertical(y)`, `Mth.wrapDegrees(yaw/pitch)`. If the player is a
  passenger the server snaps back to its own position and returns.
- **"moved too quickly"** (only when `tickRateManager().runsNormally()`): `receivedMovePacketCount++`,
  `delta = receivedMovePacketCount - knownMovePacketCount` (`knownMovePacketCount` is refreshed in
  `tickPlayer()` each server tick); if `delta > 5` log "is sending move packets too frequently" and
  clamp `delta = 1`. `maxSpeed = isFallFlying ? 300.0f : 100.0f`. If
  `(squaredDistance(newPos, firstGood) - player.getDeltaMovement().lengthSqr()) > maxSpeed * delta`
  → warn `"{} moved too quickly! {},{},{}"` and `teleport(server position)` + return. With one packet
  per tick this is unreachable for normal walking.
- The server then **re-runs the move**: `player.move(MoverType.PLAYER, new Vec3(newX-lastGoodX, newY-lastGoodY, newZ-lastGoodZ))`.
  This is why every `PlayerSim` gap becomes a correction.
- Compute `d33 = squaredDistance(clientReportedPos, serverSimulatedPos)`, with the Y difference
  zeroed when it is inside `(-0.5, 0.5)`.
  **"moved wrongly"** when `d33 > 0.0625` (i.e. > 0.25 blocks) and not changing dimension, not
  sleeping/creative/spectator, not in post-impulse grace → warn `"{} moved wrongly!"` and set a flag.
- If that flag and `!level.noCollision(player, oldBoundingBox)`, or
  `isEntityCollidingWithAnythingNew(...)`, → **teleport back to the old position** (the correction
  the bot must count), `doCheckFallDamage(...)`, `removeLatestMovementRecording()`, return.
- Otherwise `absSnapTo(clientReported position/rotation)` — accepted. Then
  `clientIsFloating` is set when `deltaY >= -0.03125` and the player has no supporting blocks, no
  flight, no levitation, no elytra and `noBlocksAround(...)`; `setOnGroundWithMovement`,
  `doCheckFallDamage`, `handlePlayerKnownMovement` (sets `receivedMovementThisTick`), and
  `lastGoodX/Y/Z = player position`.
- `shouldCheckPlayerMovement(boolean isFallFlying)` (line 3575) is gated by game rules
  `PLAYER_MOVEMENT_CHECK` / `ELYTRA_MOVEMENT_CHECK` and `isSingleplayerOwner()` / dimension change.
  Do **not** rely on turning these rules off for the live gate; they default on.

**Floating:** `tickPlayer()` (line 270) calls `resetPosition()` (which sets `firstGood*`), ticks the
server player, increments `tickCount`, sets `knownMovePacketCount = receivedMovePacketCount`, and if
`clientIsFloating` increments `aboveGroundTickCount`; when it exceeds
`getMaximumFlyingTicks(entity)` (`MAXIMUM_FLYING_TICKS = 80` baseline) it kicks with
`multiplayer.disconnect.flying` / `"{} was kicked for floating too long!"`. A bot that reports
`onGround=false` while hovering on a mismatched collision shape will be kicked after ~80 ticks.

**`handleClientTickEnd`** (line 6708): if `!receivedMovementThisTick`, set the server player's known
movement to `Vec3.ZERO`; then clear `receivedMovementThisTick` and `receivedPositionThisTick`.
`handlePlayerKnownMovement` sets `receivedMovementThisTick = true` only when the accepted movement's
`lengthSqr() > 1e-5`.

**Other corrections the bot must handle as packets, not simulate:** `ClientboundPlayerPositionPacket`
(teleport / correction, ack it, reset the sim to the sent position), `ClientboundSetEntityMotionPacket`
(knockback → write `movement` into the sim's velocity), `ClientboundEntityPositionSyncPacket`,
`ClientboundExplodePacket` (impulse), `ClientboundRespawnPacket`. Count every one of these as a
correction in Phase 2 evidence.

## 5. `PlayerSim` gaps — verified, not taken from the class doc

`src/main/java/baritone/pathing/kinematic/PlayerSim.java` and `BsiWorld.java`; decompiled 26.3
sources in `_mc263src\src\net\minecraft\world\level\block\`. The class doc says "no lava, currents,
sneaking or the swimming pose's lower box ... nothing that changes gravity", and "checked against PPE
and decompiled values, NOT against a live client". It is partly stale:

| claimed gap | verified? | detail |
| --- | --- | --- |
| lava | **gap** | no lava handling anywhere in `PlayerSim` / `BsiWorld` |
| flowing-water currents | **gap** | `PlayerSim.World.water()` is boolean "still or flowing water fills this cell (flow is not simulated)"; `tickWater` handles still water only |
| bubble columns | **gap** | no reference |
| sneaking edge stop | **gap** | `keyInput(...)` does apply the sneak *speed* multiplier 0.3 (lines 191-205), but there is no "do not walk off an edge while sneaking" rule |
| swimming / crawl lower box | **partial** | `tickWater` (line 347) does model `updateSwimming` and swim-pose vertical travel, but `PlayerSim` has a single fixed box `HALF_WIDTH=0.3f`, `HEIGHT=1.8f`; no pose-dependent bounding box, so collision while swimming/crawling is wrong |
| entity collision / pushing | **gap** | `PlayerSim.World` only supplies block boxes; no entity list |
| riding / boats / elytra | **gap** | no vehicle state; `isFallFlying` never set |
| gravity-changing effects (Levitation, Slow Falling) | **gap** | only `speedScale` and `jumpBoost` are modelled; gravity is the fixed `GRAVITY = 0.08` |
| cobweb / sweet berry / powder snow "stuck in block" | **gap, confirmed** | `_mc263src` `WebBlock.entityInside` -> `makeStuckInBlock(state, Vec3(0.25, 0.05, 0.25))`, `SweetBerryBushBlock` -> `Vec3(0.8, 0.75, 0.8)`, `PowderSnowBlock` -> `Vec3(0.9, 1.5, 0.9)`. `PlayerSim` only reads `world.speedFactor()`/`jumpFactor()` -> `Block.getSpeedFactor()`/`getJumpFactor()` (`BsiWorld` lines 88-97), which cover soul sand and honey, **not** `makeStuckInBlock`. Any of these three blocks will produce a server correction |
| slime / bed bounce | covered | `bouncy()`/`bounce()`, tag-gated (see §1 tag caveat) |
| ladders / vines, still water, step-up, block speed/jump factors, Speed/Slowness/Jump Boost | covered | class doc + `BsiWorld` overrides |
| sprint / input rules | covered | `keyInput`, `tickImpulse`, `MINOR_COS` |

Any `PlayerSim` change **must** keep `NativeParityTest` and `NativePlannerParityTest` green
(`src/test/java/baritone/pathing/kinematic/`) and be ported to `native/src/sim.rs` (32,935 bytes)
or have native disabled for that case. Both test files exist.

## 6. Client-only dependencies on goal -> A* -> PathExecutor -> movement execution

Re-measured on this revision (`src/main`, `src/api`, `src/launch`):
**186 `Minecraft.getInstance()` call sites in 66 files** (brief says 192/66; the difference is the
revision). Breakdown by top package: gui 10, pathing 5, utils 4, structure 4, spectate 4,
process 4, command 4, swarm 2, maze 2, harness 2, then 1 each in training/threat/scherzo/physics/
legit/health/gracenote/contract/cache/bombard/behavior/BaritoneProvider.

Only **two files on the goal -> A* -> PathExecutor -> movement chain** call it:
- `behavior/PathingBehavior.java:950` — `Minecraft.getInstance().gameDirectory.toPath()` for a debug
  path dump.
- `pathing/movement/movements/MovementFall.java:455,528,546,557` — four `gameMode.interact/useItem`
  calls for placing/boarding a boat (a fall-specific trick, out of scope for walking bots).

`IPlayerContext` (`src/api/java/baritone/api/utils/IPlayerContext.java`) is the real coupling. Its
signature itself is client-typed:

```
Minecraft minecraft();  LocalPlayer player();  IPlayerController playerController();
Level world();  IWorldData worldData();  HitResult objectMouseOver();  BetterBlockPos viewerPos();
default Iterable<Entity> entities() { return ((ClientLevel) world()).entitiesForRendering(); }
default BetterBlockPos playerFeet() { ... player().position(), world().getBlockState(...), ... }
```

Usage counts on `pathing/`, `behavior/`, `utils/`, `process/`: `ctx.player()` **650**,
`ctx.minecraft()` **82**, `ctx.playerController()` **26**. The player method histogram is dominated by
common `Entity`/`Player` APIs: `position` 103, `onGround` 44, `getDeltaMovement` 41, `getYRot` 31,
`tickCount` 18, `getInventory` 14, `getX/Y/Z` 31, `setYRot` 8, `isInWater` 8, `isFallFlying` 7,
`onClimbable` 6, `isInLava` 6, `isSwimming` 5, `isCrouching` 5, `horizontalCollision` 5,
`setXRot` 4, `isEyeInFluid` 4, `inventoryMenu` 4, `input` 4, `getXRot` 4, `getVehicle` 4,
`swing` 3, `isSprinting` 3, `isPassenger` 3, `getAttributeValue` 3, `distanceTo` 3,
`blockPosition` 3, `isShiftKeyDown` 2, `mainSupportingBlockPos` 1, `yRotO` 1, `xRotO` 1.
Client-only ones in that list: `input` (`ClientInput`/`KeyboardInput`), `inventoryMenu`, `swing`,
`getName`, `setSprinting` via controller.

`ctx.minecraft()` on the chain is used for: `options.autoJump` (`PathingBehavior` 385-389),
`options.sensitivity` (`LookBehavior`), `options.renderDistance` + `isSameThread()`
(`BlockStateInterface` 84-94), `gui.screen()` (`LookBehavior`, `PathRenderer`), `gameDirectory`
(`SwarmBehavior`, `PathingBehavior:950`), `gameMode` (`BlockBreakHelper`),
`new KeyboardInput(options)` (`InputOverrideHandler:209`), `Minecraft.execute` (`SwarmBehavior`),
`SeedPlanning.sync` (`PathingBehavior:181`).

`BlockStateInterface` (`src/main/java/baritone/utils/BlockStateInterface.java`) has **two**
constructors: a public client one that casts `world.getChunkSource()` to `ClientChunkCache`,
optionally `createThreadSafeCopy()`, and throws unless `ctx.minecraft().isSameThread()`; and a
`protected BlockStateInterface(int minY, int height, BetterWorldBorder worldBorder)` added by
Ostinato "headless constructor for snapshot/synthetic worlds" whose subclass overrides `get0`,
`isLoaded`, `worldContainsLoadedChunk`. `BsiWorld` takes a `BlockStateInterface` and reads collision
shapes off it. **The chunk-store `BlockStateInterface` already has a working headless base class**;
Phase 3 needs to subclass it over the network chunk store and also provide `Level`/`IPlayerContext`
views.

`Baritone.settings()` is a static singleton (`src/main/java/baritone/Baritone.java:440`).
`BaritoneProvider.createBaritone(Minecraft)` takes a client `Minecraft`. Phases 1-3 run one bot per
JVM; per-bot settings are Phase 4.

## 7. Plan to cut each dependency behind interfaces, flag-free for the client

The Fabric mod must behave exactly as before: no `if (headless)` flags in hot code, no changed
behaviour on the client path. Proposed sequence (Phase 3):

1. **`IClientServices`** (new interface in `src/api`, name provisional): getters for `autoJump`,
   `sensitivity`, `renderDistance`, `isSameThread`, `screenActive`, `gameDirectory`,
   `interactionController`, `executor`. `ClientServices.INSTANCE` delegates to
   `Minecraft.getInstance()`; `HeadlessServices` returns constants/no-ops. Replace the ~20
   `ctx.minecraft()` reads *on the chain* with `ctx.services().x()`. The other 162 call sites in
   gui/process/command/etc. are untouched — those classes are not instantiated by the headless bot.
2. **`BlockStateInterface`** — add a protected/public constructor variant taking a
   `ChunkAccess`-like `BlockGetter` supplier or reuse the existing headless constructor; route
   `isSameThread()` through `IClientServices`. Client path unchanged (same `ClientChunkCache`).
3. **`IPlayerContext.player()`** — the hardest cut. Options, in order of preference:
   (a) introduce `IPlayerView` (position, velocity, rotation, onGround, bounding box, inventory,
   effects, water/lava flags, supporting block, tickCount) and migrate only the chain's
   `ctx.player()` calls (most are common `Entity` getters) to `ctx.view()`; the client implementation
   is a thin adapter over `LocalPlayer`; the headless one is a value object fed by `PlayerSim`;
   (b) alternatively narrow `IPlayerContext.player()` from `LocalPlayer` to common `Player` and
   supply a lightweight `Player` subclass — rejected for now because `Player`/`LivingEntity`
   construction needs a `Level` and pulls in more client state than (a).
   Needed before Phase 3's `goto` gate; Phase 1/2 do not need it.
4. **`InputOverrideHandler`** — keep the client `KeyboardInput` path; add an `IMovementSink`
   interface whose client impl is the existing handler and whose headless impl turns
   `MovementState` into `PlayerSim` inputs + one `ServerboundMovePlayerPacket` per tick. The
   `new KeyboardInput(...)` line stays on the client side only.
5. **`playerFeet()` / `entities()` / `objectMouseOver()` / `viewerPos()`** — override in the headless
   `IPlayerContext` (they are already default or abstract methods), avoiding the `ClientLevel` cast.
6. **`PathingBehavior:950`** and `MovementFall` boat interactions — guard by capability on
   `IClientServices` (e.g. `gameDirectory()` returning an empty `Optional`, `interact()` returning
   `PASS`), not by a headless boolean. Boat falls are excluded from the Phase 3 course.
7. **`Baritone.settings()`** (Phase 4): thread a `Settings` instance through
   `Baritone`/`CalculationContext`/`PathingBehavior` instead of the static accessor; one instance
   per bot. `BaritoneProvider` gets a headless factory taking no `Minecraft`.
8. **TenorClef (out of scope)**: ~100 client call sites. It would need the same `IClientServices`/
   `IPlayerView` treatment plus its own inventory/window/entity trackers rebuilt over packets. Leave
   untouched.

## 8. Architecture to build (Phases 1-4)

- New Gradle **source set** `headless/` in the root `build.gradle`, `compileClasspath += main.output +
  main.compileClasspath` (Unimined already supplies all 26.3 + Netty + DFU dependencies), plus a
  `JavaExec` `runHeadlessBot` task. A source set (not a subproject) avoids re-running Unimined's
  `minecraft {}` block on a second project. Written reason for the merged-jar deviation is in §1.
- Vanilla packet classes and codecs only; no hand-rolled protocol.
- Listeners: `ClientLoginPacketListener`, `ClientConfigurationPacketListener`,
  `ClientGamePacketListener` (127 abstract methods — implement with a no-op adapter base class or a
  JDK dynamic proxy that dispatches by method name; the latter avoids 127 stubs and is safe because
  `Packet.handle` calls the typed method on the interface). `ClientCommonPacketListener` methods
  (keep-alive, pong, disconnect, custom payload, resource pack, cookie) are shared.
- Registry/tag state: build `RegistryAccess.Frozen` from config packets (§1/§2); bind both PLAY
  protocol directions with `RegistryFriendlyByteBuf.decorator(access)`.
- Chunk store: per-dimension `Map<ChunkPos, HeadlessChunk>` of `PalettedContainer<BlockState>`
  slices, updated from `ClientboundLevelChunkWithLightPacket`, `ClientboundSectionBlocksUpdatePacket`,
  `ClientboundBlockUpdatePacket`, `ClientboundForgetLevelChunkPacket`; ignore light. Expose to
  Ostinato through a `BlockStateInterface` subclass (§6) and `BsiWorld`.
- `IPlayerContext` headless impl (§7), `PlayerSim` tick -> one movement packet per 50 ms tick,
  `ServerboundClientTickEndPacket` at tick end. Teleport ack, velocity packets and corrections
  counted explicitly.
- Phase 3 console command: local stdin preferred; a console whisper via `ServerboundChatPacket` is
  the fallback (chat commands need `ServerboundChatCommandPacket` and signed-argument handling;
  `enforcesSecureChat=false` on our offline server).
- Phase 4: per-bot `Settings`, per-bot world/entity state, shared immutable chunk data where safe;
  measure private working set with `Get-Process`/`Win32_Process` and server TPS from the console
  (no RCON).

## 9. Out of scope — what each would need

| item | what it needs |
| --- | --- |
| Online auth | Microsoft OAuth/device code, `ServerboundKeyPacket` encryption, session join, `ClientboundHelloPacket` handling, `MinecraftSessionService`; explicitly excluded and the live server is `online-mode=false` |
| TenorClef | ~100 client call sites; `IClientServices`/`IPlayerView` (§7) plus packet-backed inventory, container/window, entity and recipe trackers |
| Combat / PvP | entity tracker, `ServerboundInteractPacket`, `ServerboundAttackPacket`, `ServerboundPunchPacket`, cooldowns, `ClientboundDamageEventPacket`, knockback handling |
| Swarm coordinator link | SIGIL / `InMemorySwarmBus` (`transport=local` roster value), signed commands, reservations; only relevant after Phase 4 |
| Inventory / crafting GUIs | `ClientboundContainerSetContentPacket`/`SetSlotPacket`/`SetDataPacket`, `ServerboundContainerClickPacket`, `ClientboundBlockChangedAckPacket` sequence numbers, recipe book |
| Rendering / viewer | none — deliberately excluded; no GL, no window, no render thread |

## 10. Open questions / risks (carry into the gates)

1. The full test suite is flaky on a loaded machine (§1). Every commit needs a quiet
   `gradlew test --rerun`; report the pre-existing `LongRouteSimTest` flake faithfully.
2. `ClientGamePacketListener` has 127 abstract methods and `ClientboundLoginPacket` uses
   `RegistryFriendlyByteBuf`; getting the PLAY protocol bound with the config-derived `RegistryAccess`
   before the first `ClientboundLoginPacket` is the sharpest Phase 1 risk.
3. Tags must be applied from configuration or `BsiWorld`/`MovementHelper` silently mis-plan (§1).
4. `PlayerSim`'s missing `makeStuckInBlock`, lava, currents, pose box and gravity effects (§5) will
   produce server corrections if the Phase 2/3 courses touch those blocks. The Phase 2 course
   (walk, sprint, jump, step-up, ladder, still water) is inside covered territory by design; the
   Phase 3 100+ block course must be built on normal terrain only.
5. The server re-runs movement with `MoverType.PLAYER`; even a perfect `PlayerSim` may differ because
   the server-side `ServerPlayer` has `Input`/`knownMovement`/`setOnGroundWithMovement` state driven by
   our packets. Phase 2 must count corrections, not assume zero.

## 11. Gate 0 result

- Notes written: this file. Covers all five required survey items (§1 Bootstrap proof, §2 packet
  sequence, §3 serverbound packet rules, §4 movement checks, §5 verified PlayerSim gaps, §6/§7 the
  client-only dependency map and the interface-cutting plan), plus §8 architecture and §9 out-of-scope.
- Bootstrap probe test: **PASS**, 8 tests / 0 failures / 0 skipped / ~6.1 s, plain JVM, no client,
  no Fabric. Evidence `headless-bot-logs\phase0\bootstrap-probe.log` and the JUnit XML.
- Full existing suite at the Phase 0 commit: **PASS**, 2041 tests / 0 failures / 0 errors /
  24 skipped, `gradlew test --offline --rerun -Dorg.gradle.jvmargs=-Xmx1g --max-workers=2`,
  9m 23s. Evidence `headless-bot-logs\phase0\full-test-after-fix.log`.
- Survey evidence: `headless-bot-logs\phase0\javap\*.txt`, `baseline-test.log`,
  `longroute-isolated.log`, `longroute-package.log`; Phase 1 API evidence
  `headless-bot-logs\phase1\api-signatures.txt`, `listener-contracts.txt`,
  `registry-signatures.txt`, `chunk-decode-bytecode.txt`.
- FQNs in §2 were re-verified with `javap` against the 26.3 merged jar after the first draft;
  several 26.3 packages differ from older releases (see the verified-name table in §2).
- No feature code written in Phase 0 (the probe is a JUnit survey tool, not bot code). The one
  production-repo change is test-only isolation in `LongRouteSimTest`.
- **GATE 0: PASS** (notes written + Bootstrap probe passes + full suite green).
- No live gates have been run; Phases 1-4 are all **needs a live run** at this point.

## 12. Phase 1 implementation findings (correction to the Phase 0 survey)

Phase 0's probe built `RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)` and
checked that `GameProtocols.CLIENTBOUND_TEMPLATE.bind(...)` produced a non-null `ProtocolInfo`.
That proves the **codec object can be constructed**; it does not prove a PLAY packet can be
**decoded**. Writing Phase 1 code found the real blocker:

* 26.3 has **95 static registries** after `Bootstrap.bootStrap()`. `minecraft:worldgen/biome` and
  `minecraft:dimension_type` are **datapack registries** and are absent from the built-in access.
  Evidence: `headless-bot-logs\phase1\registry-diag.log` / `registry-diag2.log`.
* `PalettedContainerFactory.create(builtInAccess)` throws
  `IllegalStateException: Missing registry: minecraft:worldgen/biome`.
  `ClientboundLoginPacket.commonPlayerSpawnInfo()` needs `Holder<DimensionType>`, so the first
  PLAY packet would fail the same way.
* `RegistryDataLoader.load(Map.of(), ResourceProvider.EMPTY, ...)` fails with 13
  `Registry must be non-empty: minecraft:*_variant` errors: `NetworkRegistryLoadTask.load` returns
  immediately for any synchronized registry with no network entry, and a `ResourceProvider` cannot
  enumerate files.

Fixes, both common-side and using vanilla code (no `net.minecraft.client.*`):

1. `HeadlessRegistries` is the replacement for the client-only `RegistryDataCollector`. It
   accumulates `ClientboundRegistryDataPacket` / `ClientboundUpdateTagsPacket`, resolves static
   tags with `NetworkPayload.resolve` -> `Registry.prepareTagReload` -> `PendingTags.apply`, and
   rebuilds the RELOADABLE layer with
   `RegistryDataLoader.load(Map<ResourceKey, NetworkedRegistryData>, ResourceProvider,
   TagLoader.buildUpdatedLookups(...), SYNCHRONIZED_REGISTRIES, executor)` over
   `RegistryLayer.createRegistryAccess()`. The bot calls `build()` immediately before binding the
   PLAY protocol, after the last configuration packet. The Mojmap merged jar bundles the whole
   vanilla datapack (9,884 `data/` entries), so `CLASSPATH_RESOURCES` supplies the same JSON the
   client's resource manager would.
   **Only a live server supplies the full `NetworkedRegistryData` map**; `build()` with no config
   data is not a supported path (documented, and the unit tests do not pretend otherwise).
2. Chunk decoding no longer needs a biome registry at all. `HeadlessChunkStore.readSection` uses
   vanilla `PalettedContainer.read(FriendlyByteBuf)` for the block states and again for the biome
   palette (to consume the bytes), with a synthetic `IdMap<Integer>` standing in for the BIOME
   registry. This is safe because `Strategy$2.getConfigurationForBitCount` derives the wire storage
   length from the **bits on the wire**, not from the id map, and `PalettedContainer.read` only
   calls `IdMap.byId` through `globalMap()`. Evidence:
   `headless-bot-logs\phase1\paletted-container-bytecode.txt` and
   `chunk-decode-bytecode.txt`. Pathing only needs block states.

Net effect: the only thing that still needs the config-derived registry access is PLAY packet
decoding, which is exactly what the server sends registry data for. The Phase 0 "PLAY protocol
binding works" claim is therefore narrowed to: **the codec binds; decoding needs the config data**.

Live confirmation (Phase 1 gate, 2026-11-14 02:41-02:51 MST): the server sent 32 registry-data
packets; `HeadlessRegistries.build()` produced **127 registries** and 15 tag registries were applied
before the PLAY codec was bound. `ClientboundLoginPacket` decoded its `Holder<DimensionType>`,
49 chunks / 1176 sections decoded, and `execute if block` markers at (5,-60,5) diamond,
(6,-60,5) emerald and (7,-60,5) gold - the last one changed **after** the bot joined - all read
back identically from the bot's chunk store. So the reconstruction is not just theoretical.

## 13. Phase 2 implementation: PlayerSim as the movement authority

The dependency cut is deliberately narrow. Nothing in `src/main` was rewritten for Phase 2:

* `PlayerSim` (`src/main/java/baritone/pathing/kinematic/PlayerSim.java`) is **pure Java** - its only
  imports are `java.util.ArrayList/Arrays/List`. `BsiWorld` uses only `net.minecraft.world.level.*`,
  `core.BlockPos`, `phys.AABB`, `phys.shapes.VoxelShape`. Neither touches a client class.
* `BlockStateInterface` already had a `protected BlockStateInterface(int minY, int height,
  BetterWorldBorder)` headless constructor. `HeadlessBlockStateInterface` is a **subclass**, not a
  modification: `get0`, `isLoaded`, `isKnown`, `worldContainsLoadedChunk` read the decoded chunk
  store, and unloaded cells map `VOID_AIR` -> `AIR` so collision stays passable.
* `HeadlessMovement` is the replacement for `KeyboardInput` / `InputOverrideHandler`. It owns a
  `PlayerSim(new BsiWorld(bsi))`, seeds it from the server-confirmed spawn, and each 20 TPS tick
  emits one `ServerboundPlayerInputPacket` on change, one `ServerboundPlayerCommandPacket` on
  sprint-state change, exactly one `ServerboundMovePlayerPacket.PosRot`, and one
  `ServerboundClientTickEndPacket` last in the tick. That last packet is what clears the server's
  `receivedPositionThisTick`; without it the second position packet in a server tick is a kick.
* 26.3's `ServerboundPlayerCommandPacket` has only **Entity-based** public constructors. A plain JVM
  has no `Entity`. Rather than invent a packet class or fabricate an `Entity`, the bot writes the
  three wire fields into a `FriendlyByteBuf` and calls the packet's own
  `STREAM_CODEC.decode(...)`, which reaches vanilla's private `(FriendlyByteBuf)` constructor.
  Still a vanilla packet, still a vanilla codec, no reflection and no client object.
  Evidence: `headless-bot-logs\phase1\movement-api.txt`.
* Corrections are counted from `ClientboundPlayerPositionPacket` past the spawn teleport,
  `ClientboundSetEntityMotionPacket` for our entity id, `ClientboundEntityPositionSyncPacket` for
  our id when `endPosition()` differs, and `ClientboundExplodePacket` knockback. The first two also
  write the server-owned state back into the sim (`resync` / `applyVelocity`).

**Known limitation:** `ClientboundMoveEntityPacket.Pos/PosRot` do not expose an entity id in their
public API, so they are still counted as unhandled rather than classified. Phase 1 saw 1548 `Pos`
and 406 `PosRot` over 10 minutes while the bot never moved and the server never corrected it; they
are therefore very likely other-entity traffic (or self-sync with no delta), but that is an
inference, not a proven fact, and is listed as an open issue.

Phase 2 **does not** implement a headless `IPlayerContext`, A* execution, or `PathExecutor`; those
are Phase 3 and remain **needs a live run**.

## 14. Phase 2 live gate: obstacle course (2026-11-14 11:13-11:18 MST)

**Gate: PASS.** One bot (`HBot1`, protocol 777) on the local dedicated 26.3 server
(`127.0.0.1:25570`, `online-mode=false`), offline build at `440aefc65`, no client JVM.

World, built and verified through the server console (commands in
`headless-bot-logs\console-in.txt`, replies in `server\server.out`):

| feature | build command | verification marker |
| --- | --- | --- |
| floor | `fill -2 -61 -2 2 -61 72 minecraft:stone` | top surface y=-60 |
| slabs (step-up) | `fill -1 -60 20 1 -60 34 minecraft:smooth_stone_slab[type=bottom]` | `SLAB_OK`, top y=-59.5 |
| plateau | `fill -1 -60 48 1 -54 52 minecraft:stone` | `PLATEAU_OK`, top y=-53 |
| ladder | `fill -1 -60 47 1 -54 47 minecraft:ladder[facing=north]` | `LADDER_FACING_NORTH_OK`, `LADDER_TOP_FACING_NORTH_OK`, `LADDER_SUPPORT_Z48_OK` |
| water pool | `fill -2 -61 55 2 -61 72 minecraft:stone` + `fill -1 -60 55 1 -59 72 minecraft:water` | `WATER_OK`, `A_WATER_AT_0_-60_60` |

Course `headless-bot-logs\phase2\obstacle-course.txt` (13 segments, 651 ticks): settle, walk,
sprint, jump take-off + arc, step-up, slab walk-off, ladder approach + climb, plateau walk-off,
walk to water, wade, settle. Driver evidence is one line per segment at 20 TPS.

Result, from `headless-bot-logs\phase2\obstacle-evidence.txt` and `obstacle-gate-run.log`
(300.2 s run, 6,002 ticks, 4,754 simulated movement ticks after attach):

```
SIM_FINAL x=-0.5 y=-60.0 z=93.116400706 | REPORTED x=-0.5 y=-60.0 z=93.116400706
          | delta=0.00000000000 | resyncs=0 corrections=0
```

Segment checkpoints (sim position at the end of each stage, no correction/resync anywhere):

| stage | tick | sim position | what it proves |
| --- | --- | --- | --- |
| settle | 20 | (-0.5, -60.0, -0.5) | spawn-consistent rest |
| walk | 80 | (-0.5, -60.0, 12.19) | 0.203 blocks/tick ground walk |
| sprint | 120 | (-0.5, -59.5, 23.34) | slab step-up on the way in, sprint speed 0.24+ |
| jump | 121-161 | (-0.5, -59.5, 35.34) | sprint-jump arc (y peaks then lands) |
| climb/plateau | 211-271 | (-0.5, -53.0, 47.85) | **ladder** ascent with jump input = 0, lands on plateau top |
| water | 441-541 | (-0.5, -60.0, 68.8 -> 78.6) | wade speed 0.098 blocks/tick vs 0.203 on land |
| settle | 651 | (-0.5, -60.0, 93.116) | final rest outside the pool |

Cross-checks:

* `server\server.out` 11:13:37-11:18:37: join, then only the bot's own clean disconnect at
  11:18:37. No `moved wrongly`, no `moved too quickly`, no kick.
* `corrections=0` is counted from `ClientboundPlayerPositionPacket` past the spawn teleport,
  `SetEntityMotion` / `EntityPositionSync` for our id, and explosion knockback; `resyncs=0`.
* Tick loop stayed at `chunks=49` and `tickErrors=0` for the whole run.
* The ladder segment is the regression that originally stalled at `z=46.699999988`: with
  `facing=south` the collision plate sat at z=47.0 and the support face pointed at z=46 air, so the
  player stopped in front of the climbable cell. `facing=north` puts the plate against the plateau
  at z=48, which is what vanilla requires for a ladder attached to a block; after the rebuild the
  same course climbs without any driver change.

Caveats: still one bot, one dimension, still (non-flowing) water, no entities, mobs, inventory or
damage; the course exercises walk, sprint, sprint-jump, 0.5 slab step-up, ladder climb across 7
blocks of height and 2-deep water, which is the Phase 2 scope. Phase 3 wiring is section 15. This
paragraph is the Phase 2 gate record and is left as it was written.

## 15. Phase 3: pathing host, live driver, goto / cancel / mine

The protocol bot is unchanged through login, configuration and the idle play tick. After spawn the
process builds one pathing host (`HeadlessLevel`, `HeadlessPlayer`, `HeadlessPlayerContext`,
`HeadlessPlayerController`, `HeadlessLookBehavior`, `HeadlessInputOverrideHandler`,
`HeadlessBaritone`, `HeadlessPathingBehavior`) inside `HeadlessLiveDriver`. `HeadlessBotMain`
does that once `PLAY` has a spawn position and the player-loaded packet has been sent. Unit tests
that never ask for a path do not build it, so the Phase 1 reminder counts stay put.

`Level`'s constructor needs real biome and damage-type registries. After configuration the bot
already has those from `HeadlessRegistries.build()`, and the level uses that access. A unit test
(or a session that never received registry packets) does not, so `HeadlessLevel.create` fills
them from `VanillaRegistries.createWorldLookup()` and keeps the built-in registries. Packet
decoding still uses the bot's own access.

### Commands

`commands.txt` (append-only), stdin, and system chat all use the same parser (`HeadlessCommands`).

| line | effect |
| --- | --- |
| `probe <x> <y> <z>` | unchanged |
| `status` | unchanged, and the snapshot now includes `pathing: ...` |
| `course <file>` | unchanged; a live path pauses the course on its current tick and resumes it afterwards |
| `goto <x> <y> <z>` | queue `GoalBlock` on the tick thread |
| `mine <block>` | nearest loaded breakable block in 16×8, path next to it, break it, wait until the cell is air |
| `place <x> <y> <z>` | place one throwaway into that cell. See section 16 |
| `cancel` | cancel pathing, mining, and placing, clear forced keys. Does **not** exit |
| `stop path` | alias of `cancel` |
| `stop`, `quit`, `exit` | end the process, same as Phase 1/2 `stop` |

Chat acceptance (local offline server, console whispers to `HBot1` / `HBot2`):

* `... whispers to you: <command>`
* `... -> me]` / `... -> you] <command>`
* `[Server] <command>` and `<Server> <command>`
* a system-chat line whose whole text is itself a command

Public chat (`<Bob> goto ...`) and `You whisper to ...` are ignored. `stop` from an accepted
whisper still ends the run. There is no inventory-click or signed-chat command channel.

### Movement packets

`HeadlessBot.tick` still sends exactly one movement packet and then `ClientTickEnd`.

* Nothing queued and no course left: `StatusOnly`, plus a `PosRot` every 20 ticks (Phase 1).
* A course still has steps, or a goto/mine owns the keys: `HeadlessMovement` sends one `PosRot`.
  Pathing does not advance the course. After the course finishes, the bot returns to the reminder.
* `gotoGoal` is not on the command thread. The tick drains the queue, then sends the one packet.
  A partial path's next search is queued the same way, from `HeadlessPathingBehavior.tick`.

Before `PathExecutor` runs, and again after `PlayerSim` steps, `HeadlessLiveDriver.sync` copies
position, velocity, onGround, yaw, pitch, sprint and pose into `HeadlessPlayer`.

### A*

`AStarPathFinder.calculate` runs on the daemon thread `headless-astar`. The tick thread only builds
the `CalculationContext` and installs the `PathExecutor` when the future is done. That setup can
hitch one tick; the search itself does not, and it cannot emit a second movement packet.
`primaryTimeoutMS` / `failureTimeoutMS` are the existing settings (500 ms primary).

### Placement

Section 16. Constructing the host now turns `allowPlace`, `allowParkour` and `allowParkourPlace`
on. `allowGodBridge` and `unifiedExecutor` stay false.

### Safety

* `IPlayerContext.localPlayer()` is `player() instanceof LocalPlayer lp ? lp : null`.
  `HeadlessPlayerContext` overrides it to return null.
* `MixinNetworkManager` null-checks `localPlayer()` at all four packet sites before touching
  `connection`. `MixinClientPlayNetHandler` already did the same.

### Open

* Live gate (offline 26.3 server, parkour+ladder, confirmed break) is not run in this slice.
* Block placement itself is section 16. The live gate (give, place, bridge, pillar) is still not run in this slice.
* `mine` is the nearest loaded block, not the client `#mine` process. It gives up if the block's
  chunk unloads, the path cannot reach it, or 1200 ticks pass.
* Whisper text is whatever `Component.getString()` flattens to. A server whose tell format matches
  none of the patterns above will not take chat commands; stdin and `commands.txt` still work.
* `cancelRequested` on the A* search is the existing non-volatile flag. Cancel is queued onto the
  tick thread and then calls `finder.cancel()`, same as the client.
* Building the host flips four process-wide settings. A second Baritone in the same JVM would see
  them. The headless process is one bot.
* Respawn and play→configuration enqueue `cancel` for the next loaded tick. A position correction
  still only resyncs `PlayerSim`, as in Phase 2.

Full Ostinato suite after this slice (`gradlew test --rerun`, 2026-11-15): 2071 tests completed,
0 failed, 24 skipped. Phase 2 at 76151d599 was 2062 completed with the same 24 skipped; the 9
added tests are the command parser, the phase 3 host checks, and the live-driver pause.
TenorClef `:26.3:test` was not run; this slice does not change shared TenorClef code.

## 16. Phase 3b: inventory mirror and block placement

`HeadlessInventory` keeps the survival hotbar, main inventory, offhand and carried cursor.
`ClientboundContainerSetContentPacket` and `ClientboundContainerSetSlotPacket` apply when
`containerId` is `0` (the player menu) or `-1` (the cursor). Menu slots go through
`InventoryMenu`: hotbar row `USE_ROW_SLOT_START..END` is hotbar `0..8`, `INV_SLOT_START..END`
is the backpack, `SHIELD_SLOT` is the offhand. `ClientboundSetHeldSlotPacket` selects a hotbar
slot. `ClientboundSetPlayerInventoryPacket` writes a raw inventory index when one arrives.
Any other container id (a chest) is left unhandled. Packets that show up before the pathing
player exists are queued and flushed in `ensureHost`.

`hasGenericThrowaway()` is true when an acceptable throwaway (the existing
`acceptableThrowawayItems` list, skipping AltoClef-protected items) sits in the hotbar, the
offhand, or the backpack, and interaction is not paused. `selectThrowawayForLocation(true)`
selects a hotbar stack and sends `ServerboundSetCarriedItemPacket` when that slot is not the
one last sent. Offhand is used by selecting an empty or non-block hotbar slot so the right-click
tries `OFF_HAND`. A backpack-only stack counts for planning (`select(false)` / `hasGenericThrowaway`)
but is not moved: a survival swap is a hashed `ServerboundContainerClickPacket`, and this slice
does not send one. `/give` fills the hotbar first, which is what the live gate uses.

### Packets

Destroy and place share one prediction counter. It pre-increments the way vanilla's
`BlockStatePredictionHandler` does, so the first action packet is sequence `1`.

| packet | when |
| --- | --- |
| `ServerboundSetCarriedItemPacket(slot)` | selected hotbar slot changed (`syncHeldItem` / select throwaway) |
| `ServerboundUseItemOnPacket(hand, BlockHitResult, sequence)` | `processRightClickBlock`. The hit is the caller's (face, block pos, face-center location), not a rebuilt ray |
| `ServerboundPlayerActionPacket` | unchanged destroy START / STOP / ABORT, now on the same pre-incremented counter |

The place prediction decrements the held `BlockItem` only. It does not write the block into the
chunk store. A second click while a place is in flight does not send another packet.

### Confirm and rollback

The cell is placed only when the chunk store shows the expected block (`ClientboundBlockUpdatePacket`
or a section update). `ClientboundBlockChangedAckPacket` for that sequence, or 40 ticks with no
matching block, rolls the stack back to the pre-click copy when the slot still shows the
prediction (empty, or count == snapshot − 1). A later update that does show the block re-applies
the decrement, so the inventory and the chunk store do not disagree. Status mirrors the break
path: `lastPlaced`, `lastPlacedSequence`, `lastPlaceAcked`, `lastPlaceFailed`, `placeConfirmed(pos)`,
`placeRejected(pos)`.

Path execution looks at the block, right-clicks (`CLICK_RIGHT`, main hand then off hand, left click
suppresses the right click), and keeps the movement until the chunk shows the block. `cancel`
drops an in-flight prediction the same way a rejection does.

### `place <x> <y> <z>`

Accepted from `commands.txt`, stdin, and the same chat parser as `goto`. `stop` still ends the
run. `cancel` / `stop path` cancel placing as well as pathing and mining.

The target cell is `(x, y, z)`. The bot clicks a sturdy face of an adjacent support: below the
cell first (the top of the floor), then the four sides, then above. The hit is the center of
that face. The new block is the neighbor of the clicked face, which is the vanilla place
contract; a replaceable block at the clicked cell is the cell itself. One tick aims
(`PosRot` carries the look) and the next tick sends `UseItemOn`. Out of reach, no support,
no throwaway, or an occupied cell fails with no packet. A rejection is retried a few times,
then reported. The live gate is: give blocks, `place`, probe the cell.

### Planner flags

| setting | headless value | why |
| --- | --- | --- |
| `allowPlace` | true | throwaway select and `UseItemOn` are real |
| `allowParkour` | true | parkour moves are not generated unless this is on |
| `allowParkourPlace` | true | a parkour that places under itself can plan and the right-click path executes it |
| `allowGodBridge` | **false** | planning it is not enough. Execution walks backward off the edge and right-clicks only inside a sub-block window. PlayerSim has not been shown to hit that window, and this slice does not change PlayerSim. Sneak-bridge is the place path the bot can execute |
| `unifiedExecutor` | false | the legacy path executor is what the live driver ticks |

`gotoGoal` passes `allowPlace && hasGenericThrowaway()` into `CalculationContext`, so a bridge
and a 5-high pillar plan when the hotbar holds dirt or cobble, and the executor places through
`tickUse`.

A 5-block gap with a floor on the far side is one parkour extension (`MovementParkour.extensions`
is 1; kinematic travel is the default, so the 4-block jump after one laid block is allowed).
The landing already exists, so `Movement.toPlace` is empty for that move. The laid block is
placed by sneaking to the edge and right-clicking, then the jump runs. A pillar's placed cell
is `Movement.toPlace` (the feet block it jumps onto). A gap longer than two extensions is a
chain of sneak-bridge traverses: each step's `Movement.toPlace` is the air under the next
block, and the executor sneaks, right-clicks, and waits until the chunk shows that block.
God-bridge stays off.

Sneak-bridge needs 26.3 `Player.maybeBackOffFromEdge` in PlayerSim. Sneak speed
alone still walks the box off the edge; the movement packet then reports
`onGround=false`, and a vanilla server (`allow-flight=false`) kicks "floating too
long". The clamp is implemented. Do not turn on `allow-flight` to hide that. Live
evidence 2026-11-15 02:46 MST: A* planned a 17-block sneak-bridge, two dirt blocks
were placed, then a kick at -0.55,-60.42,23.35 (SIM delta=0).

### Open

* Live gate: (a) `place 1 -60 16` PASS 02:45 MST (probe dirt, placeAcked, no kick).
  (b) sneak-bridge first run kicked as above; PlayerSim clamp is in, rerunning with
  `allow-flight=false`. (c) pillar and (d) goto-that-places are pending that rerun.
* God-bridge stays off, as above.
* Backpack-only blocks let a bridge plan, then `select(true)` cannot move the stack, so the
  click may use the wrong hand and the place fails or replans. Hotbar `/give` is the supported case.
* No container clicks, chests, pickup, drop, armor, crafting, or combat.
* `ClientboundTakeItemEntityPacket` is still unhandled. A break that drops an item does not
  enter this inventory until a later set-slot / set-content packet.
* One place prediction is in flight at a time. The chunk store is never written by the prediction.
