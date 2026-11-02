package baritone.process;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import static baritone.process.CombatGeometry.*;
import static baritone.process.CombatInventory.isSpear;

/**
 * Melee and spear reads: where a sword or axe should look, how fast we are closing along the look vector, and
 * whether the spear's piercing ray lands in the jab band. Reads only; the process decides when to click.
 */
final class CombatSwing {
    private Vec3 kineticPos;
    /** Ostinato (Scherzo): the predictive layer's word on a jab; {@link baritone.scherzo.JabAdvice#NONE} is the fight as it was. */
    baritone.scherzo.JabAdvice advice = baritone.scherzo.JabAdvice.NONE;
    /** What the client's own ray said at the last {@link #spearRayHits}, for the jab audit. */
    boolean lastClientRay;

    /**
     * Where a sword or axe looks. The nearest point of the hitbox is its edge whenever we stand off-axis,
     * and the look that arrives is a tick old: 223541 expert sat 6-8 degrees behind the edge and sent no
     * click on 8 charged ticks. Aim at the middle of the box, led by one tick of both players' motion,
     * and only slide toward the near edge when the middle is past reach.
     */
    Vec3 swingPoint(Player me, LivingEntity t, Vec3 tv) {
        Vec3 near = aimPoint(me, t);
        Vec3 eye = me.getEyePosition();
        Vec3 mid = t.getBoundingBox().getCenter();
        Vec3 lead = tv.subtract(me.getDeltaMovement()).multiply(1, 0, 1);
        for (double f : new double[]{1.0, 0.6, 0.3}) {
            Vec3 p = new Vec3(Mth.lerp(f, near.x, mid.x), near.y, Mth.lerp(f, near.z, mid.z));
            Vec3 in = t.getBoundingBox().clip(eye, p).orElse(p);
            if (eye.distanceTo(in) <= REACH - 0.04 || eye.distanceTo(near) > REACH) return p.add(lead);
        }
        return near.add(lead);
    }

    /**
     * Blocks/second along the look vector, from last tick's position change.
     * getDeltaMovement() stays near 0.15 (about 3 blocks/s) while a sprint actually covers about 0.27.
     * The server charge check uses that position delta, so the 4.6 gate never opened.
     */
    double kineticAlong(Player me) {
        Vec3 pos = me.position();
        Vec3 delta = kineticPos == null ? Vec3.ZERO : pos.subtract(kineticPos);
        kineticPos = pos;
        if (delta.lengthSqr() > 1.0) delta = me.getDeltaMovement();
        return me.getLookAngle().dot(delta) * 20.0;
    }

    /**
     * The held spear's piercing ray hits the target inside the jab band, and the jab is fully charged.
     * Vanilla rejects a spear attack below minimum_attack_charge (1.0) and misses anything the ray misses.
     */
    boolean spearRayHits(Player me, LivingEntity target) {
        ItemStack st = me.getMainHandItem();
        if (!isSpear(st) || me.cannotAttackWithItem(st, 0)) return false;
        net.minecraft.world.item.component.AttackRange range = me.getAttackRangeWith(st);
        net.minecraft.world.phys.HitResult hit = range.getClosesetHit(me, 1.0f, e -> e == target);
        lastClientRay = hit instanceof net.minecraft.world.phys.EntityHitResult er && er.getEntity() == target
                && me.getEyePosition().distanceTo(er.getLocation()) >= SPEAR_JAB_LO && me.getEyePosition().distanceTo(er.getLocation()) <= SPEAR_JAB_HI;
        // Ostinato (Scherzo): the server casts this ray against where it has the target, a round trip ahead of what we see
        int said = advice.verdict(me, target, SPEAR_JAB_LO, SPEAR_JAB_HI);
        if (said != 0) return said > 0;
        return lastClientRay;
    }

    /** Ostinato (Scherzo): where a jab looks: the predictive layer's point when it has one, else the box's near point. */
    Vec3 jabPoint(Player me, LivingEntity target) {
        Vec3 at = advice.aim(me, target);
        return at != null ? at : aimPoint(me, target);
    }
}
