/*
 * Ostinato: Phase 3b inventory mirror and block placement. No Minecraft client and no dedicated server.
 */
package baritone.headless;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.api.utils.PathCalculationResult;
import baritone.pathing.calc.AStarPathFinder;
import baritone.api.pathing.path.IPathExecutor;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.movements.MovementParkour;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import baritone.utils.pathing.Favoring;
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
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
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
        HeadlessBot.resetSharedStateForTests();
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
        assertTrue("prediction must write the block into the PlayerSim world", level.getBlockState(dest).is(Blocks.DIRT));
        assertFalse("the local write is not a server confirmation", controller.placeConfirmed(dest));
        assertFalse(controller.lastPlaceAcked());
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
        assertTrue("prediction writes dirt immediately", level.getBlockState(dest).is(Blocks.DIRT));
        controller.pollPlace();
        assertFalse("shown predicted state is not an ack", controller.placeConfirmed(dest));

        assertTrue(controller.absorbServerBlockUpdate(dest, Blocks.DIRT.defaultBlockState()));
        assertTrue("absorb keeps the predicted world block", level.getBlockState(dest).is(Blocks.DIRT));
        controller.pollPlace();
        assertFalse(controller.placeConfirmed(dest));

        bot.onPacket(new ClientboundBlockChangedAckPacket(controller.lastPlacedSequence()));
        controller.pollPlace();
        assertTrue(controller.placeConfirmed(dest));
        assertTrue(controller.lastPlaceAcked());
        assertFalse(controller.lastPlaceFailed());
        assertEquals(9, player.getInventory().getItem(0).getCount());
        assertTrue(level.getBlockState(dest).is(Blocks.DIRT));

        // A different block at the cell is a rejection, and the chunk is left as the server wrote it.
        store.setBlockState(dest, Blocks.AIR.defaultBlockState());
        player.getInventory().setItem(0, new ItemStack(Items.DIRT, 10));
        controller.processRightClickBlock(player, level, InteractionHand.MAIN_HAND, hit);
        assertEquals(9, player.getInventory().getItem(0).getCount());
        assertTrue(controller.absorbServerBlockUpdate(dest, Blocks.STONE.defaultBlockState()));
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
        assertTrue(level.getBlockState(new BlockPos(1, 1, 0)).is(Blocks.DIRT));
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
            // Ostinato parity: the real 26.3 client bot's settings (Ostinato defaults plus TenorClef).
            assertTrue(settings.allowPlace.value);
            assertFalse(settings.allowParkour.value);
            assertFalse(settings.allowParkourPlace.value);
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
            // Ostinato: the click is the raytrace onto this face, not a reconstructed face center.
            assertEquals(1.0, use.hitResult().getLocation().y, 1.0E-6);
            assertTrue("hit x=" + use.hitResult().getLocation().x, use.hitResult().getLocation().x > 1.0 && use.hitResult().getLocation().x < 2.0);
            assertTrue("hit z=" + use.hitResult().getLocation().z, use.hitResult().getLocation().z > 0.0 && use.hitResult().getLocation().z < 1.0);
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

    // Ostinato: Paper rejects a place that intersects the player AABB. Standing
    // in the dest cell must not send UseItemOn, even with a support underfoot
    // and a rotation already on the wire.
    @Test
    public void placeIsWithheldWhileFeetIntersectTheDestCell() {
        RecordingTransport transport = new RecordingTransport();
        HeadlessBot bot = spawned(transport, 0.5, 1.0, 0.5);
        try {
            bot.chunks().setBlockState(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
            bot.onPacket(new ClientboundContainerSetSlotPacket(
                    InventoryMenu.CONTAINER_ID, 1, InventoryMenu.USE_ROW_SLOT_START, new ItemStack(Items.DIRT, 16)));
            bot.ensureHost();
            bot.enqueuePathing("place 0 1 0");
            for (int i = 0; i < 12; i++) {
                bot.tick();
            }
            assertEquals("must not click while the feet AABB intersects dest (0,1,0): status="
                            + bot.pathingStatus() + " y=" + bot.liveDriver().player().getY()
                            + " aabb=" + bot.liveDriver().player().getBoundingBox(),
                    0, transport.count(ServerboundUseItemOnPacket.class));
        } finally {
            bot.closeHost();
        }
    }

    // Ostinato: once the feet AABB is clear of the dest and the look packet is
    // already on the wire, send exactly one UseItemOn (one per layer).
    @Test
    public void placeSendsOnceWhenFeetClearDestAndLookIsOnTheWire() {
        RecordingTransport transport = new RecordingTransport();
        HeadlessBot bot = spawned(transport, 0.5, 2.0, 1.5);
        try {
            bot.chunks().setBlockState(new BlockPos(0, 1, 1), Blocks.STONE.defaultBlockState());
            bot.chunks().setBlockState(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
            bot.onPacket(new ClientboundContainerSetSlotPacket(
                    InventoryMenu.CONTAINER_ID, 1, InventoryMenu.USE_ROW_SLOT_START, new ItemStack(Items.DIRT, 16)));
            bot.ensureHost();
            bot.enqueuePathing("place 0 1 0");
            ServerboundUseItemOnPacket use = null;
            for (int i = 0; i < 8 && use == null; i++) {
                bot.tick();
                List<ServerboundUseItemOnPacket> found = packets(transport, ServerboundUseItemOnPacket.class);
                if (!found.isEmpty()) {
                    use = found.get(0);
                }
            }
            assertNotNull("place did not send UseItemOn once feet were clear: " + bot.pathingStatus()
                    + " y=" + bot.liveDriver().player().getY()
                    + " aabb=" + bot.liveDriver().player().getBoundingBox(), use);
            BlockPos against = use.hitResult().getBlockPos();
            BlockPos into = against.relative(use.hitResult().getDirection());
            assertEquals("should place into dest (0,1,0): " + use.hitResult(), new BlockPos(0, 1, 0), into);
            // Already-sent dest must not be clicked again before ack.
            for (int i = 0; i < 6; i++) {
                bot.tick();
            }
            assertEquals("exactly one UseItemOn per layer before ack, got "
                            + packets(transport, ServerboundUseItemOnPacket.class),
                    1, transport.count(ServerboundUseItemOnPacket.class));
        } finally {
            bot.closeHost();
        }
    }


    @Test
    public void aimAtPlaceIgnoresANearerWallAndHitsTheIntendedFace() {
        RecordingTransport transport = new RecordingTransport();
        HeadlessBot bot = spawned(transport, 0.5, 1.0, 1.2);
        try {
            bot.chunks().setBlockState(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
            // Wall to the east of a sneak-bridge hang (feet at z=1.2, still on the floor at z=0).
            // A level east look hits the wall first; the click must still target the floor's south face.
            bot.chunks().setBlockState(new BlockPos(1, 1, 1), Blocks.STONE.defaultBlockState());
            bot.chunks().setBlockState(new BlockPos(1, 2, 1), Blocks.STONE.defaultBlockState());
            bot.ensureHost();
            var player = bot.liveDriver().player();
            player.setPos(0.5, 1.0, 1.2);
            player.setOnGround(true);
            player.setYRot(-90f); // east, into the wall
            player.setXRot(0f);

            var hit = bot.liveDriver().context().objectMouseOver();
            assertTrue("expected a block hit, got " + hit, hit instanceof BlockHitResult);
            assertEquals("east look should hit the wall first", new BlockPos(1, 2, 1), ((BlockHitResult) hit).getBlockPos());

            BlockPos want = new BlockPos(0, 0, 1);
            assertFalse("the wall must not count as the place target", bot.liveDriver().placesInto(hit, want));

            var aim = bot.liveDriver().aimAtPlace(Set.of(want));
            assertTrue("should see the floor's south face beside the wall", aim.isPresent());
            bot.liveDriver().look().updateTarget(aim.get(), true);
            var placed = bot.liveDriver().context().objectMouseOver();
            assertTrue("aimed look missed the intended face: " + placed, bot.liveDriver().placesInto(placed, want));
            assertEquals(new BlockPos(0, 0, 0), ((BlockHitResult) placed).getBlockPos());
            assertEquals(Direction.SOUTH, ((BlockHitResult) placed).getDirection());
        } finally {
            bot.closeHost();
        }
    }

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
            // Ostinato parity: the client-bot settings (TenorClef) plan no parkour, and this narrow void gap only plans
            // with parkour-place. Turn it on here to keep exercising that code path; the live parity runs cover the
            // client-bot settings (bridge and voidbridge on a flat world).
            bot.ensureHost();
            Baritone.settings().allowParkour.value = true;
            Baritone.settings().allowParkourPlace.value = true;
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
     * Ostinato: a 12-block sneak-bridge across a wide void (no sidewalks) under the
     * client's default first-search timeouts (500/2000). Chaining uses MovementTraverse's
     * pillar-style air-footing backplace, not a mid-search BlockState overlay.
     */
    @Test
    public void twelveBlockWideVoidPlacePathDefaultTimeouts() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        HeadlessBot bot = spawned(transport, 0.5, 2.0, 0.5);
        try {
            // A* stops once pathingMaxChunkBorderFetch (50) moves would enter an unloaded chunk.
            // The pad sits on the edge of chunk (0,0); load a 3x3 neighborhood so a 12-block
            // sneak-bridge is not abandoned as a chunk-border failure.
            for (int cx = -1; cx <= 1; cx++) {
                for (int cz = -1; cz <= 1; cz++) {
                    bot.chunks().createEmptyChunk(cx, cz);
                }
            }
            // Pad at z=0, landing at z=13, void z=1..12 and x=-4..4 so there is no sidewalk.
            for (int x = -2; x <= 2; x++) {
                bot.chunks().setBlockState(new BlockPos(x, 1, 0), Blocks.STONE.defaultBlockState());
                bot.chunks().setBlockState(new BlockPos(x, 1, 13), Blocks.STONE.defaultBlockState());
            }
            // Walls near the pad, like the live (b) course.
            bot.chunks().setBlockState(new BlockPos(-2, 2, 0), Blocks.STONE.defaultBlockState());
            bot.chunks().setBlockState(new BlockPos(2, 2, 0), Blocks.STONE.defaultBlockState());
            bot.chunks().setBlockState(new BlockPos(-2, 3, 0), Blocks.STONE.defaultBlockState());
            bot.chunks().setBlockState(new BlockPos(2, 3, 0), Blocks.STONE.defaultBlockState());
            bot.onPacket(new ClientboundContainerSetSlotPacket(
                    InventoryMenu.CONTAINER_ID, 1, InventoryMenu.USE_ROW_SLOT_START, new ItemStack(Items.DIRT, 64)));
            bot.ensureHost();
            bot.tick();

            HeadlessBaritone host = bot.liveDriver().baritone();
            assertTrue("dirt must be visible to A* at plan time", host.hasGenericThrowaway());
            assertEquals(64, host.countGenericThrowaway());
            assertTrue(Baritone.settings().allowPlace.value);
            assertEquals(Long.valueOf(500L), Baritone.settings().primaryTimeoutMS.value);
            assertEquals(Long.valueOf(2000L), Baritone.settings().failureTimeoutMS.value);

            boolean hasThrowaway = Baritone.settings().allowPlace.value && host.hasGenericThrowaway();
            CalculationContext context = new CalculationContext(host, host.bsi(),
                    new ToolSet(host.getPlayerContext().entity()), hasThrowaway, host.bsi().minY(), host.bsi().height());
            BetterBlockPos start = host.getPlayerContext().playerFeet();
            GoalBlock goal = new GoalBlock(0, 2, 13);
            AStarPathFinder first = new AStarPathFinder(start, start.x, start.y, start.z, goal,
                    new Favoring(null, context), context, 1);
            PathCalculationResult firstResult = first.calculate(
                    Baritone.settings().primaryTimeoutMS.value,
                    Baritone.settings().failureTimeoutMS.value);
            IPath firstPath = firstResult.getPath().orElse(null);
            String diag = "type=" + firstResult.getType()
                    + " timedOut=" + first.timedOut()
                    + " pathLength=" + (firstPath == null ? 0 : firstPath.length())
                    + " dest=" + (firstPath == null ? "none" : firstPath.getDest())
                    + " start=" + start
                    + " hasThrowaway=" + context.hasThrowaway
                    + " throwawayCount=" + host.countGenericThrowaway()
                    + " " + host.describeHotbar();
            System.out.println("[HeadlessPlaceTest] first-search " + diag);
            assertFalse("default 500/2000 first search found no place path: " + diag,
                    firstResult.getType() == PathCalculationResult.Type.FAILURE);

            bot.enqueuePathing("goto 0 2 13");
            String status = waitUntilPlanned(bot, TimeUnit.SECONDS.toNanos(25));
            assertFalse("12-block wide-void place path failed: " + status + " | " + diag, status.contains("no path")
                    || status.contains("failed") || status.contains("planning") || status.contains("cancelled"));
            assertTrue("12-block path has no placement: " + status + " | " + describePath(bot), pathPlaces(bot));
        } finally {
            bot.closeHost();
        }
    }

    // Ostinato: live hang pose — slightly past the pad's south face, sneaking, looking
    // at the face center the way MovementTraverse does. Without a pose-driven eye,
    // this ray misses and isLookingAt never becomes true.
    @Test
    public void sneakBridgeHangLooksAtSupportFace() {
        RecordingTransport transport = new RecordingTransport();
        HeadlessBot bot = spawned(transport, 0.5, 1.0, 1.093);
        try {
            bot.chunks().setBlockState(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
            bot.ensureHost();
            var player = bot.liveDriver().player();
            player.setPos(0.5, 1.0, 1.093);
            player.setOnGround(true);
            player.setShiftKeyDown(true);
            player.setPose(Pose.CROUCHING);
            float standing = player.getEyeHeight(Pose.STANDING);
            float crouching = player.getEyeHeight(Pose.CROUCHING);
            assertEquals("setPose must refresh dimensions so playerHead sees sneak height",
                    crouching, player.getEyeHeight(), 1.0E-4);
            assertTrue("crouch eye (" + crouching + ") should be lower than standing (" + standing + ")",
                    crouching < standing - 0.2f);
            var face = new Vec3(0.5, 0.5, 1.0);
            Rotation look = RotationUtils.calcRotationFromVec3d(
                    bot.liveDriver().context().playerHead(), face, new Rotation(0f, 0f));
            bot.liveDriver().look().updateTarget(look, true);
            var hit = bot.liveDriver().context().objectMouseOver();
            assertTrue("sneak-bridge hang must see the pad's south face, got " + hit
                    + " eye=" + player.getEyeHeight() + " rot=" + look
                    + " head=" + bot.liveDriver().context().playerHead(),
                    hit instanceof BlockHitResult);
            assertEquals(new BlockPos(0, 0, 0), ((BlockHitResult) hit).getBlockPos());
            assertEquals(Direction.SOUTH, ((BlockHitResult) hit).getDirection());
            assertTrue(bot.liveDriver().context().isLookingAt(new BlockPos(0, 0, 0)));
        } finally {
            bot.closeHost();
        }
    }

    // Ostinato: tick the first backplace click on a 12-block void (too far to parkour).
    @Test
    public void sneakBridgeBackplaceSendsUseItemOn() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        HeadlessBot bot = spawned(transport, 0.5, 2.0, 0.5);
        try {
            for (int cx = -1; cx <= 1; cx++) {
                for (int cz = -1; cz <= 1; cz++) {
                    bot.chunks().createEmptyChunk(cx, cz);
                }
            }
            for (int x = -2; x <= 2; x++) {
                bot.chunks().setBlockState(new BlockPos(x, 1, 0), Blocks.STONE.defaultBlockState());
                bot.chunks().setBlockState(new BlockPos(x, 1, 13), Blocks.STONE.defaultBlockState());
            }
            bot.chunks().setBlockState(new BlockPos(-2, 2, 0), Blocks.STONE.defaultBlockState());
            bot.chunks().setBlockState(new BlockPos(2, 2, 0), Blocks.STONE.defaultBlockState());
            bot.onPacket(new ClientboundContainerSetSlotPacket(
                    InventoryMenu.CONTAINER_ID, 1, InventoryMenu.USE_ROW_SLOT_START, new ItemStack(Items.DIRT, 64)));
            bot.ensureHost();
            bot.tick();
            bot.enqueuePathing("goto 0 2 13");
            String status = waitUntilPlanned(bot, TimeUnit.SECONDS.toNanos(8));
            assertFalse("backplace path failed: " + status, status.contains("no path")
                    || status.contains("failed") || status.contains("planning") || status.contains("cancelled"));
            ServerboundUseItemOnPacket use = null;
            String hang = "";
            for (int i = 0; i < 400 && use == null; i++) {
                bot.tick();
                var player = bot.liveDriver().player();
                if (player.getZ() > 1.02 && hang.isEmpty()) {
                    var look = bot.liveDriver().context().objectMouseOver();
                    String lookDesc = String.valueOf(look);
                    if (look instanceof BlockHitResult block) {
                        lookDesc = block.getBlockPos() + " " + block.getDirection() + " at " + block.getLocation();
                    }
                    var in = bot.liveDriver().baritone().getInputOverrideHandler();
                    var want = bot.liveDriver().intendedPlaces();
                    hang = "tick=" + i
                            + " pos=" + player.position()
                            + " rot=" + player.getYRot() + "," + player.getXRot()
                            + " eye=" + player.getEyeHeight()
                            + " sneak=" + player.isShiftKeyDown()
                            + " crouch=" + player.isCrouching()
                            + " pose=" + player.getPose()
                            + " clickR=" + in.isInputForcedDown(Input.CLICK_RIGHT)
                            + " clickL=" + in.isInputForcedDown(Input.CLICK_LEFT)
                            + " sneakKey=" + in.isInputForcedDown(Input.SNEAK)
                            + " back=" + in.isInputForcedDown(Input.MOVE_BACK)
                            + " lookingAtPad=" + bot.liveDriver().context().isLookingAt(new BlockPos(0, 1, 0))
                            + " look=" + lookDesc
                            + " want=" + want
                            + " aim=" + bot.liveDriver().aimAtPlace(want)
                            + " status=" + bot.pathingStatus();
                    System.out.println("[HeadlessPlaceTest] hang " + hang);
                }
                List<ServerboundUseItemOnPacket> found = packets(transport, ServerboundUseItemOnPacket.class);
                if (!found.isEmpty()) {
                    use = found.get(0);
                }
            }
            assertNotNull("first backplace did not send UseItemOn: hang=" + hang
                    + " final=" + bot.pathingStatus()
                    + " pos=" + bot.liveDriver().player().position()
                    + " rot=" + bot.liveDriver().player().getYRot() + "," + bot.liveDriver().player().getXRot()
                    + " eye=" + bot.liveDriver().player().getEyeHeight()
                    + " sneak=" + bot.liveDriver().player().isShiftKeyDown()
                    + " pose=" + bot.liveDriver().player().getPose()
                    + " look=" + bot.liveDriver().context().objectMouseOver(), use);
            BlockPos against = use.hitResult().getBlockPos();
            BlockPos into = against.relative(use.hitResult().getDirection());
            assertEquals("should place against the pad: " + use.hitResult(), new BlockPos(0, 1, 0), against);
            assertEquals(new BlockPos(0, 1, 1), into);
        } finally {
            bot.closeHost();
        }
    }

    // Ostinato: the live 12-block void placed 8 then died at z=10.06 after the
    // plan-ahead segment was installed. Tick all the way to the landing (or fail).
    @Test
    public void sneakBridgeTwelveBlockExecutesToLanding() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        HeadlessBot bot = spawned(transport, 0.5, 2.0, 0.5);
        try {
            for (int cx = -1; cx <= 1; cx++) {
                for (int cz = -1; cz <= 1; cz++) {
                    bot.chunks().createEmptyChunk(cx, cz);
                }
            }
            for (int x = -2; x <= 2; x++) {
                bot.chunks().setBlockState(new BlockPos(x, 1, 0), Blocks.STONE.defaultBlockState());
                bot.chunks().setBlockState(new BlockPos(x, 1, 13), Blocks.STONE.defaultBlockState());
            }
            bot.chunks().setBlockState(new BlockPos(-2, 2, 0), Blocks.STONE.defaultBlockState());
            bot.chunks().setBlockState(new BlockPos(2, 2, 0), Blocks.STONE.defaultBlockState());
            bot.onPacket(new ClientboundContainerSetSlotPacket(
                    InventoryMenu.CONTAINER_ID, 1, InventoryMenu.USE_ROW_SLOT_START, new ItemStack(Items.DIRT, 64)));
            bot.ensureHost();
            bot.tick();
            bot.enqueuePathing("goto 0 2 13");
            String status = "";
            int places = 0;
            for (int i = 0; i < 2500; i++) {
                bot.tick();
                // A* is on a real-time worker; yield so the first search and any
                // plan-ahead can finish. Ack places on every tick, including the
                // ones that land while the search is still running.
                if (i % 2 == 0) {
                    Thread.sleep(10);
                }
                status = bot.pathingStatus();
                places = packets(transport, ServerboundUseItemOnPacket.class).size();
                // Confirm each place so the chunk store matches a live server ack.
                for (ServerboundUseItemOnPacket use : packets(transport, ServerboundUseItemOnPacket.class)) {
                    BlockPos against = use.hitResult().getBlockPos();
                    BlockPos into = against.relative(use.hitResult().getDirection());
                    if (bot.lastBlockChangeSequence() < use.sequence()) {
                        bot.onPacket(new ClientboundBlockUpdatePacket(into, Blocks.DIRT.defaultBlockState()));
                        bot.onPacket(new ClientboundBlockChangedAckPacket(use.sequence()));
                    }
                }
                var player = bot.liveDriver().player();
                if ((status.contains("arrived") || (Math.abs(player.getX() - 0.5) < 1.0
                        && Math.abs(player.getY() - 2.0) < 0.7 && player.getZ() >= 12.8 && player.getZ() <= 14.7))
                        && !status.contains("execution failed")) {
                    System.out.println("[HeadlessPlaceTest] arrived ticks=" + i + " places=" + places
                            + " z=" + player.getZ() + " status=" + status);
                    return;
                }
                if (status.contains("execution failed") || status.contains("no path")
                        || status.contains("planning failed") || status.contains("cancelled")) {
                    break;
                }
            }
            var player = bot.liveDriver().player();
            throw new AssertionError("did not reach landing z=13: status=" + status
                    + " ticks-or-fail at pos=" + player.position()
                    + " rot=" + player.getYRot() + "," + player.getXRot()
                    + " places=" + places
                    + " path=" + describePath(bot)
                    + " aim=" + placeAimDebug(bot));
        } finally {
            bot.closeHost();
        }
    }

    // Ostinato: sneakBridgeTwelve used to fail when run after the default-timeout
    // 12-block plan (shared static / ThreadLocal / closeHost leak). Both must
    // pass in one JVM in this order.
    @Test
    public void sneakBridgeTwelveBlockExecutesToLandingAfterDefaultTimeoutPlan() throws Exception {
        twelveBlockWideVoidPlacePathDefaultTimeouts();
        sneakBridgeTwelveBlockExecutesToLanding();
    }

    // Ostinato: after pathing (or with no path at all) the sim must still fall and land.
    // A frozen mid-air pose at onGround=false is a flying flag.
    @Test
    public void idlePhysicsLandsWhenLeftMidAir() {
        RecordingTransport transport = new RecordingTransport();
        HeadlessBot bot = spawned(transport, 0.5, 4.0, 0.5);
        try {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    bot.chunks().setBlockState(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
                }
            }
            assertEquals(4.0, bot.y(), 1.0E-6);
            double previous = bot.y();
            boolean descended = false;
            for (int i = 0; i < 80; i++) {
                bot.tick();
                if (bot.y() < previous - 1.0E-4) {
                    descended = true;
                }
                previous = bot.y();
                if (bot.onGround() && bot.y() >= 1.9 && bot.y() <= 2.2) {
                    assertTrue("must have fallen before landing, y=" + bot.y(), descended);
                    int movePackets = transport.count(ServerboundMovePlayerPacket.Pos.class)
                            + transport.count(ServerboundMovePlayerPacket.PosRot.class)
                            + transport.count(ServerboundMovePlayerPacket.Rot.class);
                    assertTrue("falling must keep sending position packets, got " + movePackets,
                            movePackets >= i);
                    return;
                }
            }
            throw new AssertionError("did not land: y=" + bot.y() + " onGround=" + bot.onGround()
                    + " descended=" + descended);
        } finally {
            bot.closeHost();
        }
    }

    // Ostinato: a 6-block gap onto an existing landing is an extended parkour. The first
    // tick used to ClassCast HeadlessBaritone to Baritone in MovementParkour.extend.
    @Test
    public void extendedParkourExtensionDoesNotCast() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        HeadlessBot bot = spawned(transport, 0.5, 2.0, 0.5);
        try {
            for (int cx = -1; cx <= 1; cx++) {
                for (int cz = -1; cz <= 1; cz++) {
                    bot.chunks().createEmptyChunk(cx, cz);
                }
            }
            for (int x = -2; x <= 2; x++) {
                bot.chunks().setBlockState(new BlockPos(x, 1, 0), Blocks.STONE.defaultBlockState());
                bot.chunks().setBlockState(new BlockPos(x, 1, 7), Blocks.STONE.defaultBlockState());
            }
            bot.onPacket(new ClientboundContainerSetSlotPacket(
                    InventoryMenu.CONTAINER_ID, 1, InventoryMenu.USE_ROW_SLOT_START, new ItemStack(Items.DIRT, 64)));
            bot.ensureHost();
            // Ostinato parity: client-bot settings plan no parkour; this test covers the parkour-extension executor path.
            Baritone.settings().allowParkour.value = true;
            Baritone.settings().allowParkourPlace.value = true;
            bot.tick();
            bot.enqueuePathing("goto 0 2 7");
            String status = "";
            boolean sawExtended = false;
            for (int i = 0; i < 1500; i++) {
                bot.tick();
                if (i % 2 == 0) {
                    Thread.sleep(10);
                }
                status = bot.pathingStatus();
                if (status.contains("path error") || status.contains("cancelled after executor")
                        || status.contains("ClassCastException")) {
                    throw new AssertionError("extended parkour crashed: " + status + " path=" + describePath(bot));
                }
                IPathExecutor executor = bot.liveDriver().baritone().getPathingBehavior().getCurrent();
                if (executor != null) {
                    for (IMovement movement : executor.getPath().movements()) {
                        if (movement instanceof MovementParkour parkour && parkour.extensions() > 0) {
                            sawExtended = true;
                        }
                    }
                }
                for (ServerboundUseItemOnPacket use : packets(transport, ServerboundUseItemOnPacket.class)) {
                    BlockPos against = use.hitResult().getBlockPos();
                    BlockPos into = against.relative(use.hitResult().getDirection());
                    if (bot.lastBlockChangeSequence() < use.sequence()) {
                        bot.onPacket(new ClientboundBlockUpdatePacket(into, Blocks.DIRT.defaultBlockState()));
                        bot.onPacket(new ClientboundBlockChangedAckPacket(use.sequence()));
                    }
                }
                var player = bot.liveDriver().player();
                if (status.contains("execution failed") || status.contains("no path")
                        || status.contains("planning failed")
                        || status.contains("arrived")
                        || (Math.abs(player.getX() - 0.5) < 1.0
                                && Math.abs(player.getY() - 2.0) < 0.8 && player.getZ() >= 6.3 && player.getZ() <= 8.7)) {
                    // The ClassCast was on the first extend tick. Landing the jump is a later problem;
                    // this test only requires that extend() ran on a HeadlessBaritone without crashing.
                    assertTrue("path must include a parkour extension, got " + describePath(bot)
                            + " status=" + status, sawExtended);
                    return;
                }
            }
            throw new AssertionError("extended parkour never started: status=" + status
                    + " sawExtended=" + sawExtended + " pos=" + bot.liveDriver().player().position()
                    + " path=" + describePath(bot));
        } finally {
            bot.closeHost();
        }
    }

    private static String placeAimDebug(HeadlessBot bot) {
        HeadlessLiveDriver driver = bot.liveDriver();
        if (driver == null) {
            return "no host";
        }
        var player = driver.player();
        var level = player.level();
        Set<BlockPos> want = driver.intendedPlaces();
        StringBuilder out = new StringBuilder();
        out.append("want=").append(want);
        out.append(" sneak=").append(player.isShiftKeyDown());
        out.append(" pose=").append(player.getPose());
        out.append(" eyeH=").append(player.getEyeHeight());
        out.append(" sneakEye=").append(player.getEyeHeight(Pose.CROUCHING));
        out.append(" aabb=").append(player.getBoundingBox());
        out.append(" reach=").append(driver.controller().getBlockReachDistance());
        out.append(" look=").append(driver.context().objectMouseOver());
        for (BlockPos dest : want) {
            out.append(" dest=").append(dest).append(" state=").append(level.getBlockState(dest));
            for (Direction toward : Direction.values()) {
                BlockPos support = dest.relative(toward);
                var state = level.getBlockState(support);
                out.append(" support[").append(toward).append("]=").append(support)
                        .append(" ").append(state.getBlock())
                        .append(" sturdy=").append(state.isFaceSturdy(level, support, toward.getOpposite()));
            }
        }
        out.append(" aim=").append(driver.aimAtPlace(want));
        return out.toString();
    }

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
        return waitUntilPlanned(bot, TimeUnit.SECONDS.toNanos(8));
    }

    private static String waitUntilPlanned(HeadlessBot bot, long nanos) throws InterruptedException {
        long deadline = System.nanoTime() + nanos;
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
