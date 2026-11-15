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
import java.util.function.Predicate;

import static org.junit.Assert.*;

public class HeadlessMovementTest {

    private static RegistryAccess.Frozen access;

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        access = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    /** A flat stone floor at y=-61 inside seven-by-five decoded chunks, enough for 250+ ticks of walking. */
    private static HeadlessBlockStateInterface flatWorld() {
        HeadlessChunkStore store = new HeadlessChunkStore();
        store.setDimension(-64, 384);
        for (int cx = -2; cx <= 2; cx++) {
            for (int cz = -2; cz <= 4; cz++) {
                store.createEmptyChunk(cx, cz);
            }
        }
        for (int x = -32; x <= 32; x++) {
            for (int z = -32; z <= 60; z++) {
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
        // Walk keeps yaw 0, so only the first packet carries look (PosRot); the rest are Pos.
        assertEquals(1, transport.count(ServerboundMovePlayerPacket.PosRot.class));
        assertEquals(9, transport.count(ServerboundMovePlayerPacket.Pos.class));
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

    /** Flat floor plus a bottom-slab ledge (top at -59.5, a 0.5 step within PlayerSim's STEP=0.6) from z=8. */
    private static HeadlessBlockStateInterface stepUpWorld() {
        HeadlessBlockStateInterface bsi = flatWorld();
        for (int x = -1; x <= 1; x++) {
            for (int z = 8; z <= 60; z++) {
                bsi.store().setBlockState(new BlockPos(x, -60, z), Blocks.STONE_SLAB.defaultBlockState());
            }
        }
        return bsi;
    }

    /**
     * A {@link PlayerSim.World} that delegates everything to a real BsiWorld but overrides fluid and
     * climbable detection with caller-supplied predicates. The plain-JVM unit test has no datapack tags
     * (those arrive from the server's configuration phase), so this pins the sim's water/ladder
     * *physics* deterministically without depending on BlockTags.CLIMBABLE / FluidTags.WATER being bound.
     */
    private static final class ForcedWorld implements PlayerSim.World {
        private final PlayerSim.World base;
        private final Predicate<BlockPos> water;
        private final Predicate<BlockPos> climb;

        ForcedWorld(PlayerSim.World base, Predicate<BlockPos> water,
                    Predicate<BlockPos> climb) {
            this.base = base;
            this.water = water;
            this.climb = climb;
        }

        @Override
        public void collect(double a, double b, double c, double d, double e, double f, List<double[]> o) {
            base.collect(a, b, c, d, e, f, o);
        }

        @Override
        public float slipperiness(int x, int y, int z) {
            return base.slipperiness(x, y, z);
        }

        @Override
        public float bounce(int x, int y, int z) {
            return base.bounce(x, y, z);
        }

        @Override
        public float speedFactor(int x, int y, int z) {
            return base.speedFactor(x, y, z);
        }

        @Override
        public float jumpFactor(int x, int y, int z) {
            return base.jumpFactor(x, y, z);
        }

        @Override
        public boolean bouncy(int x, int y, int z) {
            return base.bouncy(x, y, z);
        }

        @Override
        public boolean sticky(int x, int y, int z) {
            return base.sticky(x, y, z);
        }

        @Override
        public boolean cellBoxes(int x, int y, int z, List<double[]> o) {
            return base.cellBoxes(x, y, z, o);
        }

        @Override
        public boolean water(int x, int y, int z) {
            return water.test(new BlockPos(x, y, z));
        }

        @Override
        public boolean climbable(int x, int y, int z) {
            return climb.test(new BlockPos(x, y, z));
        }
    }

    @Test
    public void stepUpClimbsOneBlockAndContinues() {
        PlayerSim sim = new PlayerSim(new BsiWorld(stepUpWorld()));
        sim.x = 0.5;
        sim.y = -60.0;
        sim.z = 0.5;
        sim.onGround = true;
        for (int i = 0; i < 250; i++) {
            sim.tick(0f, 1, 0, false, false); // walk +z into the ledge
        }
        // A 0.5-block step-up must land the player on the slab ledge (top -59.5) and let them keep going.
        assertTrue("player should be on the slab ledge (y near -59.5), got y=" + sim.y,
                sim.y > -60.0 && sim.onGround);
        assertTrue("player should have advanced well past the ledge start, z=" + sim.z, sim.z > 9.0);
    }

    @Test
    public void ladderClimbLiftsThePlayer() {
        // Force the ladder column climbable (no datapack tags in the unit test) over open air, wide enough
        // in z that the player's slow forward drift stays within it for the whole climb.
        PlayerSim.World forced = new ForcedWorld(new BsiWorld(flatWorld()),
                bp -> false,
                bp -> bp.getX() >= -1 && bp.getX() <= 1 && bp.getZ() >= 8 && bp.getZ() <= 13
                        && bp.getY() >= -60 && bp.getY() <= -30);
        PlayerSim sim = new PlayerSim(forced);
        sim.x = 0.5;
        sim.y = -60.0;
        sim.z = 8.0; // standing inside the climbable column
        sim.onGround = true;
        assertTrue("player's feet cell must be climbable", sim.climbable());
        double startY = sim.y;
        for (int i = 0; i < 30; i++) {
            sim.tick(0f, 1, 0, false, true); // press into the ladder + jump
        }
        assertTrue("climbing a ladder must raise y, start=" + startY + " end=" + sim.y, sim.y > startY + 0.5);
    }

    @Test
    public void stillWaterLetsThePlayerSwim() {
        // Force a still-water pool (no datapack tags in the unit test); the region is open air so the
        // player can occupy it. This pins travelInWater's horizontal swim response.
        PlayerSim.World forced = new ForcedWorld(new BsiWorld(flatWorld()),
                bp -> bp.getY() >= -61 && bp.getY() <= -58 && bp.getX() >= -1 && bp.getX() <= 1
                        && bp.getZ() >= 0 && bp.getZ() <= 20,
                bp -> false);
        PlayerSim sim = new PlayerSim(forced);
        sim.x = 0.5;
        sim.y = -59.0;
        sim.z = 0.5;
        sim.onGround = false; // submerged
        assertTrue("feet must be in forced water", sim.inWater());
        for (int i = 0; i < 30; i++) {
            sim.tickWater(0f, 0f, true, false, false, false); // horizontal swim, pitch 0
        }
        assertTrue("still water must keep the player submerged", sim.inWater() || sim.eyesInWater());
        assertTrue("swimming forward must advance z, z=" + sim.z, sim.z > 0.6);
    }

    @Test
    public void liveDriverPausesTheCourseWithoutSkippingOrDoubleSending() {
        RecordingTransport transport = new RecordingTransport();
        HeadlessBot bot = new HeadlessBot(transport, "127.0.0.1", 25570, "HBot1",
                UUID.randomUUID(), access);
        HeadlessMovement movement = new HeadlessMovement(flatWorld(), HeadlessMovement.parse(List.of(
                "10 0 1 0 0 0 0 #walk")));
        int[] holds = {3};
        movement.setDriver((ignored, current) -> {
            if (holds[0] > 0) {
                holds[0]--;
                return new HeadlessMovement.Step(1, 90f, 0f, 0, 0, false, false, false, "hold");
            }
            return null;
        });
        movement.start(0.5, -60.0, 0.5, true);
        for (int i = 0; i < 13; i++) {
            int before = transport.sent.size();
            movement.tick(bot);
            int movementPackets = 0;
            int tickEnds = 0;
            for (int s = before; s < transport.sent.size(); s++) {
                Packet<?> packet = transport.sent.get(s);
                if (packet instanceof ServerboundMovePlayerPacket) {
                    movementPackets++;
                }
                if (packet instanceof ServerboundClientTickEndPacket) {
                    tickEnds++;
                }
            }
            // Standing still with an unchanged look sends no movement packet; ClientTickEnd still ends the tick.
            assertTrue("tick " + i + " must send at most one movement packet", movementPackets <= 1);
            assertEquals("tick " + i + " must send one ClientTickEnd", 1, tickEnds);
            assertTrue(transport.sent.get(transport.sent.size() - 1) instanceof ServerboundClientTickEndPacket);
            if (i < 3) {
                assertEquals("pathing must not consume the course", 0, movement.courseStepTick());
            }
        }
        assertTrue(movement.finished());
        assertTrue("walk ticks still report their positions, packets=" + movement.positionPackets(),
                movement.positionPackets() >= 11);
        assertEquals(13, transport.count(ServerboundClientTickEndPacket.class));
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
