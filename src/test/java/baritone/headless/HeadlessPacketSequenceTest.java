/*
 * Ostinato: Phase 1 packet-sequence state machine test. New file, replaces no Baritone code.
 *
 * The fake server speaks vanilla: every clientbound packet is encoded and decoded with the real
 * ProtocolInfo codec for the current state and then delivered through Packet.handle(listener) -
 * exactly what Connection.genericsFtw does. Every serverbound packet the bot emits is encoded and
 * decoded with the real outbound codec, so sending a packet in the wrong protocol state fails here
 * instead of only failing against a live server.
 */
package baritone.headless;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.Assert.*;

public class HeadlessPacketSequenceTest {

    private static RegistryAccess.Frozen access;

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        access = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    private FakeServer server;
    private HeadlessBot bot;

    @Before
    public void setup() {
        server = new FakeServer(access);
        bot = new HeadlessBot(server, "127.0.0.1", 25565, "HBot1",
                UUID.nameUUIDFromBytes("HBot1".getBytes()), access);
    }

    @Test
    public void handshakeHelloAndCompression() {
        bot.start();
        assertEquals(HeadlessBot.State.LOGIN, bot.state());
        assertEquals("127.0.0.1", server.initiateHost);
        assertEquals(25565, server.initiatePort);
        assertTrue("initiate must receive a ClientLoginPacketListener",
                server.initiateListener instanceof ClientLoginPacketListener);
        assertEquals(PacketFlow.CLIENTBOUND, ((ClientLoginPacketListener) server.initiateListener).flow());
        assertEquals(ConnectionProtocol.LOGIN, ((ClientLoginPacketListener) server.initiateListener).protocol());
        assertTrue(((ClientLoginPacketListener) server.initiateListener).isAcceptingMessages());

        assertEquals(1, server.count(ServerboundHelloPacket.class));
        ServerboundHelloPacket hello = server.first(ServerboundHelloPacket.class);
        assertEquals("HBot1", hello.name());
        assertNotNull(hello.profileId());

        server.deliver(new ClientboundLoginCompressionPacket(256));
        assertEquals(256, server.compressionThreshold);
    }

    @Test
    public void loginToConfigurationToPlayUsesTheExactVanillaOrder() {
        bot.start();
        server.deliver(new ClientboundLoginCompressionPacket(256));
        server.deliver(new ClientboundLoginFinishedPacket(
                new GameProfile(UUID.randomUUID(), "HBot1"), UUID.randomUUID()));

        assertEquals(HeadlessBot.State.CONFIGURATION, bot.state());
        server.assertOrder(
                ServerboundHelloPacket.class,
                ServerboundLoginAcknowledgedPacket.class);
        assertEquals(ConnectionProtocol.CONFIGURATION, server.inbound.id());
        assertEquals(ConnectionProtocol.CONFIGURATION, server.outbound.id());
        // Known packs is a *reply* to ClientboundSelectKnownPacks, not an entry packet.
        assertEquals(0, server.count(ServerboundSelectKnownPacks.class));
        // Brand + ClientInformation were emitted with the configuration codec.
        assertEquals(2, server.customPayloadAndInfo);

        server.deliver(new ClientboundSelectKnownPacks(List.of()));
        assertEquals(1, server.count(ServerboundSelectKnownPacks.class));
        assertTrue("empty selection forces full registry data",
                server.first(ServerboundSelectKnownPacks.class).knownPacks().isEmpty());
        server.deliver(new ClientboundUpdateTagsPacket(Map.of()));

        server.deliver(ClientboundFinishConfigurationPacket.INSTANCE);
        assertEquals(HeadlessBot.State.PLAY, bot.state());
        assertEquals(ConnectionProtocol.PLAY, server.inbound.id());
        assertEquals(ConnectionProtocol.PLAY, server.outbound.id());
        assertTrue(server.sentAfterInboundPlay.contains(ServerboundFinishConfigurationPacket.INSTANCE));
    }

