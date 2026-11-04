/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.physics;

import baritone.api.physics.BodyState;
import baritone.api.physics.PredictionResult.Assumption;
import baritone.api.physics.PredictionResult.Exposure;
import baritone.health.DamageMath;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.function.BooleanSupplier;

/**
 * Ostinato: ServerExplosion, vanilla 26.3, with the default damage calculator. Entities are hurt and pushed against
 * the world as it was before any block broke (vanilla's order). Which blocks break depends on one random power per
 * ray, radius * (0.7 + [0,1) * 0.6); attenuation along a ray only ever lowers the power, so the set the weakest roll
 * breaks is in every outcome ({@link #certain}) and the set the strongest roll breaks covers every outcome
 * ({@link #possible}). Reused by one thread; the sets are cleared per explosion.
 */
final class ExplosionSim {
    /** nextFloat's largest value. */
    private static final float MAX_ROLL = 0x1.fffffep-1f;

    final LongOpenHashSet certain = new LongOpenHashSet(), possible = new LongOpenHashSet();

    /**
     * ServerExplosion.calculateExplodedPositions for the weakest and strongest rolls; only blocks that are not air
     * are kept. False when cancelled part way.
     */
    boolean blocks(TerrainView v, double cx, double cy, double cz, float radius, BooleanSupplier cancelled) {
        certain.clear();
        possible.clear();
        float weakest = radius * (0.7f + 0.0f * 0.6f), strongest = radius * (0.7f + MAX_ROLL * 0.6f);
        int rays = 0;
        for (int xx = 0; xx < 16; xx++) {
            for (int yy = 0; yy < 16; yy++) {
                for (int zz = 0; zz < 16; zz++) {
                    if (xx != 0 && xx != 15 && yy != 0 && yy != 15 && zz != 0 && zz != 15) continue;
                    if ((++rays & 63) == 0 && cancelled.getAsBoolean()) return false;
                    double xd = (float) xx / 15.0f * 2.0f - 1.0f;
                    double yd = (float) yy / 15.0f * 2.0f - 1.0f;
                    double zd = (float) zz / 15.0f * 2.0f - 1.0f;
                    double d = Math.sqrt(xd * xd + yd * yd + zd * zd);
                    xd /= d;
                    yd /= d;
                    zd /= d;
                    ray(v, cx, cy, cz, xd, yd, zd, weakest, certain);
                    ray(v, cx, cy, cz, xd, yd, zd, strongest, possible);
                }
            }
        }
        return true;
    }

    private static void ray(TerrainView v, double x, double y, double z, double xd, double yd, double zd, float power, LongOpenHashSet out) {
        int minY = v.terrain().minY, maxY = v.terrain().maxY;
        for (float remaining = power; remaining > 0.0f; remaining -= 0.22500001f) {
            int bx = PhysMath.floor(x), by = PhysMath.floor(y), bz = PhysMath.floor(z);
            if (by < minY || by >= maxY || bx < -30000000 || bx >= 30000000 || bz < -30000000 || bz >= 30000000) break;
            BlockInfo info = v.at(bx, by, bz);
            if (info.resistance >= 0.0f) remaining -= (info.resistance + 0.3f) * 0.3f;
            if (remaining > 0.0f && info.resistance >= 0.0f) out.add(PhysMath.pack(bx, by, bz));
            x += xd * (double) 0.3f;
            y += yd * (double) 0.3f;
            z += zd * (double) 0.3f;
        }
    }

