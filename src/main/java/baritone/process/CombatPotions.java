package baritone.process;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.phys.Vec3;
import baritone.api.process.PathingCommand;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import static baritone.process.CombatAim.press;

/**
 * Splash and lingering potions, wherever in the inventory they are: one that heals at our own feet when low, the
 * kit's boosts (strength, speed and the like) when we lack them and the foe is too far to share the splash, and
 * what harms thrown at the foe. A splash reaches four blocks from where it breaks and weakens with distance, so
 * a potion for us is thrown straight down and one for the foe is led onto its path.
 */
final class CombatPotions {
    /** What the process does for us: hotbar (pulling the potion up from the inventory), keys, the use key and the tick's decision label. */
    interface Hands {
        boolean select(Player me, int slot);
        void key(Input in);
        void use(boolean down);
        PathingCommand decide(String d);
    }

    /** The boosts worth a throw. Anything else a kit carries (slow falling, invisibility) is left for its owner to use. */
    private static final List<Holder<MobEffect>> BOOSTS = List.of(MobEffects.STRENGTH, MobEffects.SPEED, MobEffects.FIRE_RESISTANCE,
            MobEffects.RESISTANCE, MobEffects.ABSORPTION, MobEffects.REGENERATION);

    private final IPlayerContext ctx;
    private final CombatInventory inv;
    private final CombatAim aimer;
    private final CombatTargeting targeting;
    private final CombatPolicy.Pots p;
    private final Hands hands;
    private final Map<Holder<MobEffect>, Integer> harmedUntil = new HashMap<>();
    private int nextThrow, lastTick;

    CombatPotions(IPlayerContext ctx, CombatInventory inv, CombatAim aimer, CombatTargeting targeting, CombatPolicy.Pots p, Hands hands) {
        this.ctx = ctx;
        this.inv = inv;
        this.aimer = aimer;
        this.targeting = targeting;
        this.p = p;
        this.hands = hands;
    }

    /** A new fight: what the last foe was made to carry says nothing about this one. */
    void reset() {
        harmedUntil.clear();
        nextThrow = lastTick = 0;
    }

    /** The rules on their own, in a class that loads without the game. */
    static final class Rule {
        private Rule() {}

        /** A healing potion goes at or under {@code healAt}; a bite already going down is finished first unless we are at half of that. */
        static boolean heal(float hp, double healAt, int eatTicks) {
            return hp <= healAt && (eatTicks <= 0 || hp <= healAt / 2);
        }

        /**
         * A boost is thrown when it is missing or about to run out, and the foe is far enough to get none of it by the
         * time it breaks. Regeneration is only worth its throw when there is something to regenerate.
         */
        static boolean boost(int left, int renew, double dist, double boostDist, boolean regen, float missing) {
            return left < renew && dist > boostDist && (!regen || missing >= 4);
        }

        /** What harms is thrown from where a throw lands: not from inside a sword's reach, not from beyond its arc. */
        static boolean harm(double dist, double lo, double hi, boolean los, boolean stillOn) {
            return los && !stillOn && dist > lo && dist < hi;
        }
    }

    /** Heal with a potion at our feet, backing away from the foe so that as little of it as possible reaches them. Null when it is not the moment. */
    PathingCommand heal(Player me, LivingEntity target, int eatTicks) {
        if (me.tickCount < lastTick) reset(); // respawned: the tick count started over under the old stamps
        lastTick = me.tickCount;
        if (!Rule.heal(me.getHealth(), p.healAt.real(), eatTicks)) return null;
        int slot = inv.potion(me, MobEffects.INSTANT_HEALTH);
        if (slot < 0 || me.tickCount < nextThrow) return null;
        return atFeet(me, slot, true);
    }