    @Test
    public void playHandlesTeleportKeepAliveChunksAndPlayerLoaded() {
        toPlay();

        server.deliver(new ClientboundPlayerPositionPacket(17,
                new PositionMoveRotation(new Vec3(12.5, 70.0, -3.5), Vec3.ZERO, 90.0f, 0.0f),
                Set.of()));
        ServerboundAcceptTeleportationPacket ack = server.first(ServerboundAcceptTeleportationPacket.class);
        assertEquals(17, ack.id());
        assertEquals(12.5, ack.x(), 1e-9);
        assertEquals(70.0, ack.y(), 1e-9);
        assertEquals(-3.5, ack.z(), 1e-9);
        assertEquals(90.0f, ack.yRot(), 1e-6f);
        assertEquals(12.5, bot.x(), 1e-9);
        assertEquals(0L, bot.corrections());

        server.deliver(new ClientboundKeepAlivePacket(987654321L));
        assertEquals(987654321L, server.first(ServerboundKeepAlivePacket.class).getId());
        server.deliver(new ClientboundPingPacket(4242));
        assertEquals(4242, server.first(ServerboundPongPacket.class).getId());

        server.deliver(new ClientboundChunkBatchFinishedPacket(5));
        assertEquals(1, server.count(ServerboundChunkBatchReceivedPacket.class));
        assertEquals(1, server.count(ServerboundPlayerLoadedPacket.class));
        assertEquals(1, server.count(ServerboundPlayerInputPacket.class));
        assertEquals(64.0f, server.first(ServerboundChunkBatchReceivedPacket.class).desiredChunksPerTick(), 1e-6f);

        // A second spawn teleport is a server correction, not a respawn.
        server.deliver(new ClientboundPlayerPositionPacket(18,
                new PositionMoveRotation(new Vec3(12.5, 70.0, -3.5), Vec3.ZERO, 90.0f, 0.0f),
                Set.of()));
        assertEquals(1L, bot.corrections());
    }

    @Test
    public void onePositionPacketAndClientTickEndEveryTick() {
        toPlay();
        server.deliver(new ClientboundPlayerPositionPacket(1,
                new PositionMoveRotation(new Vec3(0, 64, 0), Vec3.ZERO, 0, 0), Set.of()));
        server.deliver(new ClientboundChunkBatchFinishedPacket(1));
        server.clear();

        for (int i = 0; i < 40; i++) {
            bot.tick();
        }
        // Ostinato: once spawned, PlayerSim ticks every tick and ClientTickEnd ends
        // every client tick. Idle look is not repeated: vanilla sends PosRot once
        // (to establish look), then Pos only on the 20-tick reminder.
        int posRot = server.count(ServerboundMovePlayerPacket.PosRot.class);
        int pos = server.count(ServerboundMovePlayerPacket.Pos.class);
        assertEquals("idle must not repeat look, posRot=" + posRot + " pos=" + pos, 1, posRot);
        assertEquals(0, server.count(ServerboundMovePlayerPacket.Rot.class));
        assertEquals(40, server.count(ServerboundClientTickEndPacket.class));
    }

    @Test
    public void listenerProxyAnswersTheContractForAllThreePhases() {
        bot.start();
        assertNotNull(server.inbound);
        assertEquals(ConnectionProtocol.LOGIN, server.inbound.id());
        assertTrue(server.listener.isAcceptingMessages());
        assertEquals(PacketFlow.CLIENTBOUND, server.listener.flow());
        assertEquals(ConnectionProtocol.LOGIN, server.listener.protocol());

        toPlay();
        assertEquals(ConnectionProtocol.PLAY, server.listener.protocol());
        assertEquals(PacketFlow.CLIENTBOUND, server.listener.flow());
        assertTrue(server.listener.isAcceptingMessages());
    }

    private void toPlay() {
        if (bot.state() == HeadlessBot.State.PLAY) {
            return;
        }
        bot.start();
        server.deliver(new ClientboundLoginFinishedPacket(
                new GameProfile(UUID.randomUUID(), "HBot1"), UUID.randomUUID()));
        server.deliver(ClientboundFinishConfigurationPacket.INSTANCE);
    }

    // ------------------------------------------------------------------ fake server

    private static final class FakeServer implements HeadlessTransport {

        final RegistryAccess.Frozen access;
        final List<Packet<?>> sent = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<Object> sentAfterInboundPlay = new ArrayList<>();

        String initiateHost;
        int initiatePort;
        ClientLoginPacketListener initiateListener;
        ProtocolInfo<?> inbound = LoginProtocols.CLIENTBOUND;
        ProtocolInfo<?> outbound = LoginProtocols.SERVERBOUND;
        PacketListener listener;
        int compressionThreshold = -1;
        int customPayloadAndInfo;

        FakeServer(RegistryAccess.Frozen access) {
            this.access = access;
        }

