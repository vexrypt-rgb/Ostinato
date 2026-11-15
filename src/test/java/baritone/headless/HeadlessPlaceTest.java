/*
 * Ostinato: Phase 3b inventory mirror and block placement. No Minecraft client and no dedicated server.
 */
package baritone.headless;

import baritone.Baritone;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.movements.MovementParkour;
import baritone.utils.BlockStateInterface;
import com.mojang.authlib.GameProfile;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
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
import static org.junit.Assert.assertTrue;

public class HeadlessPlaceTest {

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
        // Bootstrap leaves item components unbound, so a dirt stack cannot be constructed until this runs.
        if (!Items.DIRT.builtInRegistryHolder().areComponentsBound()) {
            HeadlessRegistries.bindDefaultComponents(VanillaRegistries.createWorldLookup());
        }
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
    public void setContentAndSetSlotFillHotbarAndBackpack() {
        HeadlessPlayer player = player();
        HeadlessInventory inventory = new HeadlessInventory();
        inventory.bind(player);
        int slots = player.inventoryMenu.slots.size();
        assertTrue(slots > InventoryMenu.USE_ROW_SLOT_START);
        assertTrue(slots > InventoryMenu.INV_SLOT_START);

        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < slots; i++) {
            items.add(ItemStack.EMPTY);
        }
        items.set(InventoryMenu.USE_ROW_SLOT_START, new ItemStack(Items.DIRT, 32));
        items.set(InventoryMenu.INV_SLOT_START, new ItemStack(Items.COBBLESTONE, 7));
        assertTrue(inventory.handle(new ClientboundContainerSetContentPacket(
                InventoryMenu.CONTAINER_ID, 2, items, ItemStack.EMPTY)));
        assertEquals(Items.DIRT, player.getInventory().getItem(0).getItem());
        assertEquals(32, player.getInventory().getItem(0).getCount());
        assertEquals(Items.COBBLESTONE, player.getInventory().getItem(InventoryMenu.INV_SLOT_START).getItem());
        assertEquals(7, player.getInventory().getItem(InventoryMenu.INV_SLOT_START).getCount());

        assertTrue(inventory.handle(new ClientboundContainerSetSlotPacket(
                InventoryMenu.CONTAINER_ID, 3, InventoryMenu.USE_ROW_SLOT_START + 1,
                new ItemStack(Items.COBBLESTONE, 4))));
        assertEquals(4, player.getInventory().getItem(1).getCount());
        assertEquals(Items.COBBLESTONE, player.getInventory().getItem(1).getItem());

        assertTrue(inventory.handle(new ClientboundSetHeldSlotPacket(1)));
        assertEquals(1, player.getInventory().getSelectedSlot());

        assertTrue(inventory.handle(new ClientboundSetPlayerInventoryPacket(4, new ItemStack(Items.DIRT, 3))));
        assertEquals(3, player.getInventory().getItem(4).getCount());

