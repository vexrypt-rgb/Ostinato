/*
 * Ostinato: fully headless 26.3 protocol bot. New file, replaces no Baritone code.
 *
 * Mirrors ClientboundRecipeBookAdd / Remove so the bot can send ServerboundPlaceRecipePacket
 * with the same RecipeDisplayId a real client uses when clicking the recipe book.
 */
package baritone.headless;

import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookRemovePacket;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class HeadlessRecipeBook {

    private final Map<Integer, RecipeDisplayEntry> byId = new LinkedHashMap<>();

    boolean handle(Object packet) {
        if (packet instanceof ClientboundRecipeBookAddPacket add) {
            if (add.replace()) {
                byId.clear();
            }
            for (ClientboundRecipeBookAddPacket.Entry entry : add.entries()) {
                RecipeDisplayEntry contents = entry.contents();
                byId.put(contents.id().index(), contents);
            }
            return true;
        }
        if (packet instanceof ClientboundRecipeBookRemovePacket remove) {
            applyRemove(remove);
            return true;
        }
        return false;
    }

    void clear() {
        byId.clear();
    }

    int size() {
        return byId.size();
    }

    /** The recipe-book display for {@code result}, or null when the server has not unlocked it. */
    RecipeDisplayId find(Item result, Level level) {
        if (result == null) {
            return null;
        }
        for (RecipeDisplayEntry entry : byId.values()) {
            if (outputs(entry, result, level)) {
                return entry.id();
            }
        }
        return null;
    }

    private static boolean outputs(RecipeDisplayEntry entry, Item result, Level level) {
        RecipeDisplay display = entry.display();
        if (matchesSlot(display.result(), result)) {
            return true;
        }
        if (level == null) {
            return false;
        }
        try {
            ContextMap context = SlotDisplayContext.fromLevel(level);
            List<ItemStack> items = entry.resultItems(context);
            for (ItemStack stack : items) {
                if (!stack.isEmpty() && stack.is(result)) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // Ostinato: some displays need a fuller context than a headless level provides.
        }
        return false;
    }

    private static boolean matchesSlot(SlotDisplay slot, Item result) {
        if (slot instanceof SlotDisplay.ItemSlotDisplay itemSlot) {
            Holder<Item> holder = itemSlot.item();
            return holder != null && holder.value() == result;
        }
        return false;
    }

    private void applyRemove(ClientboundRecipeBookRemovePacket remove) {
        for (RecipeDisplayId id : remove.recipes()) {
            byId.remove(id.index());
        }
    }
}
