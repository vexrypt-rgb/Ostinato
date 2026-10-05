package baritone.process;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Stateless combat geometry: reach and distance to a hitbox, aim points, projectile arcs and explosion
 * damage. Pure functions of the entities and positions passed in, shared by every combat mechanic.
 */
final class CombatGeometry {
    static final double REACH = 3.0, SPEAR_JAB_LO = 2.6, SPEAR_JAB_HI = 3.4;

    private CombatGeometry() {}

    /**
     * Point to look at so an arrow of the given launch speed (blocks/tick) meets a moving target: gravity 0.05 and
     * drag 0.99 per tick are simulated, so a far shot is lobbed over the drop instead of hitting the floor.
     */
    static Vec3 arcAim(Vec3 from, Vec3 pos, Vec3 vel, double speed) {
        Vec3 aim = pos;
        double flight = 0;
        for (int it = 0; it < 3; it++) {
            Vec3 to = pos.add(vel.x * flight, vel.y * flight * 0.5, vel.z * flight);
            double dx = Math.hypot(to.x - from.x, to.z - from.z), dy = to.y - from.y;
            double lo = -Math.PI / 6, hi = Math.PI / 4;
            double tFlight = dx / speed;
            for (int i = 0; i < 24; i++) {
                double mid = (lo + hi) / 2, vx = Math.cos(mid) * speed, vy = Math.sin(mid) * speed, x = 0, y = 0;
                int t = 0;
                while (x < dx && t < 400) {
                    x += vx;
                    y += vy;
                    vx *= 0.99;
                    vy = vy * 0.99 - 0.05;
                    t++;
                }
                if (y < dy) lo = mid;
                else hi = mid;
                tFlight = t;
            }
            double ang = (lo + hi) / 2;
            flight = tFlight;
            Vec3 h = new Vec3(to.x - from.x, 0, to.z - from.z);
            h = h.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : h.normalize();
            aim = from.add(h.x * Math.cos(ang) * 50, Math.sin(ang) * 50, h.z * Math.cos(ang) * 50);
        }
        return aim;
    }

    static Vec3 aimPoint(Player me, LivingEntity t) {
        Vec3 eye = me.getEyePosition();
        AABB b = t.getBoundingBox().deflate(0.05);
        return new Vec3(Mth.clamp(eye.x, b.minX, b.maxX), Mth.clamp(eye.y, b.minY + 0.2, b.maxY - 0.1), Mth.clamp(eye.z, b.minZ, b.maxZ));
    }

    /** getSeenPercent for an anchor: its own block is gone when it blows, but walls between us and it still stop the rays. */
    static double seenThroughAnchor(Vec3 at, LivingEntity e) {
        AABB bb = e.getBoundingBox();
        double sx = 1.0 / ((bb.maxX - bb.minX) * 2 + 1), sy = 1.0 / ((bb.maxY - bb.minY) * 2 + 1), sz = 1.0 / ((bb.maxZ - bb.minZ) * 2 + 1);
        if (sx < 0 || sy < 0 || sz < 0) return 1;
        BlockPos own = BlockPos.containing(at);
        int hit = 0, total = 0;
        for (double x = 0; x <= 1; x += sx) for (double y = 0; y <= 1; y += sy) for (double z = 0; z <= 1; z += sz) {
            Vec3 from = new Vec3(net.minecraft.util.Mth.lerp(x, bb.minX, bb.maxX), net.minecraft.util.Mth.lerp(y, bb.minY, bb.maxY), net.minecraft.util.Mth.lerp(z, bb.minZ, bb.maxZ));
            BlockHitResult r = e.level().clip(new net.minecraft.world.level.ClipContext(from, at, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, e));
            if (r.getType() == net.minecraft.world.phys.HitResult.Type.MISS || r.getBlockPos().equals(own)) hit++;
            total++;
        }
        return total == 0 ? 1 : (double) hit / total;
    }

    /** Vanilla end crystal (power 6) damage to {@code e} after armour. */
    static float blast(LivingEntity e, Vec3 at, double size) {
        double d = Math.sqrt(e.distanceToSqr(at)) / size;
        if (d > 1) return 0;
        // an anchor is removed before it blows, but would block its own rays here, so take it as fully exposed
        double impact = (1 - d) * (size == 12 ? ServerExplosion.getSeenPercent(at, e) : seenThroughAnchor(at, e));
        float raw = (float) ((impact * impact + impact) / 2 * 7 * size + 1);
        return CombatRules.getDamageAfterAbsorb(e, raw, e.damageSources().generic(), e.getArmorValue(), (float) e.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
    }

    /** Horizontal distance from the eye to the target hitbox. Ignores a mace hop's vertical gap. */
    static double horizontalBoxDist(Player me, Entity t) {
        Vec3 eye = me.getEyePosition();
        AABB b = t.getBoundingBox();
        double cx = Mth.clamp(eye.x, b.minX, b.maxX);
        double cz = Mth.clamp(eye.z, b.minZ, b.maxZ);
        return Math.hypot(eye.x - cx, eye.z - cz);
    }

    static double exactReach(Player me, Entity t) {
        Vec3 eye = me.getEyePosition();
        AABB b = t.getBoundingBox();
        return eye.distanceTo(new Vec3(Mth.clamp(eye.x, b.minX, b.maxX), Mth.clamp(eye.y, b.minY, b.maxY), Mth.clamp(eye.z, b.minZ, b.maxZ)));
    }

    static double eyeToBox(Player me, LivingEntity t) {
        return me.getEyePosition().distanceTo(aimPoint(me, t));
    }

    /** Blocks of air straight below the player (capped at 64). */
    static double groundGap(net.minecraft.world.entity.player.Player me) {
        net.minecraft.core.BlockPos.MutableBlockPos bp = new net.minecraft.core.BlockPos.MutableBlockPos(me.getBlockX(), 0, me.getBlockZ());
        for (int i = 0; i < 64; i++) {
            bp.setY(me.getBlockY() - i - 1);
            if (me.level().getBlockState(bp).blocksMotion()) return me.getY() - (bp.getY() + 1);
        }
        return 64;
    }
}
