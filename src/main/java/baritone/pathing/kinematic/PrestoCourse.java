/*
 * Ostinato: new file (Presto v4.1). Not part of upstream Baritone.
 */
package baritone.pathing.kinematic;

/**
 * Ostinato Presto v4.1: the live A/B course's geometry and the jolt's safety rule, kept free of Minecraft so the
 * harness's own behaviour has unit tests ({@code PrestoCourseTest}). The course runs east along z cell 0 at floor
 * level {@link #F}; see {@link PrestoLiveTest}.
 */
public final class PrestoCourse {
    public static final int F = 200, GOAL_X = 57;
    /** How far back a jolt moves the player, as a server correction would. */
    public static final double JOLT_BACK = 1.5;
    /**
     * Safe jolts trigger once the client is past this x, on the ground. The v4 trigger (7.2) pulled the player back to
     * x 5.1-5.7, on the one-block gap's edge or over it (presto-v4-fix7..9: every jolt fall). The first v4.1 choice,
     * 9.2, is where the player is in the air over the two-block gap: a pull-back there keeps the jump's speed and
     * height but not its distance, and three of four jolt laps came down at x 9.3, on the 6-8 floor's lip, and fell
     * (presto-v4.1 run 1 laps 20-22, Presto off and on). 34.5 is on the 32-40 floor, which every traced lap crosses on
     * the ground (y 201, x 33.5-37.5): the pull-back to 33.0-35.0 leaves a straight run-up of six blocks.
     */
    public static final double SAFE_JOLT_X = 34.5, STRESS_JOLT_X = 7.2;
    /** Past the trigger by this much and no safe destination came up: the lap is run without its jolt. */
    public static final double JOLT_WINDOW = 2.0;
    /** The player's box reaches this far to each side of its middle. */
    public static final double HALF_WIDTH = 0.3;
    /** A destination's floor may lie at most this far under the feet: a jolt in mid-jump falls onto it unhurt. */
    public static final double MAX_DROP = 1.5;

    private PrestoCourse() {}

    /** Top block of the walkway at x (its feet then stand at top + 1), or MIN_VALUE over a gap. */
    public static int top(int x) {
        if (x <= 4 || x >= 6 && x <= 8 || x >= 11 && x <= 13 || x >= 17 && x <= 20 || x >= 32 && x <= 40 || x >= 44 && x <= 48 || x >= 53 && x <= 58) return F;
        if (x >= 21 && x <= 24 || x >= 28 && x <= 31) return F + 1;
        return Integer.MIN_VALUE;
    }

    /** The low ceiling over the three-block gap: one row wider than the walkway on each side. */
    public static boolean ceiling(int x, int y, int z) {
        return y == F + 4 && x >= 36 && x <= 48 && Math.abs(z) <= 1;
    }

    /** Whether the course has a solid block at (x, y, z), the pit's floor at F - 3 included. */
    public static boolean solid(int x, int y, int z) {
        return y == F - 3 || z == 0 && (y == top(x) || y < top(x) && y >= F) || ceiling(x, y, z);
    }

    /**
     * Whether a player box centred at (x, z) with its feet at y stands wholly over walkway at one level, at most
     * {@link #MAX_DROP} under the feet and not above them: both x edges of the box over the same floor, the box inside
     * the walkway's one-wide z cell.
     */
    public static boolean supported(double x, double feetY, double z) {
        if (z - HALF_WIDTH < 0 || z + HALF_WIDTH > 1) {
            return false;
        }
        int a = top((int) Math.floor(x - HALF_WIDTH)), b = top((int) Math.floor(x + HALF_WIDTH - 1e-9));
        if (a == Integer.MIN_VALUE || a != b) {
            return false;
        }
        double floor = a + 1;
        return feetY >= floor - 1e-6 && feetY - floor <= MAX_DROP;
    }

    /**
     * Where a jolt from (x, feetY, z) puts the player, or NaN when that is not safe: the player must be standing (on
     * the ground, feet on the floor's top) and the destination {@link #supported} at the same level. A jolt in flight
     * keeps the flight's speed but takes away its distance, so no airborne jolt is safe however solid the spot under it.
     */
    public static double safeJoltX(double x, double feetY, double z, boolean onGround) {
        double d = x - JOLT_BACK;
        if (!onGround || !supported(x, feetY, z) || !supported(d, feetY, z)) {
            return Double.NaN;
        }
        int floor = top((int) Math.floor(d)) + 1;
        return Math.abs(feetY - floor) < 1e-3 ? d : Double.NaN;
    }
}
