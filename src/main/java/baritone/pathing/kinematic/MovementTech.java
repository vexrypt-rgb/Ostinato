package baritone.pathing.kinematic;

import java.util.List;

/**
 * Ostinato: vanilla 26.3 numbers for movement tech, each checked against the decompiled source or the built-in
 * enchantment data (data/minecraft/enchantment/*.json). Pure functions so they can be tested without a client.
 */
public final class MovementTech {

    private MovementTech() {}

    // ---- fall damage: enchantments (EnchantmentHelper.getDamageProtection + CombatRules.getDamageAfterMagicAbsorb)

    /** feather_falling.json: damage_protection 3 a level, only for #is_fall. */
    public static final int FEATHER_EPF_PER_LEVEL = 3;
    /** protection.json: damage_protection 1 a level, any damage not bypassing invulnerability; per armour piece. */
    public static final int PROTECTION_EPF_PER_LEVEL = 1;
    /** CombatRules: the summed protection is clamped to 0..20, each point 1/25 (4%) off. */
    public static final int EPF_CAP = 20;

    public static int fallEpf(int featherFallingLevel, int... protectionLevels) {
        int epf = FEATHER_EPF_PER_LEVEL * Math.max(0, featherFallingLevel);
        for (int p : protectionLevels) epf += PROTECTION_EPF_PER_LEVEL * Math.max(0, p);
        return epf;
    }

    /** What is left of a fall's damage after the enchantments: 1 - min(epf, 20) / 25. Armour points do nothing (fall is bypasses_armor). */
    public static double epfFactor(int epf) {
        return 1 - Math.max(0, Math.min(EPF_CAP, epf)) / 25.0;
    }

    /**
     * LivingEntity: floor(fall - safe) raw, then Resistance (getDamageAfterMagicAbsorb, (25 - 5 * (amp + 1)) / 25),
     * then the enchantments. Not rounded again: health is a float.
     */
    public static double fallDamage(double blocksFallen, double safeFall, double resistanceFactor, int epf) {
        double raw = Math.floor(blocksFallen + 1.0E-6 - safeFall);
        return raw <= 0 ? 0 : raw * resistanceFactor * epfFactor(epf);
    }

    // ---- Riptide (TridentItem.releaseUsing, riptide.json)

    public static final int RIPTIDE_MIN_CHARGE_TICKS = 10, RIPTIDE_SPIN_TICKS = 20, RIPTIDE_DURABILITY = 1;
    /** player.move(SELF, (0, 1.1999999284744263, 0)) when the launch starts on the ground. */
    public static final double RIPTIDE_GROUND_LIFT = 1.1999999284744263;

    /** trident_spin_attack_strength: linear, base 1.5, +0.75 a level above the first. */
    public static float riptideStrength(int level) {
        return level <= 0 ? 0 : 1.5F + 0.75F * (level - 1);
    }

    /** The push, in floats as vanilla: the look vector scaled to the strength. */
    public static double[] riptidePush(float yawDeg, float pitchDeg, int level) {
        float s = riptideStrength(level);
        float yr = yawDeg * ((float) Math.PI / 180), xr = pitchDeg * ((float) Math.PI / 180);
        float xd = -(float) Math.sin(yr) * (float) Math.cos(xr), yd = -(float) Math.sin(xr), zd = (float) Math.cos(yr) * (float) Math.cos(xr);
        float dist = (float) Math.sqrt(xd * xd + yd * yd + zd * zd);
        return new double[]{xd * (s / dist), yd * (s / dist), zd * (s / dist)};
    }

    /**
     * Horizontal distance one riptide launch carries over open flat ground (rain, no water), from standing: the ground
     * lift, the push, then plain air physics in PlayerSim with W held, until it touches down.
     */
    public static double riptideFlatReach(int level, float pitchDeg) {
        double[] p = riptidePush(-90, pitchDeg, level);
        PlayerSim s = new PlayerSim(floorAt(0));
        s.x = 0.5; s.z = 0.5; s.y = RIPTIDE_GROUND_LIFT;
        s.vx = p[0]; s.vy = p[1]; s.vz = p[2];
        for (int t = 0; t < 400 && !s.onGround; t++) s.tick(-90, true, false, false);
        return s.x - 0.5;
    }

    // ---- wind charge (WindChargeItem, AbstractWindCharge, WindCharge, ServerExplosion.hurtEntities)

    public static final float WIND_SHOOT_POWER = 1.5F, WIND_RADIUS = 1.2F, WIND_KNOCKBACK = 1.22F;
    /** Explodes 0.25 out from the face it hit (AbstractWindCharge.onHitBlock). */
    public static final double WIND_HIT_OFFSET = 0.25;
    /** Items.WIND_CHARGE useCooldown(0.5f). */
    public static final int WIND_COOLDOWN_TICKS = 10;