        @Override
        public void initiate(String host, int port, ClientLoginPacketListener loginListener) {
            initiateHost = host;
            initiatePort = port;
            initiateListener = loginListener;
            inbound = LoginProtocols.CLIENTBOUND;
            listener = loginListener;
            outbound = LoginProtocols.SERVERBOUND;
            // The real initiate also sends the handshake packet; verify it with vanilla's codec.
            events.add("initiate");
        }

        @Override
        public void send(Packet<?> packet) {
            // Encode + decode with the *current outbound protocol codec*. A packet sent in the wrong
            // state (e.g. a config packet after the outbound switch to PLAY) throws here.
            ByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), access);
            encode(outbound, buf, packet);
            Packet<?> decoded = decode(outbound, buf);
            assertEquals("codec round trip changed the packet type", packet.getClass(), decoded.getClass());
            assertEquals("codec round trip left bytes unread", 0, buf.readableBytes());
            sent.add(packet);
            if (packet instanceof net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
                    || packet instanceof net.minecraft.network.protocol.common.ServerboundClientInformationPacket) {
                customPayloadAndInfo++;
            }
            if (inbound != null && inbound.id() == ConnectionProtocol.PLAY && sentAfterInboundPlay.isEmpty()) {
                sentAfterInboundPlay.add(packet);
            }
            events.add("send " + packet.getClass().getSimpleName());
        }

        @Override
        public void setupInbound(ProtocolInfo<?> info, PacketListener newListener) {
            inbound = info;
            listener = newListener;
            events.add("inbound " + info.id());
            if (info.id() == ConnectionProtocol.PLAY) {
                sentAfterInboundPlay.clear();
            }
        }

        @Override
        public void setupOutbound(ProtocolInfo<?> info) {
            outbound = info;
            events.add("outbound " + info.id());
        }

        @Override
        public void setupCompression(int threshold, boolean rejectBadPackets) {
            compressionThreshold = threshold;
            events.add("compression " + threshold);
        }

        @Override
        public void tick() {
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        /** Fake server -> bot, through the real clientbound codec and Packet.handle(listener). */
        @SuppressWarnings("rawtypes")
        void deliver(Packet<?> packet) {
            ByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), access);
            encode(inbound, buf, packet);
            Packet decoded = decode(inbound, buf);
            assertEquals("clientbound codec round trip changed the packet type",
                    packet.getClass(), decoded.getClass());
            assertEquals("clientbound codec round trip left bytes unread", 0, buf.readableBytes());
            decoded.handle(listener);
        }

        <T extends Packet<?>> int count(Class<T> type) {
            int n = 0;
            for (Packet<?> p : sent) {
                if (type.isInstance(p)) {
                    n++;
                }
            }
            return n;
        }

        @SuppressWarnings("unchecked")
        <T> T first(Class<T> type) {
            for (Packet<?> p : sent) {
                if (type.isInstance(p)) {
                    return (T) p;
                }
            }
            fail("no packet of type " + type.getSimpleName() + " in " + events);
            return null;
        }

        void clear() {
            sent.clear();
            events.clear();
            sentAfterInboundPlay.clear();
        }

        void assertOrder(Class<?>... expected) {
            int at = 0;
            for (Packet<?> p : sent) {
                if (at < expected.length && expected[at].isInstance(p)) {
                    at++;
                }
            }
            assertEquals("expected serverbound order " + java.util.Arrays.toString(expected)
                    + " in " + events, expected.length, at);
        }

        /** Every n-th packet in the recorded sequence must be a ClientTickEnd. */
        void assertEveryNth(int n, Class<?> type) {
            Predicate<Packet<?>> isType = type::isInstance;
            int ticks = 0;
            for (int i = 0; i < sent.size(); i++) {
                if (isType.test(sent.get(i))) {
                    assertTrue("ClientTickEnd at unexpected index " + i + " in " + events,
                            (i + 1) % n == 0);
                    ticks++;
                }
            }
            assertTrue(ticks > 0);
        }
    }

    /** Raw StreamCodec calls let the test pass a Packet<?> without generic gymnastics. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void encode(ProtocolInfo info, ByteBuf buf, Packet packet) {
        ((net.minecraft.network.codec.StreamCodec) info.codec()).encode(buf, packet);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Packet decode(ProtocolInfo info, ByteBuf buf) {
        return (Packet) ((net.minecraft.network.codec.StreamCodec) info.codec()).decode(buf);
    }
}