    /** Take a boost the kit carries; {@code free} is false while something else owns our movement (a dive, a pearl in flight). */
    PathingCommand boost(Player me, LivingEntity target, double dist, boolean free) {
        if (!free || me.tickCount < nextThrow || !me.onGround()) return null;
        float missing = me.getMaxHealth() - me.getHealth();
        for (int i = 0; i < 36; i++) {
            PotionContents pc = thrown(me.getInventory().getItem(i));
            if (pc == null) continue;
            boolean wanted = false, clean = true;
            for (MobEffectInstance e : pc.getAllEffects()) {
                if (!BOOSTS.contains(e.getEffect())) {
                    clean = false; // a mixed potion (slowness with its resistance) is not a plain gain
                    break;
                }
                MobEffectInstance mine = me.getEffect(e.getEffect());
                int left = mine != null && mine.getAmplifier() >= e.getAmplifier() ? (mine.isInfiniteDuration() ? Integer.MAX_VALUE : mine.getDuration()) : 0;
                wanted |= Rule.boost(left, p.renewTicks, dist, p.boostDist, e.getEffect().equals(MobEffects.REGENERATION), missing);
            }
            if (clean && wanted) return atFeet(me, i, false);
        }
        return null;
    }

    /** Throw what harms at the foe: once for an effect that lasts, again when it has run its time. */
    PathingCommand harm(Player me, LivingEntity target, double dist, boolean los) {
        if (me.tickCount < nextThrow) return null;
        for (int i = 0; i < 36; i++) {
            PotionContents pc = thrown(me.getInventory().getItem(i));
            if (pc == null) continue;
            boolean any = false, clean = true, stillOn = true;
            int lasts = 0;
            for (MobEffectInstance e : pc.getAllEffects()) {
                any = true;
                boolean instant = e.getEffect().equals(MobEffects.INSTANT_DAMAGE);
                // the undead are healed by harming and do not feel poison
                if (e.getEffect().value().isBeneficial() || target.isInvertedHealAndHarm() && (instant || e.getEffect().equals(MobEffects.POISON))) {
                    clean = false;
                    break;
                }
                stillOn &= !instant && me.tickCount < harmedUntil.getOrDefault(e.getEffect(), 0);
                lasts = Math.max(lasts, instant ? 0 : e.getDuration());
            }
            if (!any || !clean || !Rule.harm(dist, p.harmLo, p.harmHi, los, stillOn)) continue;
            if (me.isUsingItem()) {
                hands.use(false);
                return hands.decide("pot");
            }
            Vec3 at = target.position().add(targeting.velocity(target).scale(dist / 0.5)).add(0, 0.2, 0);
            Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), at.add(0, dist * 0.12, 0), ctx.playerRotations());
            if (!hands.select(me, i)) return hands.decide("swap");
            if (!aimer.face(r.getYaw(), r.getPitch(), 2.5f)) return hands.decide("pot");
            press(ctx.minecraft().options.keyUse);
            nextThrow = me.tickCount + p.harmGap;
            for (MobEffectInstance e : pc.getAllEffects()) harmedUntil.put(e.getEffect(), me.tickCount + lasts);
            return hands.decide("pot");
        }
        return null;
    }

    /** The splash or lingering potion in the stack, null if it is anything else. */
    private static PotionContents thrown(ItemStack st) {
        if (st.getItem() != Items.SPLASH_POTION && st.getItem() != Items.LINGERING_POTION) return null;
        return st.get(DataComponents.POTION_CONTENTS);
    }

    private PathingCommand atFeet(Player me, int slot, boolean backOff) {
        if (me.isUsingItem()) { // a raised shield or a bite holds the use key: the throw needs it free
            hands.use(false);
            return hands.decide("pot");
        }
        if (backOff) hands.key(Input.MOVE_BACK); // the potion keeps our own speed, so it still breaks at our feet
        if (!hands.select(me, slot)) return hands.decide("swap");
        if (aimer.throwStraightDown(me)) nextThrow = me.tickCount + p.gap;
        return hands.decide("pot");
    }
}
