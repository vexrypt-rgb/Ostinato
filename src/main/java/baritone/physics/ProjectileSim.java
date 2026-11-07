/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.physics;

import baritone.api.physics.BodyState;

import java.util.List;

/**
 * Ostinato: one tick of a projectile, vanilla 26.3, by family: ThrowableProjectile (gravity, drag, then the hit along
 * the move), AbstractArrow (in-ground check, water drag, move along the old motion, then air drag and gravity) and
 * AbstractHurtingProjectile (acceleration along the motion, drag, hit). Hits are a block face or one of the given
 * targets, held where they were. Every target is treated as hittable (no owner or team filter). Mutable; one
 * simulation owns it.
 */
final class ProjectileSim {
    final Body b = new Body();
    private BodyState.Type type;
    private float drag, waterDrag;
    private double acceleration;
    private int age;
    private List<BodyState> targets;

    // the hit of the last tick (hitTarget -1 for a block); hit false when none
    boolean hit, inGround;
    double hitX, hitY, hitZ, hitSpeed;
    int hitTarget, hitBlockX, hitBlockY, hitBlockZ, hitFace;

    ProjectileSim set(BodyState s, List<BodyState> targets) {
        b.set(s);
        type = s.type;
        drag = s.drag;
        waterDrag = s.waterDrag;
        acceleration = s.acceleration;
        age = s.age;
        inGround = s.has(BodyState.IN_GROUND);
        this.targets = targets;
        hit = false;
        return this;
    }

    void tick(TerrainView v) {
        b.startTick();
        hit = false;
        age++; // ServerLevel.tickNonPassenger increments tickCount before tick()
        switch (type) {
            case ARROW, TRIDENT -> arrow(v);
            case HURTING_PROJECTILE -> hurting(v);
            default -> throwable(v);
        }
    }

    private void throwable(TerrainView v) {
        if (b.gravity != 0.0) b.vy -= b.gravity;
        double k = b.inWater ? waterDrag : drag;
        b.vx *= k;
        b.vy *= k;
        b.vz *= k;
        moveAndHit(v);
    }

