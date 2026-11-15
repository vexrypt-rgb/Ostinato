/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 3b). New file, replaces no Baritone code.
 *
 * Survival inventory and open-container mirror. Login can deliver contents before the pathing
 * player exists, so those packets wait here and flush into Player.inventoryMenu on bind.
 * Foreign menus (crafting table, furnace, chest) are applied when OpenScreen arrives, and
 * ServerboundContainerClick is sent the way MultiPlayerGameMode.handleContainerInput does:
 * predict the click on the local menu, then hash the changed slots with CRC32C HashOps.
 */
package baritone.headless;

import baritone.process.interact.ClientContainers;
import baritone.process.interact.ContainerView;
import com.google.common.hash.HashCode;
import com.google.common.primitives.Shorts;
import com.google.common.primitives.SignedBytes;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.NonNullList;
import net.minecraft.network.HashedPatchMap;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundPlaceGhostRecipePacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookRemovePacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.HashOps;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

import java.util.ArrayList;
import java.util.List;

public final class HeadlessInventory {

    /** Player inventory menu, and the carried-item window vanilla uses for containerId -1. */
    private static final int CARRIED_CONTAINER = -1;

    private final Object lock = new Object();
    private final List<Object> pending = new ArrayList<>();
    private final HeadlessRecipeBook recipes = new HeadlessRecipeBook();
    private HeadlessBot bot;
    private HeadlessPlayer player;
    private HashedPatchMap.HashGenerator hashGenerator;
    private long clickPackets;
    private long placeRecipePackets;
    private long closePackets;

    void attach(HeadlessBot bot) {
        this.bot = bot;
    }

    /**
     * @return true when this packet was applied or queued.
     */
    public boolean handle(Object packet) {
        if (recipes.handle(packet)) {
            return true;
        }
        if (packet instanceof ClientboundPlaceGhostRecipePacket) {
            return true;
        }
        if (!isInventoryPacket(packet)) {
            return false;
        }
        synchronized (lock) {
            if (player == null) {
                pending.add(packet);
                return true;
            }
            return apply(packet);
        }
    }

    /** Flush packets that arrived before {@link HeadlessBot#ensureHost()} built the player. */
    public void bind(HeadlessPlayer player) {
        synchronized (lock) {
            this.player = player;
            List<Object> queued = new ArrayList<>(pending);
            pending.clear();
            for (Object packet : queued) {
                apply(packet);
            }
        }
    }

    public HeadlessPlayer player() {
        return player;
    }

    public HeadlessRecipeBook recipes() {
        return recipes;
    }

    public AbstractContainerMenu menu() {
        return player == null ? null : player.containerMenu;
    }

    public ContainerView.Kind kind() {
        AbstractContainerMenu menu = menu();
        return menu == null ? ContainerView.Kind.NONE : ClientContainers.kindOf(menu);
    }

    public boolean isMenuOpen() {
        return player != null && player.containerMenu != null && player.containerMenu != player.inventoryMenu;
    }

    public long clickPackets() {
        return clickPackets;
    }

    public long placeRecipePackets() {
        return placeRecipePackets;
    }

    public long closePackets() {
        return closePackets;
    }