        assertFalse(inventory.handle(new ClientboundContainerSetSlotPacket(
                2, 4, 0, new ItemStack(Items.DIRT, 9))));
        assertEquals(32, player.getInventory().getItem(0).getCount());
    }

    @Test
    public void packetsBeforeTheHostFlushOnBindAndThrowawaySeesDirt() {
        RecordingTransport transport = new RecordingTransport();
        HeadlessBot bot = spawned(transport, 0.5, 2.0, 0.5);
        try {
            bot.onPacket(new ClientboundContainerSetSlotPacket(
                    InventoryMenu.CONTAINER_ID, 1, InventoryMenu.USE_ROW_SLOT_START, new ItemStack(Items.DIRT, 20)));
            assertFalse(bot.hostAttached());
            bot.ensureHost();
            HeadlessPlayer player = bot.liveDriver().player();
            assertEquals(Items.DIRT, player.getInventory().getItem(0).getItem());
            assertEquals(20, player.getInventory().getItem(0).getCount());
            assertTrue(bot.liveDriver().baritone().hasGenericThrowaway());

            player.getInventory().setItem(0, ItemStack.EMPTY);
            player.getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 5));
            assertTrue(bot.liveDriver().baritone().hasGenericThrowaway());
            assertTrue(bot.liveDriver().baritone().selectThrowawayForLocation(false, 0, 1, 0));
            assertFalse(bot.liveDriver().baritone().selectThrowawayForLocation(true, 0, 1, 0));
            assertEquals(0, transport.count(ServerboundSetCarriedItemPacket.class));
        } finally {
            bot.closeHost();
        }
    }

    @Test
    public void selectThrowawaySendsSetCarriedItemOnce() {
        RecordingTransport transport = new RecordingTransport();
        HeadlessBot bot = new HeadlessBot(transport, "127.0.0.1", 25565, "HBot1", UUID.randomUUID(), access);
        HeadlessChunkStore store = new HeadlessChunkStore();
        store.setDimension(0, 32);
        store.createEmptyChunk(0, 0);
        HeadlessLevel level = HeadlessLevel.create(store, access);
        HeadlessPlayer player = new HeadlessPlayer(level, new GameProfile(UUID.randomUUID(), "HBot1"));
        HeadlessPlayerController controller = new HeadlessPlayerController(bot, player, level);
        HeadlessPlayerContext context = new HeadlessPlayerContext(player, level, controller);
        HeadlessBaritone baritone = new HeadlessBaritone(context, new HeadlessBlockStateInterface(store),
                new HeadlessInputOverrideHandler(), new HeadlessLookBehavior(player));
        player.getInventory().setItem(3, new ItemStack(Items.DIRT, 5));
        assertTrue(baritone.hasGenericThrowaway());
        assertTrue(baritone.selectThrowawayForLocation(true, 1, 1, 0));
        assertEquals(3, player.getInventory().getSelectedSlot());
        List<ServerboundSetCarriedItemPacket> carried = packets(transport, ServerboundSetCarriedItemPacket.class);
        assertEquals(1, carried.size());
        assertEquals(3, carried.get(0).getSlot());
        controller.syncHeldItem();
        baritone.selectThrowawayForLocation(true, 1, 1, 0);
        assertEquals(1, transport.count(ServerboundSetCarriedItemPacket.class));
    }

    @Test
    public void useItemOnCarriesFaceHitAndSequenceAndRollsBackOnReject() {
        RecordingTransport transport = new RecordingTransport();
        HeadlessBot bot = new HeadlessBot(transport, "127.0.0.1", 25565, "HBot1", UUID.randomUUID(), access);
        HeadlessChunkStore store = new HeadlessChunkStore();
        store.setDimension(0, 32);
        store.createEmptyChunk(0, 0);
        BlockPos support = new BlockPos(1, 0, 0);
        BlockPos dest = new BlockPos(1, 1, 0);
        store.setBlockState(support, Blocks.STONE.defaultBlockState());
        HeadlessLevel level = HeadlessLevel.create(store, access);
        HeadlessPlayer player = new HeadlessPlayer(level, new GameProfile(UUID.randomUUID(), "HBot1"));
        player.getInventory().setItem(0, new ItemStack(Items.DIRT, 10));
        player.getInventory().setSelectedSlot(0);
        HeadlessPlayerController controller = new HeadlessPlayerController(bot, player, level);
        BlockHitResult hit = new BlockHitResult(new Vec3(1.5, 1.0, 0.5), Direction.UP, support, false);

        controller.processRightClickBlock(player, level, InteractionHand.MAIN_HAND, hit);
        ServerboundUseItemOnPacket use = packets(transport, ServerboundUseItemOnPacket.class).get(0);
        assertEquals(InteractionHand.MAIN_HAND, use.hand());
        assertEquals(support, use.hitResult().getBlockPos());
        assertEquals(Direction.UP, use.hitResult().getDirection());
        assertEquals(1.5, use.hitResult().getLocation().x, 1.0E-6);
        assertEquals(1.0, use.hitResult().getLocation().y, 1.0E-6);
        assertEquals(0.5, use.hitResult().getLocation().z, 1.0E-6);
        assertFalse(use.hitResult().isInside());
        assertEquals(1, use.sequence());
        assertEquals(9, player.getInventory().getItem(0).getCount());
        assertTrue("prediction must not write the chunk", level.getBlockState(dest).isAir());
        assertEquals(dest, controller.lastPlaced());
        assertEquals(1, controller.lastPlacedSequence());

        controller.clickBlock(support, Direction.UP);
        ServerboundPlayerActionPacket started = packets(transport, ServerboundPlayerActionPacket.class).get(0);
        assertEquals(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, started.getAction());
        assertTrue(started.getSequence() > use.sequence());

        bot.onPacket(new ClientboundBlockChangedAckPacket(controller.lastPlacedSequence()));
        controller.pollPlace();
        assertEquals(10, player.getInventory().getItem(0).getCount());
        assertTrue(controller.lastPlaceFailed());
        assertTrue(controller.placeRejected(dest));
        assertFalse(controller.placeConfirmed(dest));
        assertTrue(level.getBlockState(dest).isAir());
    }

    @Test
    public void blockUpdateConfirmsAndALateUpdateAfterRollbackReapplies() {
        RecordingTransport transport = new RecordingTransport();
        HeadlessBot bot = new HeadlessBot(transport, "127.0.0.1", 25565, "HBot1", UUID.randomUUID(), access);
        HeadlessChunkStore store = new HeadlessChunkStore();
        store.setDimension(0, 32);
        store.createEmptyChunk(0, 0);
        BlockPos support = new BlockPos(1, 0, 0);
        BlockPos dest = new BlockPos(1, 1, 0);
        store.setBlockState(support, Blocks.STONE.defaultBlockState());
        HeadlessLevel level = HeadlessLevel.create(store, access);
        HeadlessPlayer player = new HeadlessPlayer(level, new GameProfile(UUID.randomUUID(), "HBot1"));
        player.getInventory().setItem(0, new ItemStack(Items.DIRT, 10));
        HeadlessPlayerController controller = new HeadlessPlayerController(bot, player, level);
        BlockHitResult hit = new BlockHitResult(new Vec3(1.5, 1.0, 0.5), Direction.UP, support, false);
        controller.processRightClickBlock(player, level, InteractionHand.MAIN_HAND, hit);
        assertEquals(9, player.getInventory().getItem(0).getCount());

        store.setBlockState(dest, Blocks.DIRT.defaultBlockState());
        controller.pollPlace();
        assertTrue(controller.placeConfirmed(dest));
        assertTrue(controller.lastPlaceAcked());
        assertFalse(controller.lastPlaceFailed());
        assertEquals(9, player.getInventory().getItem(0).getCount());

        // A different block at the cell is a rejection, and the chunk is left as the server wrote it.
        store.setBlockState(dest, Blocks.AIR.defaultBlockState());
        player.getInventory().setItem(0, new ItemStack(Items.DIRT, 10));
        controller.processRightClickBlock(player, level, InteractionHand.MAIN_HAND, hit);
        assertEquals(9, player.getInventory().getItem(0).getCount());
        store.setBlockState(dest, Blocks.STONE.defaultBlockState());
        bot.onPacket(new ClientboundBlockChangedAckPacket(controller.lastPlacedSequence()));
        controller.pollPlace();
        assertEquals(10, player.getInventory().getItem(0).getCount());
        assertTrue(level.getBlockState(dest).is(Blocks.STONE));
        assertTrue(controller.placeRejected(dest));

        store.setBlockState(dest, Blocks.DIRT.defaultBlockState());
        controller.pollPlace();
        assertEquals(9, player.getInventory().getItem(0).getCount());
        assertTrue(controller.placeConfirmed(dest));
        assertFalse(controller.lastPlaceFailed());
    }

    @Test
    public void placeTimesOutAndRestoresTheStack() {
        HeadlessBot bot = new HeadlessBot(new RecordingTransport(), "127.0.0.1", 25565, "HBot1", UUID.randomUUID(), access);
        HeadlessChunkStore store = new HeadlessChunkStore();
        store.setDimension(0, 32);
        store.createEmptyChunk(0, 0);
        store.setBlockState(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState());
        HeadlessLevel level = HeadlessLevel.create(store, access);
        HeadlessPlayer player = new HeadlessPlayer(level, new GameProfile(UUID.randomUUID(), "HBot1"));
        player.getInventory().setItem(0, new ItemStack(Items.DIRT, 2));
        HeadlessPlayerController controller = new HeadlessPlayerController(bot, player, level);
        controller.processRightClickBlock(player, level, InteractionHand.MAIN_HAND,
                new BlockHitResult(new Vec3(1.5, 1.0, 0.5), Direction.UP, new BlockPos(1, 0, 0), false));
        assertEquals(1, player.getInventory().getItem(0).getCount());
        for (int i = 0; i < HeadlessPlayerController.PLACE_TIMEOUT_TICKS - 1; i++) {
            controller.tickPendingPlace();
        }
        assertEquals(1, player.getInventory().getItem(0).getCount());
        controller.tickPendingPlace();
        assertEquals(2, player.getInventory().getItem(0).getCount());
        assertTrue(controller.lastPlaceFailed());
        assertTrue(level.getBlockState(new BlockPos(1, 1, 0)).isAir());
    }

    @Test
    public void placeCommandAimsThenClicksAndConfirms() {
        RecordingTransport transport = new RecordingTransport();
        HeadlessBot bot = spawned(transport, 0.5, 1.0, 0.5);
        try {
            bot.chunks().setBlockState(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
            bot.chunks().setBlockState(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState());
            bot.onPacket(new ClientboundContainerSetSlotPacket(
                    InventoryMenu.CONTAINER_ID, 1, InventoryMenu.USE_ROW_SLOT_START, new ItemStack(Items.DIRT, 16)));
            bot.ensureHost();
            var settings = Baritone.settings();
            assertTrue(settings.allowPlace.value);
            assertTrue(settings.allowParkour.value);
            assertTrue(settings.allowParkourPlace.value);
            assertFalse(settings.allowGodBridge.value);
            assertFalse(settings.unifiedExecutor.value);

            bot.enqueuePathing("place 1 1 0");
            ServerboundUseItemOnPacket use = null;
            for (int i = 0; i < 6 && use == null; i++) {
                bot.tick();
                List<ServerboundUseItemOnPacket> found = packets(transport, ServerboundUseItemOnPacket.class);
                if (!found.isEmpty()) {
                    use = found.get(0);
                }
            }
            assertNotNull("place did not send UseItemOn: " + bot.pathingStatus(), use);
            assertEquals(new BlockPos(1, 0, 0), use.hitResult().getBlockPos());
            assertEquals(Direction.UP, use.hitResult().getDirection());
            assertEquals(1.5, use.hitResult().getLocation().x, 1.0E-6);
            assertEquals(1.0, use.hitResult().getLocation().y, 1.0E-6);
            assertEquals(0.5, use.hitResult().getLocation().z, 1.0E-6);
            assertEquals(1, use.sequence());
            assertEquals(1, transport.count(ServerboundSetCarriedItemPacket.class));
            assertEquals(15, bot.liveDriver().player().getInventory().getItem(0).getCount());

            BlockPos dest = new BlockPos(1, 1, 0);
            bot.onPacket(new ClientboundBlockUpdatePacket(dest, Blocks.DIRT.defaultBlockState()));
            bot.onPacket(new ClientboundBlockChangedAckPacket(use.sequence()));
            assertTrue(bot.liveDriver().controller().placeConfirmed(dest));
            assertEquals(15, bot.liveDriver().player().getInventory().getItem(0).getCount());
            bot.tick();
            assertTrue(bot.pathingStatus(), bot.pathingStatus().contains("placed"));
        } finally {
            bot.closeHost();
        }
    }

    @Test
    public void placeCommandRefusesOutOfReachAndAnEmptyHand() {
        RecordingTransport transport = new RecordingTransport();
        HeadlessBot bot = spawned(transport, 0.5, 1.0, 0.5);
        try {
            bot.chunks().createEmptyChunk(2, 0);
            bot.chunks().setBlockState(new BlockPos(40, 0, 0), Blocks.STONE.defaultBlockState());
            bot.onPacket(new ClientboundContainerSetSlotPacket(
                    InventoryMenu.CONTAINER_ID, 1, InventoryMenu.USE_ROW_SLOT_START, new ItemStack(Items.DIRT, 8)));
            bot.enqueuePathing("place 40 1 0");
            bot.tick();
            assertTrue(bot.pathingStatus(), bot.pathingStatus().contains("out of reach"));
            assertEquals(0, transport.count(ServerboundUseItemOnPacket.class));
            assertEquals(8, bot.liveDriver().player().getInventory().getItem(0).getCount());

            bot.liveDriver().player().getInventory().setItem(0, ItemStack.EMPTY);
            bot.chunks().setBlockState(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState());
            bot.enqueuePathing("place 1 1 0");
            bot.tick();
            assertTrue(bot.pathingStatus(), bot.pathingStatus().contains("no throwaway"));
            assertEquals(0, transport.count(ServerboundUseItemOnPacket.class));
        } finally {
            bot.closeHost();
        }
    }

    @Test
    public void bridgeAndPillarPlanAPlacement() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        HeadlessBot bot = spawned(transport, 1.5, 2.0, 0.5);
        try {
            for (int x = 0; x <= 2; x++) {
                bot.chunks().setBlockState(new BlockPos(x, 1, 0), Blocks.STONE.defaultBlockState());
            }
            for (int x = 8; x <= 10; x++) {
                bot.chunks().setBlockState(new BlockPos(x, 1, 0), Blocks.STONE.defaultBlockState());
            }
            bot.onPacket(new ClientboundContainerSetSlotPacket(
                    InventoryMenu.CONTAINER_ID, 1, InventoryMenu.USE_ROW_SLOT_START, new ItemStack(Items.DIRT, 64)));
            bot.enqueuePathing("goto 9 2 0");
            String status = waitUntilPlanned(bot);
            assertFalse("bridge did not plan: " + status, status.contains("no path") || status.contains("failed")
                    || status.contains("planning"));
            CalculationContext context = bot.liveDriver().baritone().secretInternalGetCalculationContext();
            assertNotNull(context);
            assertTrue(context.hasThrowaway);
            assertTrue(context.allowParkour);
            assertTrue("parkour-place must be plannable", context.allowParkourPlace);
            assertFalse("god-bridge stays off", context.allowGodBridge);
            assertTrue("bridge path has no placement: " + status + " | " + describePath(bot), pathPlaces(bot));

            bot.enqueuePathing("cancel");
            bot.tick();
            for (int x = 0; x <= 10; x++) {
                bot.chunks().setBlockState(new BlockPos(x, 1, 0), Blocks.AIR.defaultBlockState());
            }
            bot.chunks().setBlockState(new BlockPos(4, 1, 4), Blocks.STONE.defaultBlockState());
            // Feet on the single stone, five blocks up. Planning reads the sim body, not a stray setPos.
            bot.movement().resync(4.5, 2.0, 4.5, true);
            bot.enqueuePathing("goto 4 7 4");
            String pillar = waitUntilPlanned(bot);
            assertFalse("pillar did not plan: " + pillar, pillar.contains("no path") || pillar.contains("failed")
                    || pillar.contains("planning"));
            assertTrue("pillar path has no placement: " + pillar + " | " + describePath(bot), pathPlaces(bot));
        } finally {
            bot.closeHost();
        }
    }

    /**
     * A sneak-bridge, pillar, or parkour-place landing shows up in {@link Movement#toPlace}.
     * A 5-block gap with a floor on the far side is one parkour extension: the landing already
     * exists, so {@code toPlace} is empty and the block laid at the edge is {@link MovementParkour#extensions}.
     */
    private static boolean pathPlaces(HeadlessBot bot) {
        IPathExecutor executor = bot.liveDriver().baritone().getPathingBehavior().getCurrent();
        if (executor == null) {
            return false;
        }
        BlockStateInterface bsi = bot.liveDriver().baritone().bsi();
        for (IMovement movement : executor.getPath().movements()) {
            if (movement instanceof Movement placed && !placed.toPlace(bsi).isEmpty()) {
                return true;
            }
            if (movement instanceof MovementParkour parkour && parkour.extensions() > 0) {
                return true;
            }
        }
        return false;
    }

    private static String describePath(HeadlessBot bot) {
        IPathExecutor executor = bot.liveDriver().baritone().getPathingBehavior().getCurrent();
        if (executor == null) {
            return "no executor";
        }
        BlockStateInterface bsi = bot.liveDriver().baritone().bsi();
        StringBuilder out = new StringBuilder();
        for (IMovement movement : executor.getPath().movements()) {
            if (!out.isEmpty()) {
                out.append("; ");
            }
            out.append(movement.getClass().getSimpleName())
                    .append(' ').append(movement.getSrc()).append("->").append(movement.getDest());
            if (movement instanceof Movement placed && !placed.toPlace(bsi).isEmpty()) {
                out.append(" place=").append(placed.toPlace(bsi));
            }
            if (movement instanceof MovementParkour parkour) {
                out.append(" ext=").append(parkour.extensions());
            }
        }
        return out.toString();
    }

    private static String waitUntilPlanned(HeadlessBot bot) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        String status = "";
        while (System.nanoTime() < deadline) {
            bot.tick();
            status = bot.pathingStatus();
            if (status.contains("executing") || status.contains("no path") || status.contains("failed")
                    || status.contains("arrived")) {
                return status;
            }
            Thread.sleep(20);
        }
        return status;
    }

    private static HeadlessPlayer player() {
        HeadlessChunkStore store = new HeadlessChunkStore();
        store.setDimension(0, 32);
        store.createEmptyChunk(0, 0);
        HeadlessLevel level = HeadlessLevel.create(store, access);
        return new HeadlessPlayer(level, new GameProfile(UUID.randomUUID(), "HBot1"));
    }

    /** Login through the first chunk batch so {@code tick} will run a place command. */
    private static HeadlessBot spawned(RecordingTransport transport, double x, double y, double z) {
        HeadlessBot bot = new HeadlessBot(transport, "127.0.0.1", 25565, "HBot1", UUID.randomUUID(), access);
        bot.start();
        bot.onPacket(new ClientboundLoginFinishedPacket(new GameProfile(UUID.randomUUID(), "HBot1"), UUID.randomUUID()));
        bot.onPacket(ClientboundFinishConfigurationPacket.INSTANCE);
        bot.chunks().setDimension(0, 32);
        bot.chunks().createEmptyChunk(0, 0);
        bot.onPacket(new ClientboundPlayerPositionPacket(1,
                new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, 0f, 0f), Set.of()));
        bot.onPacket(new ClientboundChunkBatchFinishedPacket(1));
        transport.sent.clear();
        return bot;
    }

    private static <T> List<T> packets(RecordingTransport transport, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Packet<?> packet : transport.sent) {
            if (type.isInstance(packet)) {
                out.add(type.cast(packet));
            }
        }
        return out;
    }

    private static final class RecordingTransport implements HeadlessTransport {
        final List<Packet<?>> sent = new ArrayList<>();

        @Override
        public void initiate(String host, int port, net.minecraft.network.protocol.login.ClientLoginPacketListener loginListener) {
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
