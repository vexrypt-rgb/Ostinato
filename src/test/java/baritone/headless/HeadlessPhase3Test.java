/*
 * Ostinato: Phase 3 headless pathing checks. No Minecraft client and no dedicated server.
 */
package baritone.headless;

import baritone.Baritone;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.IPlayerController;
import com.mojang.authlib.GameProfile;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HeadlessPhase3Test {

    private static RegistryAccess.Frozen access;
    private boolean allowPlace;
    private boolean allowParkour;
    private boolean allowParkourPlace;
    private boolean allowGodBridge;
    private boolean unifiedExecutor;

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        access = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    @Before
    public void saveSettings() {
        var settings = Baritone.settings();
        allowPlace = settings.allowPlace.value;
        allowParkour = settings.allowParkour.value;
        allowParkourPlace = settings.allowParkourPlace.value;
        allowGodBridge = settings.allowGodBridge.value;
        unifiedExecutor = settings.unifiedExecutor.value;
    }

    @After
    public void restoreSettings() {
        var settings = Baritone.settings();
        settings.allowPlace.value = allowPlace;
        settings.allowParkour.value = allowParkour;
        settings.allowParkourPlace.value = allowParkourPlace;
        settings.allowGodBridge.value = allowGodBridge;
        settings.unifiedExecutor.value = unifiedExecutor;
    }

    @Test
    public void localPlayerIsNullForAHeadlessBody() {
        HeadlessChunkStore store = new HeadlessChunkStore();
        HeadlessLevel level = HeadlessLevel.create(store, access);
        HeadlessPlayer player = new HeadlessPlayer(level, new GameProfile(UUID.randomUUID(), "HBot1"));
        RecordingTransport transport = new RecordingTransport();
        HeadlessBot bot = new HeadlessBot(transport, "127.0.0.1", 25565, "HBot1", player.getUUID(), access);
        HeadlessPlayerController controller = new HeadlessPlayerController(bot, player, level);
        HeadlessPlayerContext context = new HeadlessPlayerContext(player, level, controller);
        assertNull(context.minecraft());
        assertNull(context.localPlayer());
        assertTrue(context.entity() == player);

        IPlayerContext viaDefault = new IPlayerContext() {
            @Override
            public Minecraft minecraft() {
                return null;
            }

            @Override
            public Player entity() {
                return player;
            }

            @Override
            public IPlayerController playerController() {
                return controller;
            }

            @Override
            public Level world() {
                return level;
            }

            @Override
            public baritone.api.cache.IWorldData worldData() {
                return null;
            }

            @Override
            public HitResult objectMouseOver() {
                return null;
            }

            @Override
            public baritone.api.utils.BetterBlockPos viewerPos() {
                return null;
            }
        };
        assertNull("a common-side Player must not be cast to LocalPlayer", viaDefault.localPlayer());
        // HeadlessPlayer and LocalPlayer are sibling Player types, so a direct instanceof is a compile error.
        assertFalse("headless body is not a client LocalPlayer", ((Object) player) instanceof LocalPlayer);
    }

    @Test
    public void destroyStateMachineStartsOnceThenStopsAndWaitsForAir() {
        HeadlessChunkStore store = new HeadlessChunkStore();
        store.setDimension(0, 32);
        store.createEmptyChunk(0, 0);
        BlockPos pos = new BlockPos(0, 2, 0);
        assertTrue(store.setBlockState(pos, Blocks.STONE.defaultBlockState()));
        HeadlessLevel level = HeadlessLevel.create(store, access);
        HeadlessPlayer player = new HeadlessPlayer(level, new GameProfile(UUID.randomUUID(), "HBot1"));
        player.setOnGround(true);
        RecordingTransport transport = new RecordingTransport();
        HeadlessBot bot = new HeadlessBot(transport, "127.0.0.1", 25565, "HBot1", player.getUUID(), access);
        HeadlessPlayerController controller = new HeadlessPlayerController(bot, player, level);

        boolean broke = false;
        // Grounded empty-hand stone is about 1/150 per tick (hardness 1.5, wrong-tool divisor 100).
        // Airborne mining is five times slower; the live driver copies onGround from PlayerSim.
        for (int i = 0; i < 200 && controller.stopPackets() == 0; i++) {
            broke = controller.onPlayerDamageBlock(pos, Direction.UP);
        }
        assertEquals(1, controller.startPackets());
        assertEquals(1, controller.stopPackets());
        assertEquals(0, controller.abortPackets());
        assertTrue(broke);
        List<ServerboundPlayerActionPacket> actions = actions(transport);
        assertEquals(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, actions.get(0).getAction());
        assertEquals(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, actions.get(1).getAction());
        assertEquals(pos, actions.get(0).getPos());
        assertTrue(actions.get(1).getSequence() > actions.get(0).getSequence());

        assertTrue(store.setBlockState(pos, Blocks.AIR.defaultBlockState()));
        assertFalse("air alone is not confirmation", controller.lastBreakAcked());
        bot.onPacket(new ClientboundBlockChangedAckPacket(controller.lastBrokenSequence()));
        assertTrue(controller.lastBreakAcked());

        int aborts = (int) controller.abortPackets();
        controller.resetBlockRemoving();
        assertEquals("a finished STOP must not be aborted", aborts, controller.abortPackets());
    }

    @Test
    public void resetAbortsAnInProgressBreak() {
        HeadlessChunkStore store = new HeadlessChunkStore();
        store.setDimension(0, 32);
        store.createEmptyChunk(0, 0);
        BlockPos pos = new BlockPos(1, 2, 0);
        store.setBlockState(pos, Blocks.OBSIDIAN.defaultBlockState());
        HeadlessLevel level = HeadlessLevel.create(store, access);
        HeadlessPlayer player = new HeadlessPlayer(level, new GameProfile(UUID.randomUUID(), "HBot1"));
        HeadlessBot bot = new HeadlessBot(new RecordingTransport(), "127.0.0.1", 25565, "HBot1",
                player.getUUID(), access);
        HeadlessPlayerController controller = new HeadlessPlayerController(bot, player, level);
        controller.onPlayerDamageBlock(pos, Direction.UP);
        assertEquals(1, controller.startPackets());
        assertEquals(0, controller.stopPackets());
        controller.resetBlockRemoving();
        assertEquals(1, controller.abortPackets());
        controller.resetBlockRemoving();
        assertEquals(1, controller.abortPackets());
    }

    @Test
    public void syncCopiesTheSimBodyBeforePathingReadsIt() {
        HeadlessChunkStore store = new HeadlessChunkStore();
        store.setDimension(0, 32);
        HeadlessLevel level = HeadlessLevel.create(store, access);
        HeadlessPlayer player = new HeadlessPlayer(level, new GameProfile(UUID.randomUUID(), "HBot1"));
        HeadlessBot bot = new HeadlessBot(new RecordingTransport(), "127.0.0.1", 25565, "HBot1",
                player.getUUID(), access);
        HeadlessLiveDriver driver = new HeadlessLiveDriver(bot, player, level);
        try {
            HeadlessMovement movement = new HeadlessMovement(new HeadlessBlockStateInterface(store), List.of());
            movement.rotation(90f, -12f);
            movement.start(4.25, 2.0, -3.5, true);
            movement.sim().vx = 0.2;
            movement.sim().vy = -0.08;
            movement.sim().vz = 0.05;
            movement.sim().onGround = true;
            movement.sim().sprinting = true;
            driver.sync(movement);
            assertEquals(4.25, player.getX(), 0.0);
            assertEquals(2.0, player.getY(), 0.0);
            assertEquals(-3.5, player.getZ(), 0.0);
            assertEquals(0.2, player.getDeltaMovement().x, 1.0E-9);
            assertEquals(-0.08, player.getDeltaMovement().y, 1.0E-9);
            assertEquals(0.05, player.getDeltaMovement().z, 1.0E-9);
            assertTrue(player.onGround());
            assertTrue(player.isSprinting());
            assertEquals(90f, player.getYRot(), 0f);
            assertEquals(-12f, player.getXRot(), 0f);
            assertEquals(net.minecraft.world.entity.Pose.STANDING, player.getPose());
        } finally {
            driver.close();
        }
    }

    @Test
    public void gotoPlansOnTheWorkerAndCancelKeepsTickingPhysics() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        HeadlessBot bot = new HeadlessBot(transport, "127.0.0.1", 25565, "HBot1",
                UUID.randomUUID(), access);
        bot.start();
        bot.onPacket(new ClientboundLoginFinishedPacket(
                new GameProfile(UUID.randomUUID(), "HBot1"), UUID.randomUUID()));
        bot.onPacket(ClientboundFinishConfigurationPacket.INSTANCE);
        bot.chunks().setDimension(0, 32);
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                bot.chunks().createEmptyChunk(cx, cz);
            }
        }
        for (int x = -16; x <= 16; x++) {
            for (int z = -16; z <= 16; z++) {
                bot.chunks().setBlockState(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
                bot.chunks().setBlockState(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
            }
        }
        bot.onPacket(new ClientboundPlayerPositionPacket(1,
                new PositionMoveRotation(new Vec3(0.5, 2.0, 0.5), Vec3.ZERO, 0f, 0f), Set.of()));
        bot.onPacket(new ClientboundChunkBatchFinishedPacket(1));
        transport.sent.clear();

        for (int i = 0; i < 40; i++) {
            tickOnce(bot, transport);
        }
        assertFalse(bot.hostAttached());
        assertEquals("idle must not repeat look", 1, transport.count(ServerboundMovePlayerPacket.PosRot.class));
        assertEquals(0, transport.count(ServerboundMovePlayerPacket.Rot.class));
        assertEquals(40, transport.count(ServerboundClientTickEndPacket.class));

        bot.enqueuePathing("goto 0 2 4");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        String status = "";
        try {
            while (System.nanoTime() < deadline) {
                tickOnce(bot, transport);
                status = bot.pathingStatus();
                double moved = Math.hypot(bot.x() - 0.5, bot.z() - 0.5);
                if (status.contains("arrived") || status.contains("no path") || status.contains("failed")
                        || moved > 1.0) {
                    break;
                }
                Thread.sleep(20);
            }
            assertTrue("host did not attach: " + status, bot.hostAttached());
            assertFalse("planner did not produce a walk: " + status,
                    status.startsWith("pathing=planning") || status.contains("no path") || status.contains("failed"));
            bot.enqueuePathing("cancel");
            int before = transport.sent.size();
            tickOnce(bot, transport);
            ServerboundMovePlayerPacket movement = null;
            for (int i = before; i < transport.sent.size(); i++) {
                if (transport.sent.get(i) instanceof ServerboundMovePlayerPacket packet) {
                    movement = packet;
                }
            }
            assertTrue("cancel must still end the client tick",
                    transport.sent.get(transport.sent.size() - 1) instanceof ServerboundClientTickEndPacket);
            assertTrue("cancel movement if any is a MovePlayerPacket, got " + movement,
                    movement == null || movement instanceof ServerboundMovePlayerPacket);
            assertTrue(bot.pathingStatus().contains("pathing=idle"));
            assertTrue("idle physics must stay on the floor after cancel, y=" + bot.y()
                    + " onGround=" + bot.onGround(), bot.onGround() && bot.y() >= 1.9 && bot.y() <= 2.1);
        } finally {
            bot.closeHost();
        }
    }

    private static void tickOnce(HeadlessBot bot, RecordingTransport transport) {
        int before = transport.sent.size();
        bot.tick();
        int moves = 0;
        int ends = 0;
        for (int i = before; i < transport.sent.size(); i++) {
            Packet<?> packet = transport.sent.get(i);
            if (packet instanceof ServerboundMovePlayerPacket) {
                moves++;
            }
            if (packet instanceof ServerboundClientTickEndPacket) {
                ends++;
            }
        }
        assertTrue("at most one movement packet per tick, got " + moves, moves <= 1);
        assertEquals("one ClientTickEnd per tick", 1, ends);
        assertTrue(transport.sent.get(transport.sent.size() - 1) instanceof ServerboundClientTickEndPacket);
    }

    private static List<ServerboundPlayerActionPacket> actions(RecordingTransport transport) {
        List<ServerboundPlayerActionPacket> out = new ArrayList<>();
        for (Packet<?> packet : transport.sent) {
            if (packet instanceof ServerboundPlayerActionPacket action) {
                out.add(action);
            }
        }
        assertTrue(out.size() >= 2);
        return out;
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
        public void setupInbound(net.minecraft.network.ProtocolInfo<?> info, net.minecraft.network.PacketListener listener) {
        }

        @Override
        public void setupOutbound(net.minecraft.network.ProtocolInfo<?> info) {
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
            for (Packet<?> packet : sent) {
                if (type.isInstance(packet)) {
                    n++;
                }
            }
            return n;
        }
    }
}
