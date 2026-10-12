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

    /** Where a splash or lingering potion carrying the effect is, as {@link #slotOf} tells it (hotbar first), or -1. */
    int potion(Player me, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect) {
        for (int i = 0; i < 36; i++) {
            ItemStack st = me.getInventory().getItem(i);
            if (st.getItem() != Items.SPLASH_POTION && st.getItem() != Items.LINGERING_POTION) continue;
            net.minecraft.world.item.alchemy.PotionContents pc = st.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
            if (pc == null) continue;
            for (net.minecraft.world.effect.MobEffectInstance ei : pc.getAllEffects()) if (ei.getEffect().equals(effect)) return i;
        }
        return -1;
    }

    /** How many thrown potions with the effect we carry. */
    int potions(Player me, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = me.getInventory().getItem(i);
            if (st.getItem() != Items.SPLASH_POTION && st.getItem() != Items.LINGERING_POTION) continue;
            net.minecraft.world.item.alchemy.PotionContents pc = st.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
            if (pc == null) continue;
            for (net.minecraft.world.effect.MobEffectInstance ei : pc.getAllEffects()) {
                if (ei.getEffect().equals(effect)) {
                    n += st.getCount();
                    break;
                }
            }
        }
        return n;
    }

    /** How many of the item we carry, the offhand's with them. */
    int count(Player me, Item item) {
        int n = me.getOffhandItem().getItem() == item ? me.getOffhandItem().getCount() : 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = me.getInventory().getItem(i);
            if (st.getItem() == item) n += st.getCount();
        }
        return n;
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
    boolean toOffhand(Player me, Item item) {
        return toOffhand(me, item, false);
    }

    /** The same, taking the item from the main inventory before the hotbar when {@code mainFirst}: the one in the hotbar stays to hand. */
    boolean toOffhand(Player me, Item item, boolean mainFirst) {
        int slot = -1, most = -1;
        for (int k = 0; k < 36; k++) { // of several the least worn: the first found when they do not wear
            int i = mainFirst ? (k + 9) % 36 : k;
            ItemStack st = me.getInventory().getItem(i);
            if (st.getItem() != item || slot >= 0 && left(st) <= most) continue;
            slot = i;
            most = left(st);
        }
        if (slot < 0) return false;
        int menuSlot = slot < 9 ? 36 + slot : slot;
        return invSwap(me, menuSlot, 40);
    }

    /** Which of two maces a smash is taken with, in a class that loads without the game. */
    static final class MacePick {
        private MacePick() {}

        /** A mace's own damage, and the fall under which a smash is no smash. */
        static final double BASE = 6, MIN_FALL = 1.5;

        /** What the fall adds to a smash: four a block for the first three, two for the next five, one after. */
        static double smash(double fall) {
            if (fall < MIN_FALL) return 0;
            return fall <= 3 ? 4 * fall : fall <= 8 ? 12 + 2 * (fall - 3) : 22 + (fall - 8);
        }

        /** What is left of {@code dmg} behind armour, as the game works it out; each level of Breach takes 15 points off the share the armour stops. */
        static double through(double dmg, double armor, double toughness, int breach) {
            double worn = Math.max(armor * 0.2, Math.min(20, armor - dmg / (2 + toughness / 4)));
            return dmg * (1 - Math.max(0, worn / 25 - 0.15 * breach));
        }

        /** What a smash from {@code fall} blocks does to that armour with a mace of these levels. Density adds half a point a block a level. */
        static double worth(double fall, double armor, double toughness, int density, int breach) {
            return through(BASE + smash(fall) + (fall >= MIN_FALL ? 0.5 * density * fall : 0), armor, toughness, breach);
        }
    }

    /**
     * Where the mace for this swing is, as {@link #slotOf} tells it: with more than one carried, the one that does most
     * to this foe's armour from the fall we are in. Two maces are one item to the game, so changing between them does
     * not start the swing's charge over.
     */
    int mace(Player me, net.minecraft.world.entity.LivingEntity target) {
        int best = -1, n = 0;
        double bestWorth = -1;
        var reg = ctx.world().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        var density = reg.get(net.minecraft.world.item.enchantment.Enchantments.DENSITY);
        var breach = reg.get(net.minecraft.world.item.enchantment.Enchantments.BREACH);
        double armor = target.getArmorValue(), tough = target.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS);
        for (int i = 0; i < 36; i++) {
            ItemStack st = me.getInventory().getItem(i);
            if (st.getItem() != Items.MACE) continue;
            n++;
            if (left(st) <= perHit(st)) continue; // its next hit would break it: not while another is carried
            int d = density.isPresent() ? net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(density.get(), st) : 0;
            int b = breach.isPresent() ? net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(breach.get(), st) : 0;
            double worth = MacePick.worth(me.onGround() ? 0 : me.fallDistance, armor, tough, d, b);
            if (worth > bestWorth) { // the first found wins a tie, which is what slotOf gave before
                bestWorth = worth;
                best = i;
            }
        }
        return n <= 1 || best < 0 ? slotOf(me, Items.MACE) : best;
    }

    /** Where wings that still fly are, as {@link #slotOf} tells it: worn down to their last point they are carried but do not open. */
    int wings(Player me) {
        for (int i = 0; i < 36; i++) {
            ItemStack st = me.getInventory().getItem(i);
            if (st.getItem() == Items.ELYTRA && !st.nextDamageWillBreak()) return i;
        }
        return -1;
    }

    /** Hotbar slot (0-8) holding the item, -1 when it is only in the main inventory or not carried. */
    int hotbarOf(Player me, Item item) {
        for (int i = 0; i < 9; i++) if (me.getInventory().getItem(i).getItem() == item) return i;
        return -1;
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
        return Spare.slot(empty, weapon, pulled, me.getInventory().getSelectedSlot());
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

    /** What wear decides about what is held, in a class that loads without the game. */
    static final class Gear {
        private Gear() {}

        /**
         * PROVISIONAL — PENDING IN-GAME CALIBRATION. Durability at or under which a shield gives its place to a
         * fresher one while one is carried. A blocked hit costs a shield the damage it stopped and one more, and the
         * hardest single hit a player lands by hand, a critical one with a netherite axe, is 15. Lower lets a shield
         * break on the arm with a spare in the pack, higher changes shields while the worn one had blocks left.
         * To change it: the largest hits blocked in recorded fights.
         */
        static final int SHIELD_LAST = 16;

        /** What is left in an item of that much durability with that much wear: no end to one that does not wear. */
        static int left(int max, int damage) {
            return max <= 0 ? Integer.MAX_VALUE : Math.max(0, max - damage);
        }

        /**
         * Which of the candidates to fight with, -1 for none. {@code rank} is the tier, 0 the best and under 0 no
         * candidate; {@code left} what is left in each and {@code perHit} what a hit costs it. The best tier wins,
         * and the least worn of equals. One that its next hit would break is passed over unless {@code lastToo}:
         * a broken item is gone, a worn one can be mended, and it is not broken while anything else will do.
         */
        static int pick(int[] rank, int[] left, int[] perHit, boolean lastToo) {
            int best = -1;
            for (int i = 0; i < rank.length; i++) {
                if (rank[i] < 0 || !lastToo && left[i] <= perHit[i]) continue;
                if (best < 0 || rank[i] < rank[best] || rank[i] == rank[best] && left[i] > left[best]) best = i;
            }
            return best;
        }

        /**
         * PROVISIONAL — PENDING IN-GAME CALIBRATION. Ticks before a piece of armour is tried again after one was put
         * on. Lower and a server that refuses the click has the inventory opened every other tick of the fight, higher
         * and a second bare place waits that much longer. To change it: how long a refused click takes to show.
         */
        static final int WEAR_AGAIN = 40;

        /** Whether one of {@code count} may go on something other than what {@code keep} of them are kept back for. */
        static boolean spare(int count, int keep) {
            return count > Math.max(0, keep);
        }

        /**
         * Which carried piece of armour to put on, -1 for none. {@code place} is where on the body each would go,
         * under 0 for what is worn nowhere; {@code bare} which of those places have nothing on them, and
         * {@code worth} what a piece stops. A piece only goes on a bare place, never in exchange for one worn: the
         * one that stops the most, and the least worn of equals.
         */
        static int wear(int[] place, double[] worth, int[] left, boolean[] bare) {
            int best = -1;
            for (int i = 0; i < place.length; i++) {
                if (place[i] < 0 || place[i] >= bare.length || !bare[place[i]] || worth[i] <= 0) continue;
                if (best < 0 || worth[i] > worth[best] || worth[i] == worth[best] && left[i] > left[best]) best = i;
            }
            return best;
        }

        /**
         * PROVISIONAL — PENDING IN-GAME CALIBRATION. Durability at or under which a worn piece of armour gives its
         * place to a carried one. A hit costs each piece a quarter of what it did before the armour took its share,
         * three for the hardest hit landed by hand, so this is two of those and some. Lower and a mace or a blast
         * breaks the piece with a spare in the pack, higher and a piece is changed with hits left in it. To change
         * it: the most a single hit took from a piece in recorded fights.
         */
        static final int ARMOR_LAST = 8;

        /**
         * Which carried piece of armour to exchange for a worn one about to break, -1 for none. {@code worn} is what
         * is left in the piece on each place of the body, and no end to it where the place is bare or the piece
         * cannot come off. Only for a place whose piece has {@code last} or less left, and never a piece as far gone
         * itself: the one that stops the most, and the least worn of equals. A broken piece is gone and a worn one
         * can be mended, so the change is made whatever the two stop.
         */
        static int change(int[] place, double[] worth, int[] left, int[] worn, int last) {
            int best = -1;
            for (int i = 0; i < place.length; i++) {
                if (place[i] < 0 || place[i] >= worn.length || worn[place[i]] > last || worth[i] <= 0 || left[i] <= last) continue;
                if (best < 0 || worth[i] > worth[best] || worth[i] == worth[best] && left[i] > left[best]) best = i;
            }
            return best;
        }
    }

    private static int left(ItemStack st) {
        return st.isDamageableItem() ? Gear.left(st.getMaxDamage(), st.getDamageValue()) : Integer.MAX_VALUE;
    }

    /** What a hit costs the item: one for a sword, two for a tool swung as a weapon. */
    private static int perHit(ItemStack st) {
        net.minecraft.world.item.component.Weapon w = st.get(net.minecraft.core.component.DataComponents.WEAPON);
        return w == null ? 2 : Math.max(1, w.itemDamagePerAttack());
    }

    /** {@link Gear#pick} over the places {@code from} to {@code to} of the inventory, as a place in it. */
    private int pick(Player me, Item[] tiers, int from, int to, boolean lastToo) {
        int[] rank = new int[to - from], left = new int[to - from], perHit = new int[to - from];
        java.util.List<Item> order = java.util.Arrays.asList(tiers);
        for (int i = from; i < to; i++) {
            ItemStack st = me.getInventory().getItem(i);
            rank[i - from] = order.indexOf(st.getItem());
            left[i - from] = left(st);
            perHit[i - from] = perHit(st);
        }
        int at = Gear.pick(rank, left, perHit, lastToo);
        return at < 0 ? -1 : from + at;
    }

    /** The hotbar slot of the best of these to fight with: the highest tier whose next hit does not break it, the least worn of equals. -1 with none. */
    int best(Player me, Item[] tiers) {
        return pick(me, tiers, 0, 9, false);
    }

    /**
     * Crit play wants damage per swing: a sword, else an axe. One behind the hotbar is taken when the one to hand is
     * gone or on its last hit, and one on its last hit only when there is nothing else to fight with: its last hit is
     * still a hit. The place is as {@link #slotOf} tells it.
     */
    int weapon(Player me) {
        int s = best(me, SWORDS);
        if (s < 0) s = pick(me, SWORDS, 9, 36, false);
        if (s >= 0) return s;
        int a = best(me, AXES);
        if (a >= 0) return a;
        int sp = spearSlot(me); // spear bench kit has no sword or axe
        if (sp >= 0) return sp;
        a = pick(me, AXES, 9, 36, false);
        if (a >= 0) return a;
        int mace = slotOf(me, Items.MACE);
        if (mace >= 0) return mace;
        int last = pick(me, SWORDS, 0, 36, true);
        return last >= 0 ? last : pick(me, AXES, 0, 36, true);
    }

    /**
     * A shield about to break gives its place to a fresher one, while one is carried and the hand is free. Once a
     * tick, for the fight with players and the one with mobs alike. True on the ticks the change takes.
     */
    boolean freshShield(Player me) {
        ItemStack off = me.getOffhandItem();
        if (off.getItem() != Items.SHIELD || me.isUsingItem() || left(off) > Gear.SHIELD_LAST) return false;
        for (int i = 0; i < 36; i++) {
            ItemStack st = me.getInventory().getItem(i);
            if (st.getItem() != Items.SHIELD || left(st) <= left(off)) continue;
            int had = left(off);
            if (toOffhand(me, Items.SHIELD)) took = "shield left=" + had;
            return true;
        }
        return false;
    }

    private static final net.minecraft.world.entity.EquipmentSlot[] BODY = {net.minecraft.world.entity.EquipmentSlot.HEAD,
            net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET};
    private final int[] woreAt = {-Gear.WEAR_AGAIN, -Gear.WEAR_AGAIN, -Gear.WEAR_AGAIN, -Gear.WEAR_AGAIN};
    private String took;

    /** What a piece of armour stops: its armour and its toughness, nothing for what is none. */
    private static double worth(ItemStack st) {
        double sum = 0;
        for (net.minecraft.world.item.component.ItemAttributeModifiers.Entry e : st.getOrDefault(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS,
                net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY).modifiers()) {
            Object a = e.attribute().value();
            if (a == net.minecraft.world.entity.ai.attributes.Attributes.ARMOR.value()
                    || a == net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS.value()) sum += e.modifier().amount();
        }
        return sum;
    }

    /**
     * A place on the body left bare, by a piece that broke or one never put on, gets the best piece carried for it
     * while the hand is free, and a worn piece about to break is exchanged for a carried one before it does. Once a
     * tick, for the fight with players and the one with mobs alike. True on the ticks the change takes.
     */
    boolean wear(Player me) {
        if (me.isUsingItem()) return false;
        boolean[] bare = new boolean[BODY.length];
        int[] worn = new int[BODY.length];
        boolean any = false;
        for (int i = 0; i < BODY.length; i++) {
            ItemStack on = me.getItemBySlot(BODY[i]);
            bare[i] = on.isEmpty();
            // a piece bound to the body does not come off: as if it had no end,
            // nor is what stops nothing changed: wings on the chest are there to fly with, and the play that put them on takes them off
            // the wait after a click is that place's own, where a refused click would be tried over and over: another place does not wait on it
            boolean waits = me.tickCount >= woreAt[i] && me.tickCount - woreAt[i] < Gear.WEAR_AGAIN;
            worn[i] = bare[i] || waits || worth(on) <= 0 || net.minecraft.world.item.enchantment.EnchantmentHelper.has(on,
                    net.minecraft.world.item.enchantment.EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE) ? Integer.MAX_VALUE : left(on);
            if (waits) bare[i] = false;
            any |= bare[i] || worn[i] <= Gear.ARMOR_LAST;
        }
        if (!any) return false;
        int[] place = new int[36], left = new int[36];
        double[] worth = new double[36];
        java.util.List<net.minecraft.world.entity.EquipmentSlot> body = java.util.Arrays.asList(BODY);
        for (int i = 0; i < 36; i++) {
            ItemStack st = me.getInventory().getItem(i);
            place[i] = st.isEmpty() ? -1 : body.indexOf(me.getEquipmentSlotForItem(st));
            worth[i] = place[i] < 0 ? 0 : worth(st);
            left[i] = left(st);
        }
        int at = Gear.wear(place, worth, left, bare);
        if (at < 0) {
            at = Gear.change(place, worth, left, worn, Gear.ARMOR_LAST);
            if (at < 0) return false;
            // taken up, set down where the worn piece is, which comes up in its stead, and that one set down where the first lay
            int from = at < 9 ? 36 + at : at, had = worn[place[at]];
            String name = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(me.getInventory().getItem(at).getItem()).getPath();
            if (invClicks(me, 0, ClickType.PICKUP, from, 5 + place[at], from)) {
                woreAt[place[at]] = me.tickCount;
                took = "change " + name + " left=" + had;
            }
            return true;
        }
        // a quick move sends a piece of armour to its own place on the body when that place is bare
        String name = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(me.getInventory().getItem(at).getItem()).getPath();
        if (invClick(me, at < 9 ? 36 + at : at, 0, ClickType.QUICK_MOVE)) {
            woreAt[place[at]] = me.tickCount;
            took = "wear " + name;
        }
        return true;
    }

    /** What {@link #wear} or {@link #freshShield} last did, told once: null when nothing was done since it was asked. */
    String took() {
        String t = took;
        took = null;
        return t;
    }

    /** The hotbar-first place of one of these that may go on something other than what {@code keep} of them are kept back for, -1 with none to spare. */
    int spare(Player me, Item item, int keep) {
        return Gear.spare(count(me, item), keep) ? slotOf(me, item) : -1;
    }

    /** A swap through the inventory screen: opened on one tick, clicked on a later one, then closed. */
    boolean invSwap(Player me, int menuSlot, int button) {
        return invClick(me, menuSlot, button, ClickType.SWAP);
    }

    /** A click through the inventory screen: opened on one tick, clicked on a later one, then closed. */
    private boolean invClick(Player me, int menuSlot, int button, ClickType type) {
        return invClicks(me, button, type, menuSlot);
    }

    /** The same click on one place after another, all on the one tick the screen is open for. */
    private boolean invClicks(Player me, int button, ClickType type, int... menuSlots) {
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
        for (int menuSlot : menuSlots) ctx.playerController().windowClick(me.inventoryMenu.containerId, menuSlot, button, type, me);
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
