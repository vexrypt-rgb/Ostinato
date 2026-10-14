package baritone.pathing.kinematic;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ostinato: what the potion effects do to a jump or a fall, flown in {@link PlayerSim} over a made-up course once
 * and remembered, so the planner can ask per node for next to nothing.
 */
public final class EffectReach {

    private EffectReach() {}

    /** How far past the landing block's near edge (less the hitbox overhang) the sim has to come down. */
    public static final double MARGIN = 0.1;

    private static final ConcurrentHashMap<Long, Boolean> LANDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Double> APEX = new ConcurrentHashMap<>();

    /** Vanilla's getJumpBoostPower for this many levels (amplifier + 1). */
    public static double jumpBoostPower(int levels) {
        return levels <= 0 ? 0 : 0.1F * (float) levels;
    }

    /**
     * Whether a sprint jump of {@code dist} blocks along the axis (block 0 to block dist), landing {@code dy} blocks
     * up, lands with {@code levels} of Jump Boost at ground speed {@code speedScale}. Started at a standstill on the
     * take-off centre (a block behind it with {@code runUp}) and jumped where MovementParkour jumps.
     */
    public static boolean lands(int levels, double speedScale, int dist, int dy, boolean runUp) {
        long key = ((long) levels << 40) | ((long) Math.round(speedScale * 1000) << 16) | ((long) dist << 8) | ((long) dy << 4) | (runUp ? 1 : 0);
        return LANDS.computeIfAbsent(key, k -> roll(jumpBoostPower(levels), speedScale, dist, dy, runUp));
    }

    static boolean roll(double jumpBoost, double speedScale, int dist, int dy, boolean runUp) {
        PlayerSim s = new PlayerSim(course(dist, dy, runUp));
        s.x = runUp ? -0.5 : 0.5;
        s.z = 0.5;
        s.onGround = true;
        s.jumpBoost = jumpBoost;
        s.speedScale = speedScale;
        boolean jumped = false;
        for (int t = 0; t < 100; t++) {
            boolean jump = false;
            if (!jumped && s.onGround) {
                double from = s.x - 0.5;
                if (dy == 0 && dist == 3) {
                    jump = from >= 0.7;
                } else if (dy == 0 && dist == 5) {
                    jump = from + Math.abs(s.vx) / 0.546 >= 1.0;
                } else {
                    jump = s.x >= 1.0; // the feet have left the take-off block
                }
            }
            s.tick(-90, true, true, jump);
            jumped |= jump;
            if (!jumped && !s.onGround && s.x > 1.3) return false; // walked off the edge before jumping
            if (jumped && s.onGround) {
                return Math.abs(s.y - dy) < 1e-6 && s.x >= dist - 0.3 + MARGIN;
            }
            if (s.y < -2) return false;
        }
        return false;
    }

    /** Take-off floor at x 0 (and -1 with a run-up), a landing platform from x = dist with its top dy up, open sky. */
    private static PlayerSim.World course(int dist, int dy, boolean runUp) {
        return new PlayerSim.World() {
            @Override
            public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                for (int bx = PlayerSim.floor(minX); bx <= PlayerSim.floor(maxX); bx++) {
                    for (int by = PlayerSim.floor(minY); by <= PlayerSim.floor(maxY); by++) {
                        boolean solid = by == -1 && bx <= 0 && bx >= (runUp ? -1 : 0)
                                || bx >= dist && bx <= dist + 3 && by >= -1 && by <= dy - 1;
                        if (solid) {
                            for (int bz = PlayerSim.floor(minZ); bz <= PlayerSim.floor(maxZ); bz++) {
                                out.add(new double[]{bx, by, bz, bx + 1, by + 1, bz + 1});
                            }
                        }
                    }
                }
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return 0.6f;
            }
        };
    }

    /** The highest the feet get on a jump with this many levels of Jump Boost (no head hitter). */
    public static double apex(int levels) {
        return APEX.computeIfAbsent(levels, l -> {
            double v = 0.42 + jumpBoostPower(l), y = 0, top = 0;
            for (int t = 0; t < 60 && v > 0; t++) {
                y += v;
                top = Math.max(top, y);
                v = (v - 0.08) * 0.98;
            }
            return top;
        });
    }

    public static final int MAX_BLOCKS = 4096;
    private static volatile double[] slowFall;

    /**
     * Ticks to fall {@code blocks} under Slow Falling, walked off at rest: move, then gravity 0.01 and the 0.98 drag,
     * as LivingEntity.travelInAir does it (terminal speed 0.49 blocks a tick).
     */
    public static double slowFallTicks(int blocks) {
        double[] table = slowFall;
        if (table == null) {
            table = new double[MAX_BLOCKS + 1];
            double depth = 0, v = 0;
            int t = 0, n = 1;
            while (n <= MAX_BLOCKS) {
                depth -= v;
                v = (v - 0.01) * 0.98;
                t++;
                while (n <= MAX_BLOCKS && depth >= n) {
                    table[n++] = t;
                }
            }
            slowFall = table;
        }
        return table[Math.max(0, Math.min(MAX_BLOCKS, blocks))];
    }
}
