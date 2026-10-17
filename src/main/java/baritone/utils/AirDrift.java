/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils;

/**
 * How far we can move sideways while falling. In the air a held movement key adds 0.02 blocks/tick of speed (walking,
 * LivingEntity.getFrictionInfluencedSpeed off the ground) and drag keeps 0.91 of it each tick; gravity is 0.08 with
 * 0.98 drag. A long fall gives time to drift a couple of columns, which is how a dropper's offset holes are threaded.
 * <p>
 * The planner side is conservative: every leg starts from no sideways speed and only {@link #MARGIN} of the reach is
 * used, so the steering has room to brake and line up.
 */
public final class AirDrift {

    private AirDrift() {}

    public static final double AIR_ACCEL = 0.02;
    public static final double AIR_DRAG = 0.91;
    /** Share of the ideal reach the planner counts on. */
    public static final double MARGIN = 0.75;
    /** The player is 0.6 wide, so a 1 wide hole takes the centre anywhere within 0.2 of the column's middle. */
    public static final double HOLE_SLACK = 0.2;
    /** Never more than this many columns sideways in one leg, whatever the time: the search stays small. */
    public static final int MAX_RADIUS = 3;

    private static final int N = 512;
    /** TICKS[d]: ticks from rest until the feet are d blocks lower. */
    private static final int[] TICKS = new int[N];
    /** REACH[n]: furthest sideways from rest in n ticks of holding one direction. */
    private static final double[] REACH = new double[N];

    static {
        double y = 0, v = 0;
        int t = 0;
        for (int d = 0; d < N; d++) {
            while (y < d - 1e-9) {
                v = (v - 0.08) * 0.98;
                y -= v;
                t++;
            }
            TICKS[d] = t;
        }
        double x = 0, h = 0;
        for (int n = 1; n < N; n++) {
            h = h * AIR_DRAG + AIR_ACCEL;
            x += h;
            REACH[n] = x;
        }
    }

    public static int ticksToFall(int blocks) {
        return TICKS[Math.max(0, Math.min(N - 1, blocks))];
    }

    public static double reach(int ticks) {
        return REACH[Math.max(0, Math.min(N - 1, ticks))];
    }

    /**
     * Whether the centre can get from the middle of one column into a hole dx, dz columns over while the fall goes from
     * fromDepth to toDepth blocks below where it started.
     */
    public static boolean reachable(int dx, int dz, int fromDepth, int toDepth) {
        if (Math.abs(dx) > MAX_RADIUS || Math.abs(dz) > MAX_RADIUS) {
            return false;
        }
        double need = Math.sqrt(dx * dx + dz * dz) - HOLE_SLACK;
        return need <= 0 || reach(ticksToFall(toDepth) - ticksToFall(fromDepth)) * MARGIN >= need;
    }

    /** Where we coast to sideways in k ticks with no key held: the speed decays by the drag each tick. */
    public static double coast(double v, int k) {
        return v * AIR_DRAG * (1 - Math.pow(AIR_DRAG, k)) / (1 - AIR_DRAG);
    }

    /** Ticks until the feet come down from y to floorTop, starting at vertical speed vy (negative is down). */
    public static int ticksUntil(double y, double vy, double floorTop) {
        int t = 0;
        while (y > floorTop && t < N) {
            vy = (vy - 0.08) * 0.98;
            y += vy;
            t++;
        }
        return t;
    }

    /**
     * Which movement keys to hold to end up over (tx, tz) by the time we come down past floorTop: aim at the error left
     * once our current sideways speed has coasted out, and let go when that is already close enough. Keys are relative
     * to the way we face, so no turning is needed. Returns {forward, back, left, right}.
     */
    public static boolean[] keys(double x, double z, double vx, double vz, double y, double vy, float yawDeg, double tx, double tz, double floorTop) {
        int k = Math.max(1, ticksUntil(y, vy, floorTop));
        double ex = tx - (x + coast(vx, k));
        double ez = tz - (z + coast(vz, k));
        boolean[] out = new boolean[4];
        double e = Math.hypot(ex, ez);
        if (e < 0.06) {
            return out;
        }
        // world = (strafe * cos - forward * sin, forward * cos + strafe * sin), Entity.getInputVector
        double r = Math.toRadians(yawDeg), s = Math.sin(r), c = Math.cos(r);
        double fwd = (-ex * s + ez * c) / e;
        double strafe = (ex * c + ez * s) / e;
        out[0] = fwd > 0.38;
        out[1] = fwd < -0.38;
        out[2] = strafe > 0.38; // left is +strafe
        out[3] = strafe < -0.38;
        return out;
    }
}
