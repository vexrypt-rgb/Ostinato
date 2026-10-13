package baritone.process;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import baritone.api.process.PathingCommand;
import baritone.api.utils.input.Input;

/**
 * Armour that wears thin in a long fight is put right with bottles o' enchanting: thrown at our own feet when the
 * most worn piece that mends is low and the foe gives the room, until it is sound again or the foe is back. A piece
 * about to break is worth giving ground for, for a while; {@link #leave} says when, and the process does the leaving.
 */
final class CombatMending {
    /** What the process does for us: hotbar (pulling the bottles up from the inventory), keys, the use key, the tick's decision label and a line in the fight log. */
    interface Hands {
        boolean select(Player me, int slot);
        void key(Input in);
        void use(boolean down);
        PathingCommand decide(String d);
        void note(String line);
    }

    private final CombatInventory inv;
    private final CombatAim aimer;
    private final CombatPolicy.Mend p;
    private final Hands hands;
    private boolean mending;
    private int nextThrow, lastTick, leftAt = -100000, leaving, began;

    CombatMending(CombatInventory inv, CombatAim aimer, CombatPolicy.Mend p, Hands hands) {
        this.inv = inv;
        this.aimer = aimer;
        this.p = p;
        this.hands = hands;
    }

    void reset() {
        mending = false;
        nextThrow = lastTick = leaving = 0;
        leftAt = -100000;
    }

    /** The rules on their own, in a class that loads without the game. {@code worst} is the least share of durability left among the pieces a bottle repairs. */
    static final class Rule {
        private Rule() {}

        /** Begin when a piece is down to {@code startAt} and the foe is beyond {@code room}, or busy eating or mending itself. */
        static boolean start(double worst, double startAt, double dist, double room, boolean foeBusy) {
            return worst <= startAt && (dist > room || foeBusy);
        }

        /** Once begun, go on until the piece is back at {@code stopAt} or the foe is inside {@code close}. */
        static boolean go(double worst, double stopAt, double dist, double close) {
            return worst < stopAt && dist > close;
        }

        /**
         * Give ground to mend: a piece at {@code urgentAt} or under, the foe too near to mend beside, and not for longer
         * than {@code limit} ticks at a time. A foe that cannot be shaken is fought, and the leaving is not tried again
         * until {@code rest} ticks have passed.
         */
        static boolean leave(double worst, double urgentAt, double dist, double room, int leaving, int limit, int sinceGaveUp, int rest) {
            return worst <= urgentAt && dist <= room && leaving < limit && sinceGaveUp >= rest;
        }
    }

    /** Least share of durability left among the worn pieces a bottle would repair (armour and the offhand; the main hand holds the bottle), 1 when there is none. */
    double worst(Player me) {
        double worst = 1;
        for (EquipmentSlot s : EquipmentSlot.values()) {
            if (s == EquipmentSlot.MAINHAND) continue;
            ItemStack st = me.getItemBySlot(s);
            if (st.isEmpty() || !st.isDamaged() || !EnchantmentHelper.has(st, EnchantmentEffectComponents.REPAIR_WITH_XP)) continue;
            worst = Math.min(worst, 1 - st.getDamageValue() / (double) st.getMaxDamage());
        }
        return worst;
    }

    /** Throw bottles at our feet while it is the moment; null when it is not. {@code free} is false while something else owns our movement. */
    PathingCommand tend(Player me, double dist, boolean foeBusy, boolean free) {
        if (me.tickCount < lastTick) reset(); // respawned: the tick count started over under the old stamps
        lastTick = me.tickCount;
        int slot = free ? inv.slotOf(me, Items.EXPERIENCE_BOTTLE) : -1;
        double worst = slot < 0 ? 1 : worst(me);
        boolean was = mending;
        mending = slot >= 0 && (mending ? Rule.go(worst, p.stopAt, dist, p.close) : Rule.start(worst, p.startAt, dist, p.room, foeBusy) && dist > p.close);
        if (mending != was) { // what the bottles did is read from these two lines: the worst piece when they came out and when they were put away
            if (mending) began = me.tickCount;
            hands.note(String.format("mend %s worst=%.2f dist=%.1f%s", mending ? "start" : "stop", slot < 0 ? worst(me) : worst, dist,
                    mending ? "" : " ticks=" + (me.tickCount - began) + (slot < 0 ? " bottles=0" : "")));
        }
        if (!mending) return null;
        leaving = 0;
        if (me.isUsingItem()) { // a raised shield or a bite holds the use key: the throw needs it free
            hands.use(false);
            return hands.decide("mend");
        }
        if (dist < p.room) hands.key(Input.MOVE_BACK); // the orbs come to us within eight blocks: giving ground loses none
        if (!hands.select(me, slot)) return hands.decide("swap");
        if (me.tickCount >= nextThrow && aimer.throwStraightDown(me)) nextThrow = me.tickCount + p.gap;
        return hands.decide("mend");
    }

    /** True while ground should be given so that {@link #tend} gets its room; the process runs or pearls away on it. */
    boolean leave(Player me, double dist, boolean free) {
        if (!free || inv.slotOf(me, Items.EXPERIENCE_BOTTLE) < 0) return false;
        boolean go = Rule.leave(worst(me), p.urgentAt, dist, p.room, leaving, p.leaveTicks, me.tickCount - leftAt, p.leaveRest);
        if (go) leaving++;
        else {
            if (leaving >= p.leaveTicks) leftAt = me.tickCount; // could not shake it: fight, and do not try again for a while
            leaving = 0;
        }
        return go;
    }
}
