/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 1). New file, replaces no Baritone code.
 *
 * Login -> configuration -> play state machine, using vanilla's own packet classes and codecs.
 * No Minecraft client object is constructed anywhere; the only game side used is Bootstrap,
 * the built-in registries, the network codecs and the chunk palette code.
 */
package baritone.headless;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ClientboundStoreCookiePacket;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ClientboundCodeOfConductPacket;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks;
import net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.configuration.ServerboundAcceptCodeOfConductPacket;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * One bot connection. All clientbound packets funnel through {@link #onPacket(Object)}; all
 * serverbound packets go through {@link HeadlessTransport}, which makes the whole sequence
 * observable in a JUnit fake-server test.
 */
public final class HeadlessBot implements HeadlessPacketListeners.Handler {

    public enum State { NEW, LOGIN, CONFIGURATION, PLAY, DISCONNECTED }

    /** A survival bot: no creative slot packets, no command-block packets. */
    private static final GameProtocols.Context SURVIVAL_CONTEXT = new GameProtocols.Context() {
        @Override
        public boolean hasInfiniteMaterials() {
            return false;
        }

        @Override
        public boolean canUseCommandBlocks() {
            return false;
        }
    };

    private static final float DESIRED_CHUNKS_PER_TICK = 64.0f;
    /** Vanilla sends a position packet at least once every 20 client ticks. */
    private static final int POSITION_REMINDER_TICKS = 20;

    private final HeadlessTransport transport;
    private final String host;
    private final int port;
    private final String username;
    private final UUID uuid;
    // Ostinato: the PLAY codec needs datapack registries that Bootstrap does not create. The
    // built-in access is a placeholder until configuration has delivered the real registry data.
    private volatile RegistryAccess.Frozen registryAccess;
    private final HeadlessRegistries registries = new HeadlessRegistries();
    private final HeadlessChunkStore chunks;
    // Ostinato: inventory packets can arrive before the pathing player exists. They queue here.
    private final HeadlessInventory inventory = new HeadlessInventory();
    // Ostinato: nearby entities, for combat. Packets can arrive before the pathing host exists.
    private final HeadlessEntityTracker entities = new HeadlessEntityTracker();
    // Ostinato: once spawned, this driver ticks PlayerSim and sends a movement packet every tick,
    // including while idle — a real client does the same, and a frozen mid-air pose is a flying flag.
    private volatile HeadlessMovement movement;
    // Ostinato: the pathing host, built once the bot has spawned. Null until then.
    private volatile HeadlessLiveDriver live;
    private final Object hostLock = new Object();
    private final ConcurrentLinkedQueue<String> pathingLines = new ConcurrentLinkedQueue<>();
    private volatile Consumer<String> chatCommands;

    private final ClientLoginPacketListener loginListener;
    private final ClientConfigurationPacketListener configurationListener;
    private final ClientGamePacketListener gameListener;

    private volatile State state = State.NEW;
    private volatile String disconnectReason;
    private volatile int compressionThreshold = -1;
    private volatile int playerId = -1;
    private volatile String dimension = "";
    private volatile int chunkCenterX;
    private volatile int chunkCenterZ;
    private volatile int chunkRadius = -1;
    private volatile boolean loadedSent;
    private volatile boolean spawnKnown;
    private volatile int spawnTeleports;
    private volatile int positionReminder;
    private volatile boolean onGround = true;
    private volatile double x;
    private volatile double y;
    private volatile double z;
    private volatile float yRot;
    private volatile float xRot;
    private volatile Vec3 lastVelocity = Vec3.ZERO;
    private volatile int lastBlockChangeSequence = -1;

    private final AtomicLong packetsIn = new AtomicLong();
    private final AtomicLong packetsOut = new AtomicLong();
    private final AtomicLong keepAlives = new AtomicLong();
    private final AtomicLong ticks = new AtomicLong();
    private final AtomicLong corrections = new AtomicLong();
    private final AtomicLong registryDataPackets = new AtomicLong();
    private final AtomicLong chunkBatchReplies = new AtomicLong();
    private final AtomicLong tagRegistriesApplied = new AtomicLong();
    private final AtomicLong unhandled = new AtomicLong();
    private final Map<String, AtomicLong> unhandledTypes = new LinkedHashMap<>();
    private final List<String> events = new ArrayList<>();

    public HeadlessBot(HeadlessTransport transport, String host, int port, String username,
                       UUID uuid, RegistryAccess.Frozen registryAccess) {
        this.transport = transport;
        this.host = host;
        this.port = port;
        this.username = username;
        this.uuid = uuid;
        this.registryAccess = registryAccess;
        this.chunks = new HeadlessChunkStore();
        this.loginListener = HeadlessPacketListeners.create(
                ClientLoginPacketListener.class, ConnectionProtocol.LOGIN, this);
        this.configurationListener = HeadlessPacketListeners.create(
                ClientConfigurationPacketListener.class, ConnectionProtocol.CONFIGURATION, this);
        this.gameListener = HeadlessPacketListeners.create(
                ClientGamePacketListener.class, ConnectionProtocol.PLAY, this);
        inventory.attach(this);
    }

    // ------------------------------------------------------------------ startup

    /** Vanilla's ConnectScreen$1 does exactly this: initiate, then send Hello. */
    public void start() {
        state = State.LOGIN;
        event("start login as " + username + " to " + host + ":" + port);
        transport.initiate(host, port, loginListener);
        send(new ServerboundHelloPacket(username, uuid));
    }

    /** Package-private so the movement driver sends through the same observable path. */
    void send(net.minecraft.network.protocol.Packet<?> packet) {
        packetsOut.incrementAndGet();
        transport.send(packet);
    }

    // ------------------------------------------------------------------ inbound

    @Override
    public void onPacket(Object packet) {
        if (packet == null) {
            return;
        }
        packetsIn.incrementAndGet();
        try {
            dispatch(packet);
        } catch (RuntimeException e) {
            event("ERROR handling " + packet.getClass().getName() + ": " + e);
            e.printStackTrace(System.err);
        }
    }

    @Override
    public void onDisconnect(String reason) {
        state = State.DISCONNECTED;
        disconnectReason = reason;
        event("disconnected: " + reason);
    }

    private void dispatch(Object p) {
        // Ostinato: 26.3 wraps entity spawn/move (and other play packets) in a bundle.
        // Without unwrapping, combat never sees AddEntity and cannot find its target.
        if (p instanceof ClientboundBundlePacket bundle) {
            for (var sub : bundle.subPackets()) {
                dispatch(sub);
            }
            return;
        }
        // ---- shared by LOGIN / CONFIGURATION / PLAY --------------------------
        if (p instanceof ClientboundKeepAlivePacket) {
            send(new ServerboundKeepAlivePacket(((ClientboundKeepAlivePacket) p).getId()));
            keepAlives.incrementAndGet();
            return;
        }
        if (p instanceof ClientboundPingPacket) {
            send(new ServerboundPongPacket(((ClientboundPingPacket) p).getId()));
            return;
        }
        if (p instanceof ClientboundUpdateTagsPacket) {
            applyTags((ClientboundUpdateTagsPacket) p);
            return;
        }
        if (p instanceof ClientboundDisconnectPacket) {
            onDisconnect("play/config disconnect: " + ((ClientboundDisconnectPacket) p).reason().getString());
            return;
        }
        if (p instanceof ClientboundResourcePackPushPacket) {
            // A headless bot never downloads a pack. Decline so the server does not wait forever.
            send(new ServerboundResourcePackPacket(((ClientboundResourcePackPushPacket) p).id(),
                    ServerboundResourcePackPacket.Action.DECLINED));
            return;
        }
        if (p instanceof ClientboundCookieRequestPacket) {
            event("cookie request ignored: " + ((ClientboundCookieRequestPacket) p).key());
            return;
        }
        if (p instanceof ClientboundStoreCookiePacket || p instanceof ClientboundTransferPacket) {
            // Transfers are deliberately ignored: the bot only ever connects to its own local server.
            event("ignored " + p.getClass().getSimpleName());
            return;
        }
        if (p instanceof ClientboundSystemChatPacket) {
            String text = ((ClientboundSystemChatPacket) p).content().getString();
            event("chat: " + text);
            // Ostinato: owner whispers and server-console system chat. Public player chat is a different packet.
            String command = HeadlessCommands.commandFromChat(text);
            if (command != null) {
                event("chat command: " + command);
                Consumer<String> sink = chatCommands;
                if (sink != null) {
                    sink.accept(command);
                }
            }
            return;
        }

        // ---- LOGIN -----------------------------------------------------------
        if (p instanceof ClientboundLoginCompressionPacket) {
            int threshold = ((ClientboundLoginCompressionPacket) p).getCompressionThreshold();
            compressionThreshold = threshold;
            transport.setupCompression(threshold, true);
            event("compression threshold " + threshold);
            return;
        }
        if (p instanceof ClientboundLoginFinishedPacket) {
            event("login finished: " + ((ClientboundLoginFinishedPacket) p).gameProfile());
            enterConfiguration(false);
            return;
        }
        if (p instanceof ClientboundHelloPacket) {
            onDisconnect("server requested encryption (online mode) - out of scope for this bot");
            return;
        }
        if (p instanceof ClientboundLoginDisconnectPacket) {
            onDisconnect("login disconnect: " + ((ClientboundLoginDisconnectPacket) p).reason().getString());
            return;
        }

        // ---- CONFIGURATION ---------------------------------------------------
        if (p instanceof ClientboundRegistryDataPacket) {
            ClientboundRegistryDataPacket data = (ClientboundRegistryDataPacket) p;
            registries.appendContents(data);
            registryDataPackets.incrementAndGet();
            event("registry data: " + data.registry().identifier() + " (" + data.entries().size() + " entries)");
            return;
        }
        if (p instanceof ClientboundSelectKnownPacks) {
            // Empty selection forces the server to send the full registry data.
            send(new ServerboundSelectKnownPacks(List.of()));
            return;
        }
        if (p instanceof ClientboundUpdateEnabledFeaturesPacket) {
            event("enabled features: " + ((ClientboundUpdateEnabledFeaturesPacket) p).features().size());
            return;
        }
        if (p instanceof ClientboundCodeOfConductPacket) {
            send(ServerboundAcceptCodeOfConductPacket.INSTANCE);
            return;
        }
        if (p instanceof ClientboundFinishConfigurationPacket) {
            enterPlay();
            return;
        }

        // ---- PLAY ------------------------------------------------------------
        if (p instanceof ClientboundLoginPacket) {
            ClientboundLoginPacket login = (ClientboundLoginPacket) p;
            playerId = login.playerId();
            dimension = login.commonPlayerSpawnInfo().dimension().identifier().toString();
            try {
                chunks.setDimension(login.commonPlayerSpawnInfo().dimensionType().value().minY(),
                        login.commonPlayerSpawnInfo().dimensionType().value().height());
            } catch (RuntimeException e) {
                event("dimension type not resolvable, keeping -64/384 default: " + e);
            }
            spawnKnown = false;
            loadedSent = false;
            event("play login id=" + playerId + " dim=" + dimension
                    + " view=" + login.chunkRadius() + " sim=" + login.simulationDistance()
                    + " secureChat=" + login.enforcesSecureChat());
            return;
        }
        if (p instanceof ClientboundPlayerPositionPacket) {
            handlePlayerPosition((ClientboundPlayerPositionPacket) p);
            return;
        }
        if (p instanceof ClientboundLevelChunkWithLightPacket) {
            chunks.loadChunk((ClientboundLevelChunkWithLightPacket) p);
            return;
        }
        if (p instanceof ClientboundSectionBlocksUpdatePacket) {
            chunks.applySectionUpdate((ClientboundSectionBlocksUpdatePacket) p);
            notePlaceWorld();
            return;
        }
        if (p instanceof ClientboundBlockUpdatePacket) {
            ClientboundBlockUpdatePacket u = (ClientboundBlockUpdatePacket) p;
            chunks.setBlockState(u.getPos(), u.getBlockState());
            notePlaceWorld();
            return;
        }
        if (p instanceof ClientboundForgetLevelChunkPacket) {
            var pos = ((ClientboundForgetLevelChunkPacket) p).pos();
            chunks.forgetChunk(pos.x(), pos.z());
            return;
        }
        if (p instanceof ClientboundChunkBatchStartPacket) {
            return;
        }
        if (p instanceof ClientboundChunkBatchFinishedPacket) {
            send(new ServerboundChunkBatchReceivedPacket(DESIRED_CHUNKS_PER_TICK));
            chunkBatchReplies.incrementAndGet();
            if (!loadedSent) {
                send(new ServerboundPlayerLoadedPacket());
                send(new ServerboundPlayerInputPacket(Input.EMPTY));
                loadedSent = true;
                event("player loaded (" + ((ClientboundChunkBatchFinishedPacket) p).batchSize()
                        + " chunks in first batch)");
            }
            return;
        }
        if (p instanceof ClientboundSetChunkCacheCenterPacket) {
            chunkCenterX = ((ClientboundSetChunkCacheCenterPacket) p).getX();
            chunkCenterZ = ((ClientboundSetChunkCacheCenterPacket) p).getZ();
            return;
        }
        if (p instanceof ClientboundSetChunkCacheRadiusPacket) {
            chunkRadius = ((ClientboundSetChunkCacheRadiusPacket) p).getRadius();
            return;
        }
        if (p instanceof ClientboundStartConfigurationPacket) {
            pathingLines.add("cancel");
            enterConfiguration(true);
            return;
        }
        if (p instanceof ClientboundRespawnPacket) {
            ClientboundRespawnPacket respawn = (ClientboundRespawnPacket) p;
            chunks.clear();
            dimension = respawn.commonPlayerSpawnInfo().dimension().identifier().toString();
            spawnKnown = false;
            loadedSent = false;
            pathingLines.add("cancel");
            event("respawn into " + dimension);
            return;
        }
        if (p instanceof ClientboundSetEntityMotionPacket) {
            ClientboundSetEntityMotionPacket motion = (ClientboundSetEntityMotionPacket) p;
            if (motion.id() == playerId) {
                lastVelocity = motion.movement();
                HeadlessMovement driver = movement;
                if (driver != null) {
                    driver.applyVelocity(lastVelocity);
                }
                if (lastVelocity.lengthSqr() > 1.0E-12) {
                    corrections.incrementAndGet();
                    event("correction velocity " + lastVelocity);
                }
            } else {
                entities.handle(p, playerId);
            }
            return;
        }
        if (p instanceof ClientboundEntityPositionSyncPacket) {
            ClientboundEntityPositionSyncPacket sync = (ClientboundEntityPositionSyncPacket) p;
            if (sync.id() == playerId) {
                Vec3 end = sync.position().endPosition();
                double d = (end.x - x) * (end.x - x) + (end.y - y) * (end.y - y)
                        + (end.z - z) * (end.z - z);
                if (d > 1.0E-12) {
                    corrections.incrementAndGet();
                    event("correction entity sync -> " + end + " (delta^2=" + d + ")");
                }
            } else {
                entities.handle(p, playerId);
            }
            return;
        }
        if (p instanceof ClientboundExplodePacket) {
            ClientboundExplodePacket boom = (ClientboundExplodePacket) p;
            if (boom.playerKnockback().isPresent()) {
                Vec3 v = boom.playerKnockback().get();
                HeadlessMovement driver = movement;
                if (driver != null) {
                    driver.applyVelocity(v);
                }
                corrections.incrementAndGet();
                event("correction explosion knockback " + v);
            }
            return;
        }
        if (p instanceof ClientboundBlockChangedAckPacket) {
            lastBlockChangeSequence = ((ClientboundBlockChangedAckPacket) p).sequence();
            notePlaceAck();
            return;
        }
        if (p instanceof ClientboundSetHealthPacket health) {
            HeadlessPlayer body = inventory.player();
            if (body != null) {
                body.setHealth(health.getHealth());
                body.getFoodData().setFoodLevel(health.getFood());
                body.getFoodData().setSaturation(health.getSaturation());
            }
            return;
        }
        if (entities.handle(p, playerId)) {
            if (p instanceof ClientboundTakeItemEntityPacket take && take.getPlayerId() == playerId) {
                // Ostinato: TakeItemEntity is the authoritative vanilla pickup signal.
                event("TakeItemEntity itemId=" + take.getItemId()
                        + " playerId=" + take.getPlayerId()
                        + " amount=" + take.getAmount());
            }
            return;
        }
        if (inventory.handle(p)) {
            return;
        }

        unhandled.incrementAndGet();
        String type = p.getClass().getSimpleName();
        synchronized (unhandledTypes) {
            unhandledTypes.computeIfAbsent(type, k -> new AtomicLong()).incrementAndGet();
        }
    }

    // ------------------------------------------------------------------ protocol switches

    /** Login finished, or play -> configuration re-entry. */
    private void enterConfiguration(boolean fromPlay) {
        state = State.CONFIGURATION;
        // Outbound is still LOGIN (or PLAY on re-entry), which is exactly what the ack packet needs.
        transport.setupInbound(ConfigurationProtocols.CLIENTBOUND, configurationListener);
        if (fromPlay) {
            send(ServerboundConfigurationAcknowledgedPacket.INSTANCE);
        } else {
            send(ServerboundLoginAcknowledgedPacket.INSTANCE);
        }
        transport.setupOutbound(ConfigurationProtocols.SERVERBOUND);
        send(new ServerboundCustomPayloadPacket(new BrandPayload("vanilla")));
        send(new ServerboundClientInformationPacket(ClientInformation.createDefault()));
        event("entered configuration" + (fromPlay ? " (re-entry)" : ""));
    }

    private void enterPlay() {
        // Configuration has delivered the dynamic registries; rebuild them common-side before the
        // first ClientboundLoginPacket, whose Holder<DimensionType> needs the dimension_type registry.
        if (registries.registryDataPackets() > 0) {
            try {
                RegistryAccess.Frozen built = registries.build();
                registryAccess = built;
                inventory.invalidateHasher();
                event("rebuilt " + built.listRegistryKeys().count() + " registries from configuration");
            } catch (RuntimeException e) {
                event("registry reconstruction FAILED: " + e + " - PLAY decode may fail");
                e.printStackTrace(System.err);
            }
        }
        var decorator = RegistryFriendlyByteBuf.decorator(registryAccess);
        var inbound = GameProtocols.CLIENTBOUND_TEMPLATE.bind(decorator);
        var outbound = GameProtocols.SERVERBOUND_TEMPLATE.bind(decorator, SURVIVAL_CONTEXT);
        state = State.PLAY;
        // Inbound first, then the terminal config packet while outbound is still CONFIGURATION.
        transport.setupInbound(inbound, gameListener);
        send(ServerboundFinishConfigurationPacket.INSTANCE);
        transport.setupOutbound(outbound);
        event("entered play");
    }

    private void handlePlayerPosition(ClientboundPlayerPositionPacket p) {
        // Vanilla's own relative/absolute resolution, including DELTA_* and ROTATE_DELTA.
        PositionMoveRotation current = new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, yRot, xRot);
        PositionMoveRotation absolute = PositionMoveRotation.calculateAbsolute(current, p.change(), p.relatives());
        Vec3 pos = absolute.position();
        x = pos.x;
        y = pos.y;
        z = pos.z;
        yRot = absolute.yRot();
        xRot = absolute.xRot();
        spawnKnown = true;
        spawnTeleports++;
        send(new ServerboundAcceptTeleportationPacket(p.id(), x, y, z, yRot, xRot));
        if (spawnTeleports > 1) {
            corrections.incrementAndGet();
            HeadlessMovement driver = movement;
            if (driver != null) {
                driver.resync(x, y, z, onGround);
            }
            event("correction teleport #" + (spawnTeleports - 1) + " id=" + p.id() + " -> " + pos);
        } else {
            event("spawn teleport id=" + p.id() + " -> " + pos);
        }
    }

    /** Common-side replacement for the client-only RegistryDataCollector tag path. */
    private void applyTags(ClientboundUpdateTagsPacket packet) {
        registries.appendTags(packet);
        tagRegistriesApplied.addAndGet(packet.tags().size());
        event("tags collected for " + packet.tags().size() + " registries");
    }

    // ------------------------------------------------------------------ tick

    /**
     * One 20 TPS client tick. Vanilla sends StatusOnly on most ticks and a position packet at
     * least every 20 ticks; doing that (rather than a position packet every tick) also guarantees
     * the server never sees two position packets inside one of its own ticks.
     */
    public void tick() {
        long n = ticks.incrementAndGet();
        transport.tick();
        maybeMarkPlayerLoaded();
        if (state != State.PLAY || !spawnKnown || !loadedSent) {
            return;
        }
        // Ostinato: pathing commands run here, before the single movement packet, so A* is only queued.
        drainPathing();
        // Ostinato: a real client ticks physics and sends a movement packet every tick, even standing
        // still. After a pillar or jump lands in pathing=idle, skipping PlayerSim left the last
        // mid-air pose frozen at onGround=false, which Grim/NCP treat as flying.
        if (movement == null) {
            ensureMovement();
        }
        HeadlessMovement driver = movement;
        if (driver != null) {
            try {
                driver.tick(this);
            } catch (RuntimeException e) {
                event("ERROR movement tick: " + e);
                e.printStackTrace(System.err);
            }
            if (n % 200 == 0) {
                event("tick " + n + " pos=" + x + "," + y + "," + z
                        + " chunks=" + chunks.chunkCount() + " corrections=" + corrections.get());
            }
            return;
        }
        positionReminder++;
        // Ostinato: the movement driver is the normal path. If we get here, still
        // do not repeat look on the 20-tick reminder — vanilla sends Pos, not PosRot.
        if (positionReminder >= POSITION_REMINDER_TICKS) {
            send(new ServerboundMovePlayerPacket.Pos(x, y, z, onGround, false));
            positionReminder = 0;
        }
        // Must be the last packet of the client tick: the server clears its per-tick movement
        // flags in ServerGamePacketListenerImpl.handleClientTickEnd.
        send(ServerboundClientTickEndPacket.INSTANCE);
        if (n % 200 == 0) {
            event("tick " + n + " pos=" + x + "," + y + "," + z
                    + " chunks=" + chunks.chunkCount() + " corrections=" + corrections.get());
        }
    }

    // ------------------------------------------------------------------ observation

    public HeadlessChunkStore chunks() {
        return chunks;
    }

    /** Attach the PlayerSim driver. Called after spawn so the sim can be seeded from the real position. */
    public void setMovement(HeadlessMovement driver) {
        this.movement = driver;
        if (live != null) {
            driver.setDriver(live);
        }
        if (spawnKnown) {
            // Ostinato: the course step overwrites this yaw before the packet; a live driver reads it first.
            driver.rotation(yRot, xRot);
            driver.start(x, y, z, onGround);
        }
        event("movement driver attached (" + driver.status().trim().replace('\n', ' ') + ")");
    }

    public HeadlessMovement movement() {
        return movement;
    }

    void setReportedPosition(double nx, double ny, double nz, boolean ground, float yaw, float pitch) {
        this.x = nx;
        this.y = ny;
        this.z = nz;
        this.onGround = ground;
        // Ostinato: the idle reminder repeats the look the last movement packet actually sent.
        this.yRot = yaw;
        this.xRot = pitch;
    }

    public int playerId() {
        return playerId;
    }

    public boolean onGround() {
        return onGround;
    }

    public float yRot() {
        return yRot;
    }

    public float xRot() {
        return xRot;
    }

    public boolean spawnKnown() {
        return spawnKnown;
    }

    // Ostinato: vanilla 26.3 (protocol 777) wraps each send with ChunkBatchStart
    // (play id 12) and ChunkBatchFinished (play id 11). Paper 26.3-252 is the same
    // protocol, but Moonrise's RegionizedPlayerChunkLoader calls
    // PlayerChunkSender.sendChunk() per column and never emits those batch
    // packets. Waiting for ChunkBatchFinished would leave loadedSent false, so
    // the pathing host never attaches and PlayerSim never starts. Send
    // PlayerLoaded after the first decoded chunk, the same way the vanilla batch
    // path does after the first finished packet.
    private void maybeMarkPlayerLoaded() {
        if (loadedSent || state != State.PLAY || !spawnKnown) {
            return;
        }
        // Do not wait for a 3x3: Moonrise may have only the spawn column so far,
        // and the vanilla batch path also fires PlayerLoaded after the first
        // finished packet rather than a full neighborhood.
        if (chunks.chunkCount() < 1) {
            return;
        }
        send(new ServerboundPlayerLoadedPacket());
        send(new ServerboundPlayerInputPacket(Input.EMPTY));
        loadedSent = true;
        event("player loaded (" + chunks.chunkCount() + " chunks, no ChunkBatchFinished)");
    }

    public boolean playerLoaded() {
        return loadedSent;
    }

    public boolean hostAttached() {
        return live != null;
    }

    /** Play session with a confirmed spawn. Chunks may still be arriving. */
    public boolean hostReady() {
        return state == State.PLAY && spawnKnown;
    }

    public int lastBlockChangeSequence() {
        return lastBlockChangeSequence;
    }

    /** The survival inventory mirror. Packets received before the host exists are queued on it. */
    public HeadlessInventory inventory() {
        return inventory;
    }

    RegistryAccess.Frozen registryAccess() {
        return registryAccess;
    }

    HeadlessLiveDriver liveDriver() {
        return live;
    }

    private void notePlaceWorld() {
        HeadlessLiveDriver current = live;
        if (current != null) {
            current.noteWorldChange();
        }
    }

    private void notePlaceAck() {
        HeadlessLiveDriver current = live;
        if (current != null) {
            current.noteAck();
        }
    }

    /** Where stdin, commands.txt, and accepted whispers deliver operator lines that are not pathing. */
    public void setChatCommandListener(Consumer<String> listener) {
        this.chatCommands = listener;
    }

    /** Queue a goto / cancel / mine line for the tick thread. Never plans on the caller. */
    public void enqueuePathing(String line) {
        pathingLines.add(line);
    }

    public String pathingStatus() {
        HeadlessLiveDriver current = live;
        return current == null ? "not attached" : current.status();
    }

    /**
     * Ostinato: build the pathing body from the live connection. Idempotent. Movement is attached
     * on the next tick so idle physics keep running.
     */
    public void ensureHost() {
        if (live != null) {
            return;
        }
        synchronized (hostLock) {
            if (live != null) {
                return;
            }
            if (state != State.PLAY || !spawnKnown) {
                throw new IllegalStateException("pathing host requires a spawned play session");
            }
            HeadlessLevel level = HeadlessLevel.create(chunks, registryAccess);
            HeadlessPlayer player = new HeadlessPlayer(level, new GameProfile(uuid, username));
            player.setPos(x, y, z);
            player.setYRot(yRot);
            player.setXRot(xRot);
            player.setOnGround(onGround);
            inventory.bind(player);
            HeadlessLiveDriver created = new HeadlessLiveDriver(this, player, level);
            if (movement != null) {
                movement.setDriver(created);
            }
            live = created;
            event("pathing host attached at " + x + "," + y + "," + z);
        }
    }

    public void closeHost() {
        HeadlessLiveDriver current = live;
        if (current != null) {
            current.close();
        }
    }

    private void drainPathing() {
        String line;
        while ((line = pathingLines.poll()) != null) {
            try {
                HeadlessCommands.Parsed parsed = HeadlessCommands.parse(line);
                if (parsed == null) {
                    continue;
                }
                if (parsed.error() != null) {
                    System.err.println("[HeadlessBot] " + parsed.error());
                    event("pathing command rejected: " + parsed.error());
                    continue;
                }
                applyPathing(parsed);
            } catch (RuntimeException e) {
                System.err.println("[HeadlessBot] pathing command failed: " + e.getMessage());
                event("pathing command failed: " + e.getMessage());
            }
        }
    }

    private void applyPathing(HeadlessCommands.Parsed parsed) {
        switch (parsed.kind()) {
            case CANCEL -> {
                if (live != null) {
                    live.cancel();
                    event("pathing cancelled");
                }
            }
            case GOTO -> {
                ensureHost();
                ensureMovement();
                live.sync(movement);
                live.gotoBlock(parsed.x(), parsed.y(), parsed.z());
                event("goto " + parsed.x() + " " + parsed.y() + " " + parsed.z());
                System.out.println("[HeadlessPathing] goto " + parsed.x() + " " + parsed.y() + " " + parsed.z());
            }
            case MINE -> {
                ensureHost();
                ensureMovement();
                live.sync(movement);
                live.mine(parsed.text());
                event("mine " + parsed.text());
                System.out.println("[HeadlessPathing] mine " + parsed.text() + " " + live.status());
            }
            case PLACE -> {
                ensureHost();
                ensureMovement();
                live.sync(movement);
                live.place(parsed.x(), parsed.y(), parsed.z());
                event("place " + parsed.x() + " " + parsed.y() + " " + parsed.z());
                System.out.println("[HeadlessPlace] place " + parsed.x() + " " + parsed.y() + " " + parsed.z());
            }
            case KILL -> {
                ensureHost();
                ensureMovement();
                live.sync(movement);
                live.kill(parsed.text());
                event("kill " + (parsed.text().isEmpty() ? "(monsters)" : parsed.text()));
                System.out.println("[HeadlessCombat] kill " + (parsed.text().isEmpty() ? "(monsters)" : parsed.text()));
            }
            case CRAFT, SMELT, CHEST, DROP, PICKUP, EQUIP -> {
                ensureHost();
                ensureMovement();
                live.sync(movement);
                live.container(parsed);
                event(parsed.kind().name().toLowerCase() + " " + parsed.text());
            }
            default -> System.err.println("[HeadlessBot] not a pathing command: " + parsed.kind());
        }
    }

    private void ensureMovement() {
        if (movement == null) {
            setMovement(new HeadlessMovement(new HeadlessBlockStateInterface(chunks), List.of()));
        } else if (live != null) {
            movement.setDriver(live);
            if (!movement.started() && spawnKnown) {
                movement.rotation(yRot, xRot);
                movement.start(x, y, z, onGround);
            }
        }
    }

    public HeadlessEntityTracker entities() {
        return entities;
    }

    public State state() {
        return state;
    }

    public String disconnectReason() {
        return disconnectReason;
    }

    public boolean isConnected() {
        return state != State.DISCONNECTED && transport.isConnected();
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public long corrections() {
        return corrections.get();
    }

    public long ticks() {
        return ticks.get();
    }

    public long packetsIn() {
        return packetsIn.get();
    }

    public long packetsOut() {
        return packetsOut.get();
    }

    public String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("state=").append(state)
                .append(" user=").append(username)
                .append(" connected=").append(isConnected())
                .append(" disconnectReason=").append(disconnectReason)
                .append("\nplayerId=").append(playerId)
                .append(" dimension=").append(dimension)
                .append(" pos=").append(x).append(',').append(y).append(',').append(z)
                .append(" rot=").append(yRot).append(',').append(xRot)
                .append(" onGround=").append(onGround)
                .append("\ncompression=").append(compressionThreshold)
                .append(" chunkCenter=").append(chunkCenterX).append(',').append(chunkCenterZ)
                .append(" chunkRadius=").append(chunkRadius)
                .append("\nticks=").append(ticks.get())
                .append(" packetsIn=").append(packetsIn.get())
                .append(" packetsOut=").append(packetsOut.get())
                .append(" keepAlives=").append(keepAlives.get())
                .append(" corrections=").append(corrections.get())
                .append(" spawnTeleports=").append(spawnTeleports)
                .append("\nregistryDataPackets=").append(registryDataPackets.get())
                .append(" tagRegistriesApplied=").append(tagRegistriesApplied.get())
                .append(" chunkBatchReplies=").append(chunkBatchReplies.get())
                .append("\nchunks=").append(chunks.chunkCount())
                .append(" loadedChunks=").append(chunks.loadedChunks())
                .append(" decodedSections=").append(chunks.decodedSections())
                .append(" blockUpdates=").append(chunks.blockUpdates())
                .append(" forgotten=").append(chunks.forgottenChunks())
                .append(" minSection=").append(chunks.minSection())
                .append(" expectedSections=").append(chunks.expectedSections())
                .append("\nunhandled=").append(unhandled.get()).append(' ');
        synchronized (unhandledTypes) {
            sb.append(new LinkedHashMap<>(unhandledTypes));
        }
        sb.append("\nevents:\n");
        synchronized (events) {
            int from = Math.max(0, events.size() - 25);
            for (int i = from; i < events.size(); i++) {
                sb.append("  ").append(events.get(i)).append('\n');
            }
        }
        sb.append("pathing: ").append(pathingStatus()).append('\n');
        HeadlessMovement driver = movement;
        if (driver != null) {
            sb.append("movement:\n").append(driver.status());
            List<String> lines = driver.log();
            int from = Math.max(0, lines.size() - 60);
            for (int i = from; i < lines.size(); i++) {
                sb.append("  ").append(lines.get(i)).append('\n');
            }
        }
        return sb.toString();
    }

    void event(String text) {
        synchronized (events) {
            events.add(text);
        }
        System.out.println("[HeadlessBot " + username + "] " + text);
    }
}