    /** Whether the possible set holds primed-TNT-to-be (TNT blocks ignite when an explosion removes them). */
    long[] chainTnt(TerrainView v) {
        long[] out = new long[8];
        int n = 0;
        for (long p : possible) {
            if (!v.at(PhysMath.unpackX(p), PhysMath.unpackY(p), PhysMath.unpackZ(p)).is(BlockInfo.TNT)) continue;
            if (n == out.length) out = java.util.Arrays.copyOf(out, n * 2);
            out[n++] = p;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /**
     * ServerExplosion.hurtEntities for one target: null when out of reach. Damage for a living target goes through
     * Player difficulty scaling, armour, Resistance and Blast Protection (in that order, as hurtServer does).
     */
    static Exposure expose(TerrainView v, double cx, double cy, double cz, float radius, BodyState t, int difficulty) {
        if (radius <= 0) return null;
        float doubleRadius = radius * 2.0f;
        double dx = t.x - cx, dy = t.y - cy, dz = t.z - cz;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz) / (double) doubleRadius;
        if (dist > 1.0) return null;
        double ox = t.x, oy = t.type == BodyState.Type.PRIMED_TNT ? t.y : t.y + (double) t.eyeHeight, oz = t.z;
        double nx = ox - cx, ny = oy - cy, nz = oz - cz;
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0E-5) {
            nx = ny = nz = 0;
        } else {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        float exposure = seenPercent(v, cx, cy, cz, t);
        double pow = (1.0 - dist) * (double) exposure;
        float raw = (float) ((pow * pow + pow) / 2.0 * 7.0 * (double) doubleRadius + 1.0);
        float dealt = raw;
        boolean lethal = false;
        if (t.isLiving()) {
            if (t.type == BodyState.Type.PLAYER) dealt = DamageMath.difficulty(dealt, difficulty);
            if (dealt > 0.0f) {
                dealt = DamageMath.afterArmor(dealt, t.armor, t.toughness, 0);
                dealt = DamageMath.afterMagic(dealt, t.resistance - 1, t.blastProtection);
            }
            lethal = dealt > 0.0f && dealt >= t.health + t.absorption;
        }
        boolean noPush = t.type == BodyState.Type.PLAYER && t.has(BodyState.FLYING) && t.has(BodyState.MAY_FLY);
        double resist = t.isLiving() ? (double) t.explosionKnockbackResistance : 0.0;
        double kb = noPush ? 0.0 : (1.0 - dist) * (double) exposure * 1.0 * (1.0 - resist);
        return new Exposure(exposure, dealt, lethal, nx * kb, ny * kb, nz * kb);
    }

    /** ServerExplosion.getSeenPercent: the share of sample points on the box with a clear collider line to the centre. */
    static float seenPercent(TerrainView v, double cx, double cy, double cz, BodyState t) {
        float halfW = t.width / 2.0f;
        double minX = t.x - (double) halfW, minY = t.y, minZ = t.z - (double) halfW;
        double maxX = t.x + (double) halfW, maxY = t.y + (double) t.height, maxZ = t.z + (double) halfW;
        double xs = 1.0 / ((maxX - minX) * 2.0 + 1.0);
        double ys = 1.0 / ((maxY - minY) * 2.0 + 1.0);
        double zs = 1.0 / ((maxZ - minZ) * 2.0 + 1.0);
        double xOffset = (1.0 - Math.floor(1.0 / xs) * xs) / 2.0;
        double zOffset = (1.0 - Math.floor(1.0 / zs) * zs) / 2.0;
        if (xs < 0.0 || ys < 0.0 || zs < 0.0) return 0.0f;
        int hits = 0, count = 0;
        for (double xx = 0.0; xx <= 1.0; xx += xs) {
            for (double yy = 0.0; yy <= 1.0; yy += ys) {
                for (double zz = 0.0; zz <= 1.0; zz += zs) {
                    double x = PhysMath.lerp(xx, minX, maxX), y = PhysMath.lerp(yy, minY, maxY), z = PhysMath.lerp(zz, minZ, maxZ);
                    if (!v.clip(x + xOffset, y, z + zOffset, cx, cy, cz, TerrainView.CLIP_COLLIDER, TerrainView.FLUID_NONE)) hits++;
                    count++;
                }
            }
        }
        return (float) hits / (float) count;
    }

    /** Assumptions an explosion at {@code tick} carries for these targets (the source itself excluded). */
    static long targetAssumptions(java.util.List<BodyState> targets, int tick, int sourceId) {
        long a = 0;
        for (BodyState t : targets) {
            if (sourceId >= 0 && t.entityId == sourceId) continue;
            if (tick > 0) a |= Assumption.TARGETS_FROZEN.bit();
            if (t.blastProtection > 0) a |= Assumption.VANILLA_ENCHANTMENTS.bit();
            if (t.has(BodyState.CLIENT_VIEW)) a |= Assumption.CLIENT_STATE.bit();
        }
        return a;
    }
}
