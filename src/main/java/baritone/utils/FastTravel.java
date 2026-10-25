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

// Ostinato: fastMode steering math. Original. No settings, no world: the executor asks, and only while fastMode is on.

/**
 * Corner cuts, straight-run aim and the flat shortcut clearance for {@link FastMode}. A turn of 60 degrees or less
 * is cut by aiming a fraction of a block past the corner. A wider bend, up to a right angle, is walked as a straight
 * chord only when the whole body fits. Neither answer allocates.
 */
public final class FastTravel {

    /** Shallow enough to keep sprinting through. Wider than this is a shortcut, not a sprint cut. */
    public static final double MAX_CORNER_DEG = 60;
    /** How far past the corner the aim point sits, in blocks. */
    public static final double LEAD = 0.35;
    /** Player body width. The shortcut checks the centre line and both sides. */
    public static final double BODY = 0.6;
    /** Sample step along a shortcut. A sprint tick is longer than this, so a hole cannot hide between samples. */
    public static final double SAMPLE = 0.2;
    /** Sprint cuts and shortcuts stay off at this health or below (half-hearts). */
    public static final double SPRINT_HEALTH = 6;

    private FastTravel() {}

    public static final class Steer {
        public static final Steer NONE = new Steer(false, 0, 0, 0, false);

        public final boolean aim;
        public final double x, y, z;
        public final boolean keepSprint;

        public Steer(boolean aim, double x, double y, double z, boolean keepSprint) {
            this.aim = aim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.keepSprint = keepSprint;
        }
    }

    /** Degrees between two flat steps. A zero step (a pillar) is treated as a U-turn so nothing cuts it. */
    public static double turnDegrees(int dx1, int dz1, int dx2, int dz2) {
        double a = Math.hypot(dx1, dz1);
        double b = Math.hypot(dx2, dz2);
        if (a == 0 || b == 0) {
            return 180;
        }
        double dot = (dx1 * (double) dx2 + dz1 * (double) dz2) / (a * b);
        if (dot > 1) {
            dot = 1;
        } else if (dot < -1) {
            dot = -1;
        }
        return Math.toDegrees(Math.acos(dot));
    }

    public static boolean shallow(double degrees) {
        return degrees > 0.5 && degrees <= MAX_CORNER_DEG;
    }

    /** Aim point {@link #LEAD} blocks past the corner, along the outgoing step. Y is the corner's feet block. */
    public static double[] leadPoint(int cornerX, int cornerY, int cornerZ, int outDx, int outDz) {
        double len = Math.hypot(outDx, outDz);
        double ux = outDx / len;
        double uz = outDz / len;
        return new double[] {cornerX + 0.5 + ux * LEAD, cornerY, cornerZ + 0.5 + uz * LEAD};
    }

    /**
     * Nether, lava, low health, and a block being placed or broken all refuse the cut. The caller has already
     * looked; this only combines the answers.
     */
    public static boolean sprintCutAllowed(double health, boolean nether, boolean hazard, boolean placingOrBreaking) {
        return health > SPRINT_HEALTH && !nether && !hazard && !placingOrBreaking;
    }

    /**
     * The floor a hurting fall must stay above when one or both presets are on. The higher floor wins if both are.
     * Neither on: {@link Double#NaN}, and the caller keeps the old fall budget.
     */
    public static double fallFloor(boolean experimental, double experimentalMin, boolean fast, double fastMin) {
        if (!experimental && !fast) {
            return Double.NaN;
        }
        double floor = Double.NEGATIVE_INFINITY;
        if (experimental) {
            floor = Math.max(floor, experimentalMin);
        }
        if (fast) {
            floor = Math.max(floor, fastMin);
        }
        return floor;
    }

    public interface Cells {
        boolean air(int x, int y, int z);

        boolean floor(int x, int y, int z);
    }

    /**
     * Straight chord from block {@code (x0, z0)} to {@code (x1, z1)} at foot {@code y}. Every sample of the
     * 0.6-wide body needs air at the feet and the head, and a floor under the feet.
     */
    public static boolean shortcutClear(int x0, int z0, int x1, int z1, int y, Cells cells) {
        double ax = x0 + 0.5;
        double az = z0 + 0.5;
        double bx = x1 + 0.5;
        double bz = z1 + 0.5;
        double dx = bx - ax;
        double dz = bz - az;
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) {
            return false;
        }
        double ux = dx / len;
        double uz = dz / len;
        double px = -uz;
        double pz = ux;
        double half = BODY / 2.0;
        for (double t = 0; t <= len + 1e-9; t += SAMPLE) {
            double s = Math.min(t, len);
            double cx = ax + ux * s;
            double cz = az + uz * s;
            if (!bodyClear(cx, cz, y, cells)
                    || !bodyClear(cx + px * half, cz + pz * half, y, cells)
                    || !bodyClear(cx - px * half, cz - pz * half, y, cells)) {
                return false;
            }
        }
        return true;
    }

    private static boolean bodyClear(double cx, double cz, int y, Cells cells) {
        int x = (int) Math.floor(cx);
        int z = (int) Math.floor(cz);
        return cells.air(x, y, z) && cells.air(x, y + 1, z) && cells.floor(x, y - 1, z);
    }

    /**
     * @param safe health, dimension and lava already accepted, and neither step places or breaks
     * @param straightSteps how many flat steps share the incoming direction, including the current one
     */
    public static Steer choose(int dx1, int dz1, int dx2, int dz2,
                               int cornerX, int cornerY, int cornerZ,
                               int farX, int farZ,
                               int straightSteps, int straightX, int straightZ,
                               boolean safe, boolean shortcutClear, boolean straightClear) {
        if (!safe) {
            return Steer.NONE;
        }
        double turn = turnDegrees(dx1, dz1, dx2, dz2);
        if (shallow(turn)) {
            double[] p = leadPoint(cornerX, cornerY, cornerZ, dx2, dz2);
            return new Steer(true, p[0], p[1], p[2], true);
        }
        if (turn > 0.5 && turn <= 90 && shortcutClear) {
            return new Steer(true, farX + 0.5, cornerY, farZ + 0.5, false);
        }
        if (turn <= 0.5 && straightSteps >= 3 && straightClear) {
            return new Steer(true, straightX + 0.5, cornerY, straightZ + 0.5, false);
        }
        return Steer.NONE;
    }
}