    /**
     * Predict the click on the open menu and send ServerboundContainerClick with the current
     * state id and CRC32C hashed changed slots, matching MultiPlayerGameMode.handleContainerInput.
     */
    public void click(int slotId, int button, ContainerInput type) {
        if (player == null || bot == null) {
            return;
        }
        synchronized (player.getInventory()) {
            AbstractContainerMenu menu = player.containerMenu;
            NonNullList<Slot> slots = menu.slots;
            int slotCount = slots.size();
            List<ItemStack> itemsBeforeClick = new ArrayList<>(slotCount);
            for (Slot slot : slots) {
                itemsBeforeClick.add(slot.getItem().copy());
            }
            menu.clicked(slotId, button, type, player);
            Int2ObjectOpenHashMap<HashedStack> changedSlots = new Int2ObjectOpenHashMap<>();
            HashedPatchMap.HashGenerator hasher = hasher();
            for (int i = 0; i < slotCount; i++) {
                ItemStack before = itemsBeforeClick.get(i);
                ItemStack after = slots.get(i).getItem();
                if (!ItemStack.matches(before, after)) {
                    changedSlots.put(i, HashedStack.create(after, hasher));
                }
            }
            HashedStack carriedItem = HashedStack.create(menu.getCarried(), hasher);
            bot.send(new ServerboundContainerClickPacket(
                    menu.containerId,
                    menu.getStateId(),
                    Shorts.checkedCast(slotId),
                    SignedBytes.checkedCast(button),
                    type,
                    changedSlots,
                    carriedItem));
            clickPackets++;
        }
    }

    public void windowClick(int windowId, int slotId, int mouseButton, ContainerInput type, Player clicking) {
        if (player == null || player.containerMenu.containerId != windowId) {
            return;
        }
        click(slotId, mouseButton, type);
    }

    public void placeRecipe(RecipeDisplayId id, boolean useMaxItems) {
        if (player == null || bot == null || id == null) {
            return;
        }
        bot.send(new ServerboundPlaceRecipePacket(player.containerMenu.containerId, id, useMaxItems));
        placeRecipePackets++;
    }

    public void closeContainer() {
        if (player == null || bot == null) {
            return;
        }
        synchronized (player.getInventory()) {
            AbstractContainerMenu open = player.containerMenu;
            if (open != player.inventoryMenu) {
                bot.send(new ServerboundContainerClosePacket(open.containerId));
                closePackets++;
                player.containerMenu = player.inventoryMenu;
            }
        }
    }

