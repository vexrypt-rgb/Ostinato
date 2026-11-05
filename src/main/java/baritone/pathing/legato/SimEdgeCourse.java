/*
 * Ostinato: new file (Ostinato 2.0 Phase 05 live A/B course). Not part of upstream Baritone.
 */
package baritone.pathing.legato;

import java.util.List;

/**
 * The Phase 05 (simulated edges) live A/B course, laid where {@link LegatoCourse} is and the same size, for the three
 * movements the sim prices: walking, turning, one-block jumps. Four strips split by five-high walls:
 * <ul>
 * <li>slalom: three walls across the strip, gaps at alternate ends (turns at speed);</li>
 * <li>ridges: one-block ridges across the strip, one or three wide (up a block and down again, no way round);</li>
 * <li>bumps: a scatter of one-block bumps (over or round);</li>
 * <li>steps: a three-step rise and fall across the strip (chained ascends).</li>
 * </ul>
 * Floor at y {@link LegatoCourse#F}; every goal is on the floor. Pure geometry.
 */
public final class SimEdgeCourse {
    public static final int F = LegatoCourse.F, SIZE = LegatoCourse.SIZE;

    public static final List<LegatoCourse.Leg> LEGS = List.of(
            new LegatoCourse.Leg("slalom", 3, 3, 81, 10),
            new LegatoCourse.Leg("ridges", 3, 30, 81, 33),
            new LegatoCourse.Leg("bumps", 3, 45, 81, 58),
            new LegatoCourse.Leg("steps", 3, 70, 81, 80));

    private SimEdgeCourse() {}

    /** Height of the solid column above the floor here: 0 none, 6 a wall (built five high), else a ridge, bump or step that high. */
    public static int height(int x, int z) {
        if (x <= 0 || z <= 0 || x >= SIZE || z >= SIZE || z == 21 || z == 42 || z == 63) {
            return 6; // border and strip walls
        }
        if (z < 21) { // slalom
            if (x == 20 && z <= 16 || x == 40 && z >= 5 || x == 60 && z <= 16) return 6;
            return 0;
        }
        if (z < 42) { // ridges
            return x == 15 || x >= 30 && x <= 32 || x == 45 || x >= 58 && x <= 60 || x == 72 ? 1 : 0;
        }
        if (z < 63) { // bumps
            return x >= 12 && x <= 72 && (mix(x, z) & 7) == 0 ? 1 : 0;
        }
        // steps
        if (x >= 20 && x < 30 || x >= 60 && x < 70) return 1;
        if (x >= 30 && x < 40 || x >= 50 && x < 60) return 2;
        if (x >= 40 && x < 50) return 3;
        return 0;
    }

    private static int mix(int x, int z) {
        int h = x * 73856093 ^ z * 19349663;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        return h ^ h >>> 15;
    }

    public static boolean solid(int x, int y, int z) {
        if (x < 0 || z < 0 || x > SIZE || z > SIZE) {
            return false;
        }
        if (y == F) {
            return true;
        }
        // walls are five high, so no step or ridge reaches their top
        int h = height(x, z);
        return y > F && y <= F + (h == 6 ? 5 : h);
    }
}