    /**
     * Knockback on a player whose feet are at f from a burst at c: distance from the feet over twice the radius,
     * direction from the burst to the eyes, (1 - d) * exposure * 1.22. Nothing past twice the radius.
     */
    public static double[] windImpulse(double fx, double fy, double fz, double eyeHeight, double cx, double cy, double cz, double exposure) {
        double d = Math.sqrt((fx - cx) * (fx - cx) + (fy - cy) * (fy - cy) + (fz - cz) * (fz - cz)) / (WIND_RADIUS * 2.0F);
        if (d > 1) return new double[3];
        double ex = fx - cx, ey = fy + eyeHeight - cy, ez = fz - cz, len = Math.sqrt(ex * ex + ey * ey + ez * ez);
        if (len < 1.0E-4) return new double[3];
        double k = (1 - d) * exposure * WIND_KNOCKBACK / len;
        return new double[]{ex * k, ey * k, ez * k};
    }

    /** A charge thrown at the floor under our own feet while standing: the burst is 0.25 over the floor. */
    public static double[] windSelfLaunch(double eyeHeight) {
        return windImpulse(0, 0, 0, eyeHeight, 0, WIND_HIT_OFFSET, 0, 1);
    }

    /** Feet height a standing self-launch reaches (the impulse is added: Entity.push). */
    public static double windSelfLaunchApex(double eyeHeight) {
        double v = windSelfLaunch(eyeHeight)[1], y = 0, top = 0;
        for (int t = 0; t < 200 && v > 0; t++) {
            y += v;
            top = Math.max(top, y);
            v = (v - 0.08) * 0.98;
        }
        return top;
    }

    /**
     * Fall damage after a wind-charge launch (LivingEntity.causeFallDamage, ServerPlayer.onExplosionHit): only the
     * part below the height the burst hit us at counts, min(fallDistance, impactY - landY).
     */
    public static double windFallDistance(double fallDistance, double impactY, double landY) {
        return Math.max(0, Math.min(fallDistance, impactY - landY));
    }

    // ---- Lunge (lunge.json, ApplyEntityImpulse, PiercingWeapon.attack, Item.Properties.spear)

    /** apply_impulse 0.458 a level along the look, then scaled (1, 0, 1): pitch costs cos(pitch). */
    public static double lungeImpulse(int level, float pitchDeg) {
        return level <= 0 ? 0 : 0.458F * level * Math.cos(Math.toRadians(pitchDeg));
    }

    /** apply_exhaustion 4 a level (4 exhaustion is one hunger point). */
    public static float lungeExhaustion(int level) {
        return 4.0F * level;
    }

    /** change_item_damage 1 a jab; needs food >= 7, not in water, not gliding, not riding. */
    public static final int LUNGE_DURABILITY = 1, LUNGE_MIN_FOOD = 7;

    /** A jab needs a full charge (MINIMUM_ATTACK_CHARGE 1): one every attackDuration seconds of the spear. */
    public static int lungeCooldownTicks(float attackDuration) {
        return (int) (attackDuration * 20.0F);
    }

    /** How far a lunge off a flat sprint carries in 12 ticks beyond the plain sprint, optionally jumped the same tick. */
    public static double lungeGain(int level, boolean jump) {
        return lungeRun(level, jump) - lungeRun(0, jump);
    }

    private static double lungeRun(int level, boolean jump) {
        PlayerSim s = new PlayerSim(floorAt(0));
        s.x = 0.5; s.z = 0.5; s.onGround = true;
        for (int t = 0; t < 40; t++) s.tick(-90, true, true, false);
        double start = s.x;
        s.vx += lungeImpulse(level, 0);
        for (int t = 0; t < 12; t++) s.tick(-90, true, true, jump && t == 0);
        return s.x - start;
    }

    // ---- elytra (LivingEntity.updateFallFlyingMovement, FireworkRocketEntity.tick)

    /** One tick of gliding velocity (applied before the move). */
    public static void glide(double[] v, float yawDeg, float pitchDeg, double gravity) {
        double yr = Math.toRadians(yawDeg), xr = Math.toRadians(pitchDeg);
        double lx = -Math.sin(yr) * Math.cos(xr), lz = Math.cos(yr) * Math.cos(xr);
        float lean = pitchDeg * ((float) Math.PI / 180);
        double lookH = Math.sqrt(lx * lx + lz * lz), moveH = Math.sqrt(v[0] * v[0] + v[2] * v[2]);
        double lift = Math.cos(lean) * Math.cos(lean);
        v[1] += gravity * (-1.0 + lift * 0.75);
        if (v[1] < 0 && lookH > 0) {
            double c = v[1] * -0.1 * lift;
            v[0] += lx * c / lookH; v[1] += c; v[2] += lz * c / lookH;
        }
        if (lean < 0 && lookH > 0) {
            double c = moveH * -Math.sin(lean) * 0.04;
            v[0] += -lx * c / lookH; v[1] += c * 3.2; v[2] += -lz * c / lookH;
        }
        if (lookH > 0) {
            v[0] += (lx / lookH * moveH - v[0]) * 0.1;
            v[2] += (lz / lookH * moveH - v[2]) * 0.1;
        }
        v[0] *= 0.99F; v[1] *= 0.98F; v[2] *= 0.99F;
    }

