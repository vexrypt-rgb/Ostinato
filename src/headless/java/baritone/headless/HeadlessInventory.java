/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 3b). New file, replaces no Baritone code.
 *
 * Survival inventory mirror. Login can deliver the contents before the pathing player exists, so those
 * packets wait here and flush into Player.inventoryMenu on bind. Chest containers are not applied.
 */
package baritone.headless;

import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class HeadlessInventory {

    /** Player inventory menu, and the carried-item window vanilla uses for containerId -1. */
    private static final int CARRIED_CONTAINER = -1;

    private final Object lock = new Object();
    private final List<Object> pending = new ArrayList<>();
    private HeadlessPlayer player;

    /**
     * @return true when this packet was applied or queued. A chest (any other container id) returns
     * false so the bot still counts it as unhandled.
     */
    public boolean handle(Object packet) {
        if (!isInventoryPacket(packet)) {
            return false;
        }
        synchronized (lock) {
            if (player == null) {
                if (isForeignContainer(packet)) {
                    return false;
                }
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

    private static boolean isInventoryPacket(Object packet) {
        return packet instanceof ClientboundContainerSetContentPacket
                || packet instanceof ClientboundContainerSetSlotPacket
                || packet instanceof ClientboundSetHeldSlotPacket
                || packet instanceof ClientboundSetPlayerInventoryPacket;
    }

    private static boolean isForeignContainer(Object packet) {
        if (packet instanceof ClientboundContainerSetContentPacket content) {
            return !isPlayerContainer(content.containerId());
        }
        if (packet instanceof ClientboundContainerSetSlotPacket slot) {
            return !isPlayerContainer(slot.getContainerId());
        }
        return false;
    }

    private static boolean isPlayerContainer(int containerId) {
        return containerId == InventoryMenu.CONTAINER_ID || containerId == CARRIED_CONTAINER;
    }

    private boolean apply(Object packet) {
        // Ostinato: menu writes go through the same lock as a predicted place decrement.
        synchronized (player.getInventory()) {
            if (packet instanceof ClientboundContainerSetContentPacket content) {
                return applyContent(content);
            }
            if (packet instanceof ClientboundContainerSetSlotPacket slot) {
                return applySlot(slot);
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

    private boolean applyContent(ClientboundContainerSetContentPacket content) {
        if (!isPlayerContainer(content.containerId())) {
            return false;
        }
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
        if (!isPlayerContainer(slot.getContainerId())) {
            return false;
        }
        ItemStack stack = slot.getItem().copy();
        if (slot.getContainerId() == CARRIED_CONTAINER) {
            player.inventoryMenu.setCarried(stack);
            return true;
        }
        writeMenuSlot(player.inventoryMenu, slot.getSlot(), slot.getStateId(), stack);
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
}
