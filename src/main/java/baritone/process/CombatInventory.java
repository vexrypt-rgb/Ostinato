package baritone.process;

import baritone.api.utils.IPlayerContext;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Hotbar and inventory queries and swaps for combat. Holds only the inventory-screen swap and break-count state. */
final class CombatInventory {
    static final Item[] SWORDS = {Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.STONE_SWORD, Items.GOLDEN_SWORD, Items.WOODEN_SWORD};
    static final Item[] AXES = {Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.GOLDEN_AXE, Items.WOODEN_AXE};
    /** Plain spears (no enchant required). Order is best-first for best(). */
    static final Item[] SPEARS = {};

    private final IPlayerContext ctx;
    private int invTick = -99;
    private final java.util.Map<Item, Integer> kitCount = new java.util.HashMap<>();
    /** When each hotbar slot was last pulled into by {@link #slotOf}, as a running count of pulls; 0 = never. */
    private final long[] pulled = new long[9];
    private long pulls;
    /** The inventory screen opened here for a swap, until that swap is clicked. */
    private net.minecraft.client.gui.screens.Screen opened;

    CombatInventory(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    void resetBreaks() {
        kitCount.clear();
    }

    /**
     * An item that used up its durability simply vanishes. Count the damageable combat items each tick: one fewer than
     * last tick is a break (a trident is thrown, not broken, so it is not counted). Returns " broke:&lt;item&gt;" per break,
     * else "". The caller drops whatever was keyed to the lost item (shield, held use key) and lets the next tick pick again.
     */
    String noteBreaks(Player me) {
        String note = "";
        java.util.Map<Item, Integer> now = new java.util.HashMap<>();
        for (int i = 0; i < me.getInventory().getContainerSize(); i++) {
            ItemStack st = me.getInventory().getItem(i);
            if (st.isDamageableItem() && st.getItem() != Items.TRIDENT && st.getItem() != Items.ELYTRA && !st.isEmpty()) now.merge(st.getItem(), 1, Integer::sum);
        }
        for (java.util.Map.Entry<Item, Integer> e : kitCount.entrySet()) {
            if (now.getOrDefault(e.getKey(), 0) < e.getValue() && me.isAlive() && me.getHealth() > 0) {
                note += " broke:" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getKey()).getPath();
            }
        }
        kitCount.clear();
        kitCount.putAll(now);
        return note;
    }

    /** Hotbar slot of a splash potion carrying the effect, or -1. */
    int potion(Player me, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect) {
        for (int i = 0; i < 9; i++) {
            ItemStack st = me.getInventory().getItem(i);
            if (st.getItem() != Items.SPLASH_POTION) continue;
            net.minecraft.world.item.alchemy.PotionContents pc = st.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
            if (pc == null) continue;
            for (net.minecraft.world.effect.MobEffectInstance ei : pc.getAllEffects()) if (ei.getEffect().equals(effect)) return i;
        }
        return -1;
    }

    /** Hotbar slot of a plain spear (unenchanted diamond_spear etc.), or -1. */
    int spearSlot(Player me) {
        // 1626 bench, 17 rounds: the spear routine dealt 0-4 a round and died in 11. A plain jab
        // is 0.96 through diamond and a smash is 4-7, so a kit with a mace and wind charges
        // plays the mace and leaves the spear in the hotbar.
        if (has(me, Items.MACE) && (has(me, Items.WIND_CHARGE) || me.getOffhandItem().getItem() == Items.WIND_CHARGE)) return -1;
        return best(me, SPEARS);
    }

    static boolean isSpear(ItemStack st) {
        if (st == null || st.isEmpty()) return false;
        Item it = st.getItem();
        for (Item s : SPEARS) if (it == s) return true;
        return false;
    }