    /** A rocket attached to us: v += look * 0.1 + (look * 1.5 - v) * 0.5, each tick of its life. */
    public static void firework(double[] v, float yawDeg, float pitchDeg) {
        double yr = Math.toRadians(yawDeg), xr = Math.toRadians(pitchDeg);
        double lx = -Math.sin(yr) * Math.cos(xr), ly = -Math.sin(xr), lz = Math.cos(yr) * Math.cos(xr);
        v[0] += lx * 0.1 + (lx * 1.5 - v[0]) * 0.5;
        v[1] += ly * 0.1 + (ly * 1.5 - v[1]) * 0.5;
        v[2] += lz * 0.1 + (lz * 1.5 - v[2]) * 0.5;
    }

    /** Shortest life of a rocket: 10 * (flight + 1), plus random 0..5 and 0..6 that are taken as 0. */
    public static int fireworkMinLife(int flight) {
        return 10 * (flight + 1);
    }

    /**
     * Ground takeoff on flat ground: a sprint jump, the glide opened the first tick the jump key can be freshly pressed
     * while airborne (2 ticks after leaving), a rocket fired {@code rocketDelay} ticks into the glide, nose at
     * {@code pitchDeg} (negative is up). Returns {lowest feet height after opening, feet height after 60 ticks,
     * horizontal blocks covered in those ticks}; a lowest height at or below 0 means it touched down and the glide ended.
     */
    public static double[] groundTakeoff(float pitchDeg, int rocketDelay, int flight) {
        PlayerSim s = new PlayerSim(floorAt(0));
        s.x = 0.5; s.z = 0.5; s.onGround = true;
        for (int t = 0; t < 30; t++) s.tick(-90, true, true, false);
        s.tick(-90, true, true, true);
        s.tick(-90, true, true, false);
        double[] v = {s.vx, s.vy, s.vz};
        double y = s.y, x = s.x, low = Double.MAX_VALUE;
        int life = 0;
        for (int t = 0; t < 60; t++) {
            if (t == rocketDelay) life = fireworkMinLife(flight);
            if (life > 0) {
                firework(v, -90, pitchDeg);
                life--;
            }
            glide(v, -90, pitchDeg, 0.08);
            y += v[1];
            x += v[0];
            low = Math.min(low, y);
            if (y <= 0) return new double[]{y, y, x - s.x};
        }
        return new double[]{low, y, x - s.x};
    }

    // ---- ice (Blocks: ICE, PACKED_ICE, FROSTED_ICE friction 0.98, BLUE_ICE 0.989)

    public static final float ICE_FRICTION = 0.98F, BLUE_ICE_FRICTION = 0.989F;

    /** Whether a block friction is ice-like: a landing on it slides on. */
    public static boolean slippery(float friction) {
        return friction > 0.9F;
    }

    /** How far a player sliding at v a tick on the ground goes with no input: v / (1 - friction * 0.91). */
    public static double slideDistance(double v, float friction) {
        return v / (1 - friction * 0.91F);
    }

    /** Steady blocks a tick on a floor of this friction: plain sprint, sprint-jumping, or sprint-jumping under a ceiling 2 up (jump tapped). */
    public static double steadySpeed(float friction, boolean jump, boolean headHitter) {
        PlayerSim s = new PlayerSim(new PlayerSim.World() {
            @Override
            public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                for (int bx = PlayerSim.floor(minX); bx <= PlayerSim.floor(maxX); bx++)
                    for (int by = PlayerSim.floor(minY); by <= PlayerSim.floor(maxY); by++)
                        if (by == -1 || headHitter && by == 2)
                            for (int bz = PlayerSim.floor(minZ); bz <= PlayerSim.floor(maxZ); bz++)
                                out.add(new double[]{bx, by, bz, bx + 1, by + 1, bz + 1});
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return friction;
            }
        });
        s.x = 0.5; s.z = 0.5; s.onGround = true;
        // jump tapped: a fresh press each landing, as holding it re-jumps only every 10 ticks (LivingEntity.noJumpDelay)
        boolean prev = false;
        double start = 0;
        for (int t = 0; t < 600; t++) {
            boolean j = jump && s.onGround && !prev;
            s.tick(-90, true, true, j);
            prev = j;
            if (t == 399) start = s.x;
        }
        return (s.x - start) / 200;
    }

    private static PlayerSim.World floorAt(int top) {
        return new PlayerSim.World() {
            @Override
            public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                for (int bx = PlayerSim.floor(minX); bx <= PlayerSim.floor(maxX); bx++)
                    for (int by = PlayerSim.floor(minY); by <= PlayerSim.floor(maxY); by++)
                        if (by == top - 1)
                            for (int bz = PlayerSim.floor(minZ); bz <= PlayerSim.floor(maxZ); bz++)
                                out.add(new double[]{bx, by, bz, bx + 1, by + 1, bz + 1});
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return 0.6f;
            }
        };
    }
}
