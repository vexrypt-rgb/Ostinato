/*
 * Ostinato: Phase 2 headless movement tests. New file, replaces no Baritone code.
 *
 * Proves that the bot's decoded chunk store can back the real BlockStateInterface, that BsiWorld +
 * PlayerSim run against it in a plain JVM, and that the driver emits exactly one position packet
 * and one ClientTickEnd per tick using vanilla packet classes.
 */
package baritone.headless;

import baritone.pathing.kinematic.BsiWorld;
import baritone.pathing.kinematic.PlayerSim;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

public class HeadlessMovementTest {

    private static RegistryAccess.Frozen access;

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        access = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    /** A flat stone floor at y=-61 inside five by five decoded chunks, exactly like the live flat world. */
    private static HeadlessBlockStateInterface flatWorld() {
        HeadlessChunkStore store = new HeadlessChunkStore();
        store.setDimension(-64, 384);
        for (int cx = -2; cx <= 2; cx++) {
            for (int cz = -2; cz <= 2; cz++) {
                store.createEmptyChunk(cx, cz);
            }
        }
        for (int x = -32; x <= 32; x++) {
            for (int z = -32; z <= 32; z++) {
                assertTrue("floor cell must be inside a decoded chunk",
                        store.setBlockState(new BlockPos(x, -61, z), Blocks.STONE.defaultBlockState()));
            }
        }
        return new HeadlessBlockStateInterface(store);
    }

    @Test
    public void chunkStoreBacksTheRealBlockStateInterface() {
        HeadlessBlockStateInterface bsi = flatWorld();
        assertEquals(-64, bsi.minY());
        assertEquals(384, bsi.height());
        assertEquals(Blocks.STONE.defaultBlockState(), bsi.get0(0, -61, 0));
        assertEquals(Blocks.AIR.defaultBlockState(), bsi.get0(0, -60, 0));
        assertTrue(bsi.isLoaded(0, 0));
        assertTrue(bsi.isKnown(0, 0));
        assertTrue(bsi.worldContainsLoadedChunk(0, 0));
        assertFalse("an undecoded chunk is not loaded", bsi.isKnown(500, 500));
        assertEquals("unloaded cells must be passable air, not VOID_AIR",
                Blocks.AIR.defaultBlockState(), bsi.get0(5000, -60, 5000));
        assertEquals("out of world height is air", Blocks.AIR.defaultBlockState(), bsi.get0(0, 500, 0));
    }

    @Test
    public void playerSimStandsOnTheChunkStoreFloorAndWalks() {
        PlayerSim sim = new PlayerSim(new BsiWorld(flatWorld()));
        sim.x = 0.5;
        sim.y = -60.0;
        sim.z = 0.5;
        sim.onGround = true;
        for (int i = 0; i < 40; i++) {
            sim.tick(0f, 1, 0, false, false);
        }
        assertEquals("must stay on the floor", -60.0, sim.y, 1.0E-9);
        assertTrue("must be supported", sim.onGround);
        double moved = Math.hypot(sim.x - 0.5, sim.z - 0.5);
        assertTrue("walking must cover ground, moved=" + moved, moved > 2.0);
        assertFalse("flat ground must not trip horizontal collision", sim.collidedH);
    }