    /**
     * Level of {@code minecraft:lunge} on a spear, else 0.
     * Plain/unenchanted spears return 0 and must never lunge. Caps at 3.
     */
    int spearLungeLevel(ItemStack st) {
        if (!isSpear(st)) return 0;
        net.minecraft.world.item.enchantment.ItemEnchantments enchants =
                st.getOrDefault(net.minecraft.core.component.DataComponents.ENCHANTMENTS,
                        net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
        if (enchants.isEmpty()) return 0;
        // Plain spears have no lunge entry. Match id string so we do not depend on ResourceLocation APIs.
        for (var entry : enchants.entrySet()) {
            String id = entry.getKey().unwrapKey().map(Object::toString).orElse(entry.getKey().toString());
            if (!id.contains("lunge")) continue;
            int lvl = entry.getIntValue();
            return lvl < 1 ? 0 : Math.min(3, lvl);
        }
        return 0;
    }

    int blockSlot(Player me) {
        for (int i = 0; i < 9; i++) {
            ItemStack st = me.getInventory().getItem(i);
            if (st.getItem() instanceof net.minecraft.world.item.BlockItem bi && bi.getBlock() != net.minecraft.world.level.block.Blocks.SOUL_SAND
                    && bi.getBlock().defaultBlockState().isSolid() && !(bi.getBlock() instanceof net.minecraft.world.level.block.FallingBlock)
                    && bi.getBlock() != net.minecraft.world.level.block.Blocks.TNT) return i;
        }
        return -1;
    }

    /** Swap an inventory item into the offhand (button 40 = offhand swap). */
    void toOffhand(Player me, Item item) {
        int slot = -1;
        for (int i = 0; i < 36; i++) if (me.getInventory().getItem(i).getItem() == item) { slot = i; break; }
        if (slot < 0) return;
        int menuSlot = slot < 9 ? 36 + slot : slot;
        invSwap(me, menuSlot, 40);
    }

    /** Whether we carry the item at all. */
    boolean has(Player me, Item item) {
        return slotOf(me, item) >= 0;
    }

    /**
     * Where the item is: its hotbar slot (0-8), else its place in the main inventory (9-35), else -1. Asking moves
     * nothing. The fight asks about a dozen items every tick only to know what it carries, and when asking pulled
     * each one into the hotbar they pushed each other out again, the inventory screen opening for every swap.
     * {@link #toHotbar} does the pulling, when the item is about to be held.
     */
    int slotOf(Player me, Item item) {
        for (int i = 0; i < 36; i++) if (me.getInventory().getItem(i).getItem() == item) return i;
        return -1;
    }

    /**
     * The hotbar slot for a place from {@link #slotOf}: the same one when it is in the hotbar already, else the
     * item is pulled up into a spare slot. That swap takes two ticks, and this is -1 until it is done.
     */
    int toHotbar(Player me, int slot) {
        if (slot < 9) return slot;
        if (slot >= 36) return -1;
        int to = spare(me);
        if (!invSwap(me, slot, to)) return -1;
        pulled[to] = ++pulls;
        return to;
    }

    /** Hotbar slot to pull an item into: see {@link Spare#slot}. */
    private int spare(Player me) {
        boolean[] empty = new boolean[9], weapon = new boolean[9];
        for (int i = 0; i < 9; i++) {
            ItemStack st = me.getInventory().getItem(i);
            empty[i] = st.isEmpty();
            weapon[i] = java.util.Arrays.asList(SWORDS).contains(st.getItem()) || java.util.Arrays.asList(AXES).contains(st.getItem())
                    || st.getItem() == Items.MACE || isSpear(st);
        }
        return Spare.slot(empty, weapon, pulled, me.getInventory().selected);
    }

    /** The choice of slot on its own, in a class that loads without the game. */
    static final class Spare {
        private Spare() {}

        /**
         * Which hotbar slot gives way to an item pulled out of the inventory. An empty one first. Else one holding
         * no melee weapon, and of those the one whose own pull is longest ago: never pulled into before anything
         * that was, the highest index among equals. The slot in hand is taken only when no other will do, since
         * what it holds may be in use. All weapons: the last slot.
         * <p>
         * Taking the same slot every time made two absent items that are wanted in turn (a mace and wind charges,
         * say) throw each other out each time.
         */
        static int slot(boolean[] empty, boolean[] weapon, long[] pulled, int selected) {
            for (int i = 8; i >= 0; i--) if (empty[i]) return i;
            int best = -1;
            for (int i = 8; i >= 0; i--) {
                if (weapon[i] || i == selected) continue;
                if (best < 0 || pulled[i] < pulled[best]) best = i;
            }
            if (best >= 0) return best;
            return selected >= 0 && selected < 9 && !weapon[selected] ? selected : 8;
        }
    }

    int best(Player me, Item[] tiers) {
        for (Item it : tiers) {
            for (int i = 0; i < 9; i++) if (me.getInventory().getItem(i).getItem() == it) return i;
        }
        return -1;
    }

    /** Crit play wants damage per swing: a sword, else an axe. */
    int weapon(Player me) {
        int s = best(me, SWORDS);
        if (s >= 0) return s;
        int a = best(me, AXES);
        if (a >= 0) return a;
        int sp = spearSlot(me); // spear bench kit has no sword or axe
        if (sp >= 0) return sp;
        return slotOf(me, Items.MACE);
    }

    /** A swap through the inventory screen: opened on one tick, clicked on a later one, then closed. */
    boolean invSwap(Player me, int menuSlot, int button) {
        net.minecraft.client.Minecraft mc = ctx.minecraft();
        if (!(mc.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen)) {
            if (mc.screen == null) {
                opened = new net.minecraft.client.gui.screens.inventory.InventoryScreen(me);
                mc.setScreen(opened);
                invTick = me.tickCount;
            }
            return false;
        }
        if (me.tickCount <= invTick) return false;
        ctx.playerController().windowClick(me.inventoryMenu.containerId, menuSlot, button, ClickType.SWAP, me);
        mc.setScreen(null);
        opened = null;
        return true;
    }

    /**
     * Once a tick, before anything asks for a swap. A swap is asked for on one tick and clicked on the next; when
     * the fight wants something else by then, nobody comes back for it and nothing else closes the screen. Only a
     * screen opened here is closed, never one the player opened.
     */
    void closeAbandoned(Player me) {
        if (opened == null) return;
        if (ctx.minecraft().screen != opened) {
            opened = null;
        } else if (me.tickCount > invTick + 1 || me.tickCount < invTick) {
            closeScreen();
        }
    }

    /** Close the screen a swap opened, if it is still up. */
    void closeScreen() {
        if (opened != null && ctx.minecraft().screen == opened) ctx.minecraft().setScreen(null);
        opened = null;
    }
}
