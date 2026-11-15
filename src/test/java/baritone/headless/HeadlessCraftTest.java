/*
 * Ostinato: headless crafting recipes, hashed container clicks, and foreign menu sync.
 */
package baritone.headless;

import baritone.process.interact.ContainerView;
import com.mojang.authlib.GameProfile;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class HeadlessCraftTest {

    private static RegistryAccess.Frozen access;

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        access = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        if (!Items.OAK_LOG.builtInRegistryHolder().areComponentsBound()) {
            HeadlessRegistries.bindDefaultComponents(VanillaRegistries.createWorldLookup());
        }
    }

    @Test
    public void planBuildsPlanksThenTableFromLogs() {
        HeadlessPlayer player = player();
        player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 16));
        List<HeadlessRecipes.Recipe> plan = HeadlessRecipes.plan(Items.CRAFTING_TABLE, 1, player.getInventory());
        assertFalse(plan.isEmpty());
        assertTrue(plan.stream().anyMatch(step -> HeadlessRecipes.isPlanks(step.result())));
        assertTrue(plan.stream().anyMatch(step -> step.result() == Items.CRAFTING_TABLE));
        assertEquals(Items.OAK_PLANKS, HeadlessRecipes.concreteResult(Items.OAK_PLANKS, player.getInventory()));
        assertEquals(Items.OAK_PLANKS, HeadlessRecipes.planksForLog(Items.OAK_LOG));
    }

    @Test
    public void planPickaxeIncludesSticksAndNeedsATable() {
        HeadlessPlayer player = player();
        player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 16));
        List<HeadlessRecipes.Recipe> plan = HeadlessRecipes.plan(Items.WOODEN_PICKAXE, 1, player.getInventory());
        assertTrue(plan.stream().anyMatch(step -> HeadlessRecipes.isPlanks(step.result())));
        assertTrue(plan.stream().anyMatch(step -> step.result() == Items.STICK));
        HeadlessRecipes.Recipe pick = plan.stream()
                .filter(step -> step.result() == Items.WOODEN_PICKAXE)
                .findFirst()
                .orElse(null);
        assertNotNull(pick);
        assertTrue(pick.needsTable());
        assertEquals(3, pick.width());
        int plankCrafts = (int) plan.stream().filter(step -> HeadlessRecipes.isPlanks(step.result())).count();
        int planksProduced = plan.stream().filter(step -> HeadlessRecipes.isPlanks(step.result()))
                .mapToInt(HeadlessRecipes.Recipe::resultCount).sum();
        assertTrue("pickaxe needs 5 planks (3 head + 2 for sticks); produced=" + planksProduced, planksProduced >= 5);
        assertTrue(plankCrafts >= 2);
    }

    @Test
    public void aliasesResolveCommonCraftAndSmeltNames() {
        assertEquals(Items.STICK, HeadlessRecipes.resolve("sticks"));
        assertEquals(Items.CRAFTING_TABLE, HeadlessRecipes.resolve("workbench"));
        assertEquals(Items.WOODEN_PICKAXE, HeadlessRecipes.resolve("wooden_pickaxe"));
        assertEquals(Items.RAW_IRON, HeadlessRecipes.resolve("raw_iron"));
        assertEquals(Items.FURNACE, HeadlessRecipes.resolve("furnace"));
    }

    @Test
    public void clickSendsHashedContainerClickWithStateId() {
        RecordingTransport transport = new RecordingTransport();
        HeadlessBot bot = new HeadlessBot(transport, "127.0.0.1", 25565, "HBot1", UUID.randomUUID(), access);
        HeadlessPlayer player = player();
        bot.inventory().bind(player);
        player.inventoryMenu.setItem(InventoryMenu.USE_ROW_SLOT_START, 7, new ItemStack(Items.OAK_LOG, 8));
        transport.sent.clear();

        bot.inventory().click(InventoryMenu.USE_ROW_SLOT_START, 0, ContainerInput.PICKUP);

        assertFalse("log should be carried after left-click", player.inventoryMenu.getCarried().isEmpty());
        assertTrue(player.inventoryMenu.getCarried().is(Items.OAK_LOG));
        List<ServerboundContainerClickPacket> clicks = packets(transport, ServerboundContainerClickPacket.class);
        assertEquals(1, clicks.size());
        ServerboundContainerClickPacket click = clicks.get(0);
        assertEquals(InventoryMenu.CONTAINER_ID, click.containerId());
        assertEquals(7, click.stateId());
        assertEquals(InventoryMenu.USE_ROW_SLOT_START, click.slotNum());
        assertEquals(0, click.buttonNum());
        assertEquals(ContainerInput.PICKUP, click.containerInput());
        assertFalse("changed slots must be hashed, not left empty", click.changedSlots().isEmpty());
    }

    @Test
    public void openScreenSwitchesToAFurnaceMenu() {
        RecordingTransport transport = new RecordingTransport();
        HeadlessBot bot = new HeadlessBot(transport, "127.0.0.1", 25565, "HBot1", UUID.randomUUID(), access);
        HeadlessPlayer player = player();
        bot.inventory().bind(player);
        assertEquals(ContainerView.Kind.NONE, bot.inventory().kind());

        assertTrue(bot.inventory().handle(new ClientboundOpenScreenPacket(
                2, MenuType.FURNACE, Component.literal("Furnace"))));
        assertTrue(bot.inventory().isMenuOpen());
        assertEquals(ContainerView.Kind.FURNACE, bot.inventory().kind());
        assertEquals(2, player.containerMenu.containerId);

        bot.inventory().closeContainer();
        assertFalse(bot.inventory().isMenuOpen());
        List<net.minecraft.network.protocol.game.ServerboundContainerClosePacket> closes =
                packets(transport, net.minecraft.network.protocol.game.ServerboundContainerClosePacket.class);
        assertEquals(1, closes.size());
        assertEquals(2, closes.get(0).getContainerId());
    }

    @Test
    public void windowClickOnTheControllerIsNoLongerANoOp() {
        RecordingTransport transport = new RecordingTransport();
        HeadlessBot bot = new HeadlessBot(transport, "127.0.0.1", 25565, "HBot1", UUID.randomUUID(), access);
        HeadlessPlayer player = player();
        bot.inventory().bind(player);
        HeadlessLevel level = (HeadlessLevel) player.level();
        HeadlessPlayerController controller = new HeadlessPlayerController(bot, player, level);
        player.inventoryMenu.setItem(InventoryMenu.USE_ROW_SLOT_START, 3, new ItemStack(Items.OAK_PLANKS, 4));
        transport.sent.clear();
        controller.windowClick(InventoryMenu.CONTAINER_ID, InventoryMenu.USE_ROW_SLOT_START, 0,
                ContainerInput.PICKUP, player);
        assertEquals(1, packets(transport, ServerboundContainerClickPacket.class).size());
    }

    private static HeadlessPlayer player() {
        HeadlessChunkStore store = new HeadlessChunkStore();
        store.setDimension(0, 32);
        store.createEmptyChunk(0, 0);
        HeadlessLevel level = HeadlessLevel.create(store, access);
        return new HeadlessPlayer(level, new GameProfile(UUID.randomUUID(), "HBot1"));
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
    }

    @Test
    public void autoCollectAfterDropUsesPreDropBaseline() {
        // Vanilla scooped the throw: inventory already restored, no entity left.
        assertEquals(7, HeadlessCraft.pickupStartAfterAutoCollect(
                8, Items.DIRT, Items.DIRT, 8, 1, 100, 150, false));
        // Item still on the ground (pickup delay): keep the current (post-drop) count.
        assertEquals(7, HeadlessCraft.pickupStartAfterAutoCollect(
                7, Items.DIRT, Items.DIRT, 8, 1, 100, 120, true));
        // Drop window expired; this is just "nothing to pick up".
        assertEquals(8, HeadlessCraft.pickupStartAfterAutoCollect(
                8, Items.DIRT, Items.DIRT, 8, 1, 100, 100 + 20 * 7, false));
        // A different item's drop must not rewrite the dirt baseline.
        assertEquals(3, HeadlessCraft.pickupStartAfterAutoCollect(
                3, Items.DIRT, Items.COBBLESTONE, 8, 1, 100, 150, false));
    }

    @Test
    public void inRangeItemDoesNotNeedGoalBlock() {
        // Player at 3.5,-60,3.5, width 0.6: box [3.2..3.8] x [-60..-58.2] x [3.2..3.8].
        var player = new net.minecraft.world.phys.AABB(3.2, -60.0, 3.2, 3.8, -58.2, 3.8);
        // Item at the feet — must be auto-collectable without pathing.
        var atFeet = new net.minecraft.world.phys.AABB(3.375, -60.0, 3.375, 3.625, -59.75, 3.625);
        assertTrue(HeadlessCraft.inVanillaItemPickupRange(player, atFeet));
        // Item ~1.45m away (the earlier live miss) is outside the inflate(1.0) volume.
        var short_ = new net.minecraft.world.phys.AABB(3.375, -60.0, 1.925, 3.625, -59.75, 2.175);
        assertFalse(HeadlessCraft.inVanillaItemPickupRange(player, short_));
    }

    @Test
    public void takeItemEntitySignalsPickupWithoutInventorySync() {
        // Inventory still at the pre-pickup baseline; the take packet is enough.
        assertTrue(HeadlessCraft.pickupSucceededViaTakeItem(true, 7, 7));
        assertTrue(HeadlessCraft.pickupSucceededViaTakeItem(true, 8, 7));
        assertFalse(HeadlessCraft.pickupSucceededViaTakeItem(false, 7, 7));
        assertFalse(HeadlessCraft.pickupSucceededViaTakeItem(false, 8, 7));
    }
}