    @Test
    public void courseParserReadsEveryIntent() {
        List<HeadlessMovement.Step> steps = HeadlessMovement.parse(List.of(
                "# a comment",
                "20 0 1 0 0 0 0 #walk",
                "",
                "10 90 -1 1 1 1 0 #sprint-jump-back-left"));
        assertEquals(2, steps.size());
        assertEquals(20, steps.get(0).ticks);
        assertEquals("walk", steps.get(0).label);
        assertFalse(steps.get(0).sprint);
        assertEquals(90f, steps.get(1).yaw, 0f);
        assertEquals(-1, steps.get(1).forward);
        assertEquals(1, steps.get(1).strafe);
        assertTrue(steps.get(1).sprint);
        assertTrue(steps.get(1).jump);
        assertEquals("sprint-jump-back-left", steps.get(1).label);
        try {
            HeadlessMovement.parse(List.of("20 0 1 0"));
            fail("a short course line must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("course line 1"));
        }
    }

    @Test
    public void sprintCommandIsBuiltThroughTheVanillaCodec() {
        ServerboundPlayerCommandPacket packet = HeadlessMovement.playerCommand(
                42, ServerboundPlayerCommandPacket.Action.START_SPRINTING);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ServerboundPlayerCommandPacket.STREAM_CODEC.encode(buf, packet);
            ServerboundPlayerCommandPacket decoded = ServerboundPlayerCommandPacket.STREAM_CODEC.decode(buf);
            assertEquals(42, decoded.getId());
            assertEquals(ServerboundPlayerCommandPacket.Action.START_SPRINTING, decoded.getAction());
            assertEquals(0, decoded.getData());
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    public void onePositionPacketAndOneClientTickEndPerSimulatedTick() {
        RecordingTransport transport = new RecordingTransport();
        HeadlessBot bot = new HeadlessBot(transport, "127.0.0.1", 25570, "HBot1",
                UUID.randomUUID(), access);
        HeadlessMovement driver = new HeadlessMovement(flatWorld(), HeadlessMovement.parse(List.of(
                "5 0 1 0 0 0 0 #walk",
                "5 0 1 0 1 1 0 #sprint-jump")));
        driver.start(0.5, -60.0, 0.5, true);
        for (int i = 0; i < 10; i++) {
            driver.tick(bot);
        }
        assertEquals(10, driver.simTicks());
        assertEquals(10, driver.positionPackets());
        assertEquals(10, transport.count(ServerboundMovePlayerPacket.PosRot.class));
        assertEquals(10, transport.count(ServerboundClientTickEndPacket.class));
        assertEquals("input is sent only on change", 2, transport.count(ServerboundPlayerInputPacket.class));
        assertEquals("sprint state is sent only on change", 1,
                transport.count(ServerboundPlayerCommandPacket.class));
        assertEquals("the bot reports exactly the sim position", driver.sim().x, bot.x(), 0.0);
        assertEquals(driver.sim().y, bot.y(), 0.0);
        assertEquals(driver.sim().z, bot.z(), 0.0);
        assertTrue("course must be exhausted", driver.finished());
        assertEquals("sim and last reported position agree to 1e-6",
                0.0, Math.hypot(driver.sim().x - driver.reportedX(),
                        Math.hypot(driver.sim().y - driver.reportedY(), driver.sim().z - driver.reportedZ())),
                1.0E-6);
    }

    @Test
    public void aServerCorrectionResyncsTheSim() {
        HeadlessMovement driver = new HeadlessMovement(flatWorld(), HeadlessMovement.parse(List.of(
                "20 0 1 0 0 0 0 #walk")));
        driver.start(0.5, -60.0, 0.5, true);
        driver.sim().vx = 3;
        driver.sim().vy = -2;
        driver.resync(10.25, 70.5, -3.75, false);
        assertEquals(10.25, driver.sim().x, 0.0);
        assertEquals(70.5, driver.sim().y, 0.0);
        assertEquals(-3.75, driver.sim().z, 0.0);
        assertEquals(0.0, driver.sim().vx, 0.0);
        assertEquals(0.0, driver.sim().vy, 0.0);
        assertFalse(driver.sim().onGround);
        assertEquals(1, driver.resyncs());
        assertEquals(10.25, driver.reportedX(), 0.0);
    }

    private static final class RecordingTransport implements HeadlessTransport {
        final List<Packet<?>> sent = new ArrayList<>();

        @Override
        public void initiate(String host, int port, ClientLoginPacketListener loginListener) {
        }

        @Override
        public void send(Packet<?> packet) {
            sent.add(packet);
        }

        @Override
        public void setupInbound(ProtocolInfo<?> info, net.minecraft.network.PacketListener listener) {
        }

        @Override
        public void setupOutbound(ProtocolInfo<?> info) {
        }

        @Override
        public void setupCompression(int threshold, boolean rejectBadPackets) {
        }

        @Override
        public void tick() {
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        int count(Class<?> type) {
            int n = 0;
            for (Packet<?> p : sent) {
                if (type.isInstance(p)) {
                    n++;
                }
            }
            return n;
        }
    }
}