    private void hurting(TerrainView v) {
        double len = Math.sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz);
        double nx = 0, ny = 0, nz = 0;
        if (len >= 1.0E-5) {
            nx = b.vx / len;
            ny = b.vy / len;
            nz = b.vz / len;
        }
        double k = b.inWater ? waterDrag : drag;
        b.vx = (b.vx + nx * acceleration) * k;
        b.vy = (b.vy + ny * acceleration) * k;
        b.vz = (b.vz + nz * acceleration) * k;
        moveAndHit(v);
    }

    /** ProjectileUtil.getHitResultOnMoveVector, setPos, applyEffectsFromBlocks, then Entity.baseTick's fluid update. */
    private void moveAndHit(TerrainView v) {
        double fx = b.x, fy = b.y, fz = b.z, mx = b.vx, my = b.vy, mz = b.vz;
        double tx = fx + mx, ty = fy + my, tz = fz + mz;
        boolean block = v.clip(fx, fy, fz, tx, ty, tz, TerrainView.CLIP_COLLIDER, TerrainView.FLUID_NONE);
        if (block) {
            tx = v.hitX;
            ty = v.hitY;
            tz = v.hitZ;
            recordBlock(v, mx, my, mz);
        }
        // the entity search box: own box expanded by the motion and inflated by 1
        double sMinX = fx - b.halfW + Math.min(mx, 0) - 1, sMaxX = fx + b.halfW + Math.max(mx, 0) + 1;
        double sMinY = fy + Math.min(my, 0) - 1, sMaxY = fy + b.height + Math.max(my, 0) + 1;
        double sMinZ = fz - b.halfW + Math.min(mz, 0) - 1, sMaxZ = fz + b.halfW + Math.max(mz, 0) + 1;
        double margin = margin();
        double best = Double.MAX_VALUE;
        for (int i = 0; i < targets.size(); i++) {
            BodyState t = targets.get(i);
            float hw = t.width / 2.0f;
            double minX = t.x - hw, minY = t.y, minZ = t.z - hw, maxX = t.x + hw, maxY = t.y + t.height, maxZ = t.z + hw;
            if (!(minX < sMaxX && maxX > sMinX && minY < sMaxY && maxY > sMinY && minZ < sMaxZ && maxZ > sMinZ)) continue;
            if (!v.boxClip(minX - margin, minY - margin, minZ - margin, maxX + margin, maxY + margin, maxZ + margin, fx, fy, fz, tx, ty, tz)) continue;
            double d = sq(v.hitX - fx, v.hitY - fy, v.hitZ - fz);
            if (d < best) {
                best = d;
                recordTarget(i, v.hitX, v.hitY, v.hitZ, mx, my, mz);
            }
        }
        if (hit) {
            b.x = hitX;
            b.y = hitY;
            b.z = hitZ;
        } else {
            b.x = fx + mx;
            b.y = fy + my;
            b.z = fz + mz;
        }
        b.applyEffectsFromBlocks(v);
        b.updateFluids(v);
    }

    private void arrow(TerrainView v) {
        BlockInfo at = v.at(PhysMath.floor(b.x), PhysMath.floor(b.y), PhysMath.floor(b.z));
        if (!inGround && at.hasCollision()) {
            double px = b.x - PhysMath.floor(b.x), py = b.y - PhysMath.floor(b.y), pz = b.z - PhysMath.floor(b.z);
            double[] bx = at.boxes;
            for (int i = 0; i < bx.length; i += 6) {
                if (px >= bx[i] && px < bx[i + 3] && py >= bx[i + 1] && py < bx[i + 4] && pz >= bx[i + 2] && pz < bx[i + 5]) {
                    b.vx = b.vy = b.vz = 0;
                    inGround = true;
                    break;
                }
            }
        }
        if (inGround) return;
        double fx = b.x, fy = b.y, fz = b.z, mx = b.vx, my = b.vy, mz = b.vz;
        if (b.inWater) {
            b.vx *= waterDrag;
            b.vy *= waterDrag;
            b.vz *= waterDrag;
        }
        double tx = fx + mx, ty = fy + my, tz = fz + mz;
        if (v.clip(fx, fy, fz, tx, ty, tz, TerrainView.CLIP_COLLIDER, TerrainView.FLUID_NONE)) {
            tx = v.hitX;
            ty = v.hitY;
            tz = v.hitZ;
            recordBlock(v, mx, my, mz);
        }
        // AbstractArrow.findHitEntities: searched with the current motion, ordered by distance to the target's position
        double cx = b.vx, cy = b.vy, cz = b.vz;
        double sMinX = fx - b.halfW + Math.min(cx, 0) - 1, sMaxX = fx + b.halfW + Math.max(cx, 0) + 1;
        double sMinY = fy + Math.min(cy, 0) - 1, sMaxY = fy + b.height + Math.max(cy, 0) + 1;
        double sMinZ = fz - b.halfW + Math.min(cz, 0) - 1, sMaxZ = fz + b.halfW + Math.max(cz, 0) + 1;
        double margin = margin();
        double best = Double.MAX_VALUE;
        for (int i = 0; i < targets.size(); i++) {
            BodyState t = targets.get(i);
            float hw = t.width / 2.0f;
            double minX = t.x - hw, minY = t.y, minZ = t.z - hw, maxX = t.x + hw, maxY = t.y + t.height, maxZ = t.z + hw;
            if (!(minX < sMaxX && maxX > sMinX && minY < sMaxY && maxY > sMinY && minZ < sMaxZ && maxZ > sMinZ)) continue;
            double ex, ey, ez;
            if (v.boxClip(minX, minY, minZ, maxX, maxY, maxZ, fx, fy, fz, tx, ty, tz)) {
                ex = v.hitX;
                ey = v.hitY;
                ez = v.hitZ;
            } else {
                if (margin <= 0.0 || !v.boxClip(minX - margin, minY - margin, minZ - margin, maxX + margin, maxY + margin, maxZ + margin, fx, fy, fz, tx, ty, tz)) continue;
                ex = v.hitX;
                ey = v.hitY;
                ez = v.hitZ;
                // the margin hit counts when the line from it to the target's centre reaches the box before a block
                double ccx = (minX + maxX) * 0.5, ccy = (minY + maxY) * 0.5, ccz = (minZ + maxZ) * 0.5;
                double gx = ccx, gy = ccy, gz = ccz;
                if (v.clip(ex, ey, ez, ccx, ccy, ccz, TerrainView.CLIP_COLLIDER, TerrainView.FLUID_NONE)) {
                    gx = v.hitX;
                    gy = v.hitY;
                    gz = v.hitZ;
                }
                if (!v.boxClip(minX, minY, minZ, maxX, maxY, maxZ, ex, ey, ez, gx, gy, gz)) continue;
            }
            double d = sq(t.x - fx, t.y - fy, t.z - fz);
            if (d < best) {
                best = d;
                recordTarget(i, ex, ey, ez, mx, my, mz);
            }
        }
        if (hit) {
            b.x = hitX;
            b.y = hitY;
            b.z = hitZ;
        } else {
            b.x = tx;
            b.y = ty;
            b.z = tz;
        }
        b.applyEffectsFromBlocks(v);
        if (!b.inWater) {
            b.vx *= drag;
            b.vy *= drag;
            b.vz *= drag;
        }
        if (b.gravity != 0.0) b.vy -= b.gravity;
        b.updateFluids(v);
    }

    private double margin() {
        return Math.max(0.0f, Math.min(0.3f, (float) (age - 2) / 20.0f));
    }

    private void recordBlock(TerrainView v, double mx, double my, double mz) {
        hit = true;
        hitTarget = -1;
        hitX = v.hitX;
        hitY = v.hitY;
        hitZ = v.hitZ;
        hitBlockX = v.hitBlockX;
        hitBlockY = v.hitBlockY;
        hitBlockZ = v.hitBlockZ;
        hitFace = v.hitFace;
        hitSpeed = Math.sqrt(mx * mx + my * my + mz * mz);
    }

    private void recordTarget(int i, double x, double y, double z, double mx, double my, double mz) {
        hit = true;
        hitTarget = i;
        hitX = x;
        hitY = y;
        hitZ = z;
        hitBlockX = PhysMath.floor(x);
        hitBlockY = PhysMath.floor(y);
        hitBlockZ = PhysMath.floor(z);
        hitFace = -1;
        hitSpeed = Math.sqrt(mx * mx + my * my + mz * mz);
    }

    private static double sq(double a, double b, double c) {
        return a * a + b * b + c * c;
    }
}
