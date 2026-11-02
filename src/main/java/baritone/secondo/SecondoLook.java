/*
 * Ostinato: new file (Secondo). Not part of upstream Baritone.
 */

package baritone.secondo;

import baritone.secondo.SecondoPolicy.Observation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Ostinato: read Primo into a policy observation.
 * A swing toward a living entity is a fight. A swing into a block is a break.
 * The nearest dropped item is reported; the policy applies the pickup radius.
 */
public final class SecondoLook {

    private static final double SWING_REACH = 4.5;
    private static final double BLOCK_REACH = 5.0;

    private SecondoLook() {
    }

    public static Observation observe(Player self, Player primo, Iterable<Entity> entities) {
        int sx = self.blockPosition().getX();
        int sy = self.blockPosition().getY();
        int sz = self.blockPosition().getZ();
        int px = primo.blockPosition().getX();
        int py = primo.blockPosition().getY();
        int pz = primo.blockPosition().getZ();

        boolean fighting = false;
        String threat = null;
        boolean breaking = false;
        int bx = 0, by = 0, bz = 0;

        LivingEntity hurtBy = primo.getLastHurtByMob();
        if (primo.hurtTime > 0 && hurtBy != null && hurtBy != self && hurtBy.isAlive()) {
            fighting = true;
            threat = hurtBy.getName().getString();
        }
        if (primo.isSwinging()) {
            LivingEntity aimed = aimed(primo, self, entities);
            if (aimed != null) {
                fighting = true;
                if (threat == null) {
                    threat = aimed.getName().getString();
                }
            } else {
                BlockHitResult hit = blockInFront(primo);
                if (hit != null) {
                    breaking = true;
                    bx = hit.getBlockPos().getX();
                    by = hit.getBlockPos().getY();
                    bz = hit.getBlockPos().getZ();
                }
            }
        }

        boolean drop = false;
        int dx = 0, dy = 0, dz = 0;
        double best = Double.MAX_VALUE;
        if (entities != null) {
            for (Entity e : entities) {
                if (!(e instanceof ItemEntity)) {
                    continue;
                }
                double d = e.distanceToSqr(self);
                if (d < best) {
                    best = d;
                    drop = true;
                    dx = e.blockPosition().getX();
                    dy = e.blockPosition().getY();
                    dz = e.blockPosition().getZ();
                }
            }
        }
        return new Observation(true, primo.isAlive(), sx, sy, sz, px, py, pz, breaking, bx, by, bz, drop, dx, dy, dz, fighting, threat);
    }

    private static LivingEntity aimed(Player primo, Player self, Iterable<Entity> entities) {
        if (entities == null) {
            return null;
        }
        Vec3 look = primo.getViewVector(1.0F);
        Vec3 eye = primo.getEyePosition();
        LivingEntity best = null;
        double bestD = SWING_REACH * SWING_REACH;
        for (Entity e : entities) {
            if (!(e instanceof LivingEntity le) || le == primo || le == self || !le.isAlive()) {
                continue;
            }
            Vec3 to = le.getEyePosition().subtract(eye);
            double d2 = to.lengthSqr();
            if (d2 > bestD || d2 < 1.0e-4) {
                continue;
            }
            if (to.normalize().dot(look) < 0.7) {
                continue;
            }
            bestD = d2;
            best = le;
        }
        return best;
    }

    private static BlockHitResult blockInFront(Player primo) {
        if (!(primo.level() instanceof ClientLevel)) {
            return null;
        }
        Vec3 eye = primo.getEyePosition();
        Vec3 end = eye.add(primo.getViewVector(1.0F).scale(BLOCK_REACH));
        HitResult hit = primo.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, primo));
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
            return block;
        }
        return null;
    }
}
