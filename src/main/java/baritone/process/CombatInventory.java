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
    static final Item[] SPEARS = {Items.NETHERITE_SPEAR, Items.DIAMOND_SPEAR, Items.IRON_SPEAR, Items.COPPER_SPEAR, Items.GOLDEN_SPEAR, Items.STONE_SPEAR, Items.WOODEN_SPEAR};

    private final IPlayerContext ctx;
    private int invTick = -99;
    private final java.util.Map<Item, Integer> kitCount = new java.util.HashMap<>();

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
        int byItem = best(me, SPEARS);
        if (byItem >= 0) return byItem;
        for (int i = 0; i < 9; i++) {
            ItemStack st = me.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            // Tag covers every vanilla spear without requiring enchants or PIERCING_WEAPON
            if (st.is(net.minecraft.tags.ItemTags.SPEARS)) return i;
        }
        return -1;
    }

    static boolean isSpear(ItemStack st) {
        if (st == null || st.isEmpty()) return false;
        Item it = st.getItem();
        for (Item s : SPEARS) if (it == s) return true;
        return st.is(net.minecraft.tags.ItemTags.SPEARS);
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

    /** Whether we carry the item at all. Asking moves nothing; {@link #slotOf} does, and is for the moment of use. */
    boolean has(Player me, Item item) {
        for (int i = 0; i < 36; i++) if (me.getInventory().getItem(i).getItem() == item) return true;
        return false;
    }

    /** Hotbar slot of the item, pulling it into the hotbar if it's only in the main inventory. */
    int slotOf(Player me, Item item) {
        for (int i = 0; i < 9; i++) if (me.getInventory().getItem(i).getItem() == item) return i;
        for (int i = 9; i < 36; i++) {
            if (me.getInventory().getItem(i).getItem() == item) {
                int to = spare(me);
                return invSwap(me, i, to) ? to : -1;
            }
        }
        return -1;
    }

    /** Hotbar slot to pull an item into: an empty one, else the last that holds no sword or axe. */
    private int spare(Player me) {
        int kept = -1;
        for (int i = 8; i >= 0; i--) {
            ItemStack st = me.getInventory().getItem(i);
            if (st.isEmpty()) return i;
            if (kept < 0 && !java.util.Arrays.asList(SWORDS).contains(st.getItem()) && !java.util.Arrays.asList(AXES).contains(st.getItem())) kept = i;
        }
        return kept < 0 ? 8 : kept;
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
                mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(me));
                invTick = me.tickCount;
            }
            return false;
        }
        if (me.tickCount <= invTick) return false;
        ctx.playerController().windowClick(me.inventoryMenu.containerId, menuSlot, button, ClickType.SWAP, me);
        mc.setScreen(null);
        return true;
    }
}