    int findSlot(java.util.function.Predicate<ItemStack> pred, boolean playerInventoryOnly) {
        if (player == null) {
            return -1;
        }
        AbstractContainerMenu menu = player.containerMenu;
        int start = playerInventoryOnly ? playerInventoryStart(menu) : 0;
        int result = resultSlot(menu);
        for (int i = start; i < menu.slots.size(); i++) {
            if (i == result) {
                continue;
            }
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && pred.test(stack)) {
                return i;
            }
        }
        return -1;
    }

    int findEmptyPlayerSlot() {
        if (player == null) {
            return -1;
        }
        AbstractContainerMenu menu = player.containerMenu;
        int start = playerInventoryStart(menu);
        for (int i = start; i < menu.slots.size(); i++) {
            if (menu.getSlot(i).getItem().isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    static int playerInventoryStart(AbstractContainerMenu menu) {
        return switch (ClientContainers.kindOf(menu)) {
            case CHEST -> ((net.minecraft.world.inventory.ChestMenu) menu).getRowCount() * 9;
            case FURNACE, BLAST_FURNACE -> 3;
            case CRAFTING -> 10;
            default -> menu instanceof InventoryMenu ? InventoryMenu.INV_SLOT_START : menu.slots.size();
        };
    }

    static int resultSlot(AbstractContainerMenu menu) {
        return switch (ClientContainers.kindOf(menu)) {
            case FURNACE, BLAST_FURNACE -> 2;
            case CRAFTING -> 0;
            default -> menu instanceof InventoryMenu ? InventoryMenu.RESULT_SLOT : -1;
        };
    }

    static int craftSlotStart(AbstractContainerMenu menu) {
        return switch (ClientContainers.kindOf(menu)) {
            case CRAFTING -> 1;
            default -> menu instanceof InventoryMenu ? InventoryMenu.CRAFT_SLOT_START : -1;
        };
    }

    static int craftSlotCount(AbstractContainerMenu menu) {
        return switch (ClientContainers.kindOf(menu)) {
            case CRAFTING -> 9;
            default -> menu instanceof InventoryMenu ? InventoryMenu.CRAFT_SLOT_COUNT : 0;
        };
    }

    static int chestSize(AbstractContainerMenu menu) {
        if (menu instanceof net.minecraft.world.inventory.ChestMenu chest) {
            return chest.getRowCount() * 9;
        }
        return 0;
    }

    ItemStack slot(int index) {
        if (player == null || index < 0 || index >= player.containerMenu.slots.size()) {
            return ItemStack.EMPTY;
        }
        return player.containerMenu.getSlot(index).getItem();
    }

    boolean carriedEmpty() {
        return player == null || player.containerMenu.getCarried().isEmpty();
    }

    private HashedPatchMap.HashGenerator hasher() {
        if (hashGenerator == null && bot != null) {
            RegistryOps<HashCode> ops = RegistryOps.create(HashOps.CRC32C_INSTANCE, bot.registryAccess());
            hashGenerator = component -> component.encodeValue(ops)
                    .getOrThrow(err -> new IllegalArgumentException(String.valueOf(component) + err))
                    .asInt();
        }
        return hashGenerator;
    }

    void invalidateHasher() {
        hashGenerator = null;
    }

    private static boolean isInventoryPacket(Object packet) {
        return packet instanceof ClientboundContainerSetContentPacket
                || packet instanceof ClientboundContainerSetSlotPacket
                || packet instanceof ClientboundContainerSetDataPacket
                || packet instanceof ClientboundSetHeldSlotPacket
                || packet instanceof ClientboundSetPlayerInventoryPacket
                || packet instanceof ClientboundOpenScreenPacket
                || packet instanceof ClientboundContainerClosePacket
                || packet instanceof ClientboundRecipeBookAddPacket
                || packet instanceof ClientboundRecipeBookRemovePacket
                || packet instanceof ClientboundPlaceGhostRecipePacket;
    }

    private boolean apply(Object packet) {
        // Ostinato: menu writes go through the same lock as a predicted place decrement.
        synchronized (player.getInventory()) {
            if (packet instanceof ClientboundOpenScreenPacket open) {
                return applyOpen(open);
            }
            if (packet instanceof ClientboundContainerClosePacket close) {
                return applyClose(close);
            }
            if (packet instanceof ClientboundContainerSetContentPacket content) {
                return applyContent(content);
            }
            if (packet instanceof ClientboundContainerSetSlotPacket slot) {
                return applySlot(slot);
            }
            if (packet instanceof ClientboundContainerSetDataPacket data) {
                return applyData(data);
            }
            if (packet instanceof ClientboundSetHeldSlotPacket held) {
                if (Inventory.isHotbarSlot(held.slot())) {
                    player.getInventory().setSelectedSlot(held.slot());
                }
                return true;
            }
            if (packet instanceof ClientboundSetPlayerInventoryPacket inv) {
                applyRawSlot(inv.slot(), inv.contents().copy());
                return true;
            }
        }
        return false;
    }

    private boolean applyOpen(ClientboundOpenScreenPacket open) {
        try {
            AbstractContainerMenu menu = open.getType().create(open.getContainerId(), player.getInventory());
            player.containerMenu = menu;
            return true;
        } catch (RuntimeException e) {
            System.err.println("[HeadlessInventory] open screen failed: " + open.getType() + " " + e);
            return false;
        }
    }

    private boolean applyClose(ClientboundContainerClosePacket close) {
        if (player.containerMenu != player.inventoryMenu && player.containerMenu.containerId == close.getContainerId()) {
            player.containerMenu = player.inventoryMenu;
        }
        return true;
    }

    private boolean applyContent(ClientboundContainerSetContentPacket content) {
        int id = content.containerId();
        if (isPlayerContainer(id)) {
            return applyPlayerContent(content);
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu == player.inventoryMenu || menu.containerId != id) {
            return false;
        }
        List<ItemStack> items = content.items();
        int slots = menu.slots.size();
        List<ItemStack> copies = new ArrayList<>(items.size());
        for (ItemStack stack : items) {
            copies.add(stack.copy());
        }
        if (copies.size() == slots) {
            menu.initializeContents(content.stateId(), copies, content.carriedItem().copy());
        } else {
            int n = Math.min(copies.size(), slots);
            for (int i = 0; i < n; i++) {
                menu.setItem(i, content.stateId(), copies.get(i));
            }
            menu.setCarried(content.carriedItem().copy());
        }
        return true;
    }

    private boolean applyPlayerContent(ClientboundContainerSetContentPacket content) {
        InventoryMenu menu = player.inventoryMenu;
        if (content.containerId() == InventoryMenu.CONTAINER_ID) {
            List<ItemStack> items = content.items();
            int slots = menu.slots.size();
            // Ostinato: a full player-menu snapshot is one list. A short list still fills the prefix.
            if (items.size() == slots) {
                List<ItemStack> copies = new ArrayList<>(slots);
                for (ItemStack stack : items) {
                    copies.add(stack.copy());
                }
                menu.initializeContents(content.stateId(), copies, content.carriedItem().copy());
            } else {
                int n = Math.min(items.size(), slots);
                for (int i = 0; i < n; i++) {
                    writeMenuSlot(menu, i, content.stateId(), items.get(i).copy());
                }
                menu.setCarried(content.carriedItem().copy());
            }
            return true;
        }
        menu.setCarried(content.carriedItem().copy());
        return true;
    }

    private boolean applySlot(ClientboundContainerSetSlotPacket slot) {
        int id = slot.getContainerId();
        ItemStack stack = slot.getItem().copy();
        if (id == CARRIED_CONTAINER) {
            (isMenuOpen() ? player.containerMenu : player.inventoryMenu).setCarried(stack);
            return true;
        }
        if (isPlayerContainer(id)) {
            writeMenuSlot(player.inventoryMenu, slot.getSlot(), slot.getStateId(), stack);
            return true;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu == player.inventoryMenu || menu.containerId != id) {
            return false;
        }
        if (menu.isValidSlotIndex(slot.getSlot())) {
            menu.setItem(slot.getSlot(), slot.getStateId(), stack);
            return true;
        }
        return false;
    }

    private boolean applyData(ClientboundContainerSetDataPacket data) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu.containerId != data.getContainerId()) {
            return menu != null && player.inventoryMenu.containerId == data.getContainerId();
        }
        menu.setData(data.getId(), data.getValue());
        return true;
    }

    private void writeMenuSlot(InventoryMenu menu, int menuSlot, int stateId, ItemStack stack) {
        try {
            if (menu.isValidSlotIndex(menuSlot)) {
                menu.setItem(menuSlot, stateId, stack);
                return;
            }
        } catch (RuntimeException ignored) {
            // Fall through to the raw inventory index. Crafting-result slots are not stored there.
        }
        if (menuSlot >= InventoryMenu.USE_ROW_SLOT_START && menuSlot < InventoryMenu.USE_ROW_SLOT_END) {
            player.getInventory().setItem(menuSlot - InventoryMenu.USE_ROW_SLOT_START, stack);
        } else if (menuSlot >= InventoryMenu.INV_SLOT_START && menuSlot < InventoryMenu.INV_SLOT_END) {
            player.getInventory().setItem(menuSlot, stack);
        } else if (menuSlot == InventoryMenu.SHIELD_SLOT) {
            player.getInventory().setItem(Inventory.SLOT_OFFHAND, stack);
        }
    }

    private void applyRawSlot(int slot, ItemStack stack) {
        if (slot >= 0 && slot < Inventory.INVENTORY_SIZE
                || slot == Inventory.SLOT_OFFHAND
                || slot == Inventory.SLOT_BODY_ARMOR
                || slot == Inventory.SLOT_SADDLE) {
            player.getInventory().setItem(slot, stack);
        }
    }

    private static boolean isPlayerContainer(int containerId) {
        return containerId == InventoryMenu.CONTAINER_ID || containerId == CARRIED_CONTAINER;
    }
}
