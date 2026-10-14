package baritone.process;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import baritone.api.utils.IPlayerContext;

/**
 * Threat reads for the shield: what is coming and which way a raised shield should face. Decides nothing about
 * raising it; the process owns the block state (blockTicks, lastShieldTick) and the diver/melee routines.
 */
final class CombatDefense {
    private final IPlayerContext ctx;
    private final CombatInventory inv;
    private double shEx, shEz; // smoothed horizontal offset of a diver above, for the shield facing

    CombatDefense(IPlayerContext ctx, CombatInventory inv) {
        this.ctx = ctx;
        this.inv = inv;
    }

    /**
     * Where to point a raised shield against a diver. It lands at its offset when it arrives, not the one it has now:
     * a diver closing on us at 0.07 a tick crosses zero offset before it lands and ends up behind the shield. In its
     * last few ticks aim at the extrapolated offset; null if that is too small to have a bearing (keep the facing).
     */
    Vec3 shieldBearing(Player me, LivingEntity target, Vec3 v, int lastShieldTick, double ticksLeft) {
        Vec3 tp = target.position(), mv = me.getDeltaMovement();
        // a diver sweeping over us at a steady lateral speed (pearlmace log: +1.6 to -1.0 in six ticks) lands on the far side of us
        double t = Math.hypot(v.x, v.z) > 0.15 && ticksLeft <= 8 ? Math.min(ticksLeft, 6) : 0; // extrapolating the offset swung the shield 60 degrees a tick in the logs: the diver's horizontal velocity is too noisy
        double ox = tp.x + v.x * t - (me.getX() + mv.x * t), oz = tp.z + v.z * t - (me.getZ() + mv.z * t);
        // a knock (wind burst) in the last ticks shoves us off the diver's line; it keeps falling straight, so it lands on the side we were shoved from
        boolean knocked = Math.hypot(mv.x, mv.z) > 0.25 && Math.hypot(ox, oz) <= 0.5;
        // the raw offset flips sign tick to tick; a smoothed one keeps the side the diver has been on
        if (me.tickCount - lastShieldTick > 2) { shEx = ox; shEz = oz; } else { shEx = shEx * 0.7 + ox * 0.3; shEz = shEz * 0.7 + oz * 0.3; }
        if (ticksLeft <= 3 && !knocked && t == 0) return null; // committed: chasing the last ticks' offset spins the shield off the diver
        if (Math.hypot(ox, oz) > 0.5) return new Vec3(me.getX() + ox, me.getEyeY(), me.getZ() + oz);
        if (!knocked && Math.hypot(shEx, shEz) > 0.03) return new Vec3(me.getX() + shEx, me.getEyeY(), me.getZ() + shEz);
        // a diver nearly overhead has no stable bearing (its offset flipped sign every tick and spun the shield 60 degrees a tick):
        // it chases us and lags behind our drift, so it lands on the side we are moving away from; stood still, keep the facing
        if (Math.hypot(mv.x, mv.z) > 0.08) return new Vec3(me.getX() - mv.x, me.getEyeY(), me.getZ() - mv.z);
        return null;
    }

    /** An arrow already in flight that will really hit us: the one case where a shield is the last resort against shots. */
    boolean arrowIncoming(Player me) {
        if (me.getOffhandItem().getItem() != Items.SHIELD && inv.slotOf(me, Items.SHIELD) < 0) return false;
        AABB around = me.getBoundingBox().inflate(18);
        Vec3 chest = me.position().add(0, 1, 0);
        for (AbstractArrow a : ctx.world().getEntitiesOfClass(AbstractArrow.class, around, x -> true)) {
            Vec3 v = a.getDeltaMovement();
            double v2 = v.lengthSqr();
            if (v2 < 0.25) continue;
            Vec3 to = chest.subtract(a.position());
            double t = to.dot(v) / v2; // ticks until closest approach
            if (t < 0 || t > 9) continue;
            if (to.subtract(v.scale(t)).length() < 1.4) return true;
        }
        return false;
    }

    /** The foe has a bow or crossbow drawn or loaded and aimed from range. */
    boolean foeAimed(LivingEntity target, double dist) {
        if (dist <= 5) return false;
        ItemStack held = target.getMainHandItem();
        if (held.getItem() == Items.BOW) return target.isUsingItem();
        return held.getItem() == Items.CROSSBOW && (target.isUsingItem() || net.minecraft.world.item.CrossbowItem.isCharged(held));
    }
}
