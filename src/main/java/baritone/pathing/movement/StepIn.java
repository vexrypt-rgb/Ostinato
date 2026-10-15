package baritone.pathing.movement;

/**
 * Keys that push toward a point from the look the player already has. A neo often lands with its centre past the
 * side of the block; waiting to turn and face the middle spends the ticks that slide it off.
 */
public final class StepIn {

    private StepIn() {}

    /**
     * @param dx world x of the target minus the player
     * @param dz world z of the target minus the player
     * @return {@code {forward, strafe}} with forward +1 ahead and strafe +1 to the left. Both 0 when already there.
     */
    public static int[] keys(float lookYawDeg, double dx, double dz) {
        if (dx * dx + dz * dz < 1e-4) {
            return new int[]{0, 0};
        }
        double yaw = Math.toRadians(lookYawDeg);
        double sin = Math.sin(yaw), cos = Math.cos(yaw);
        int bestIn = 0, bestSt = 0;
        double best = 0;
        for (int in = -1; in <= 1; in++) {
            for (int st = -1; st <= 1; st++) {
                if (in == 0 && st == 0) {
                    continue;
                }
                double fx = st, fz = in;
                double len = Math.hypot(fx, fz);
                fx /= len;
                fz /= len;
                double ax = fx * cos - fz * sin;
                double az = fz * cos + fx * sin;
                double dot = ax * dx + az * dz;
                if (dot > best) {
                    best = dot;
                    bestIn = in;
                    bestSt = st;
                }
            }
        }
        return new int[]{bestIn, bestSt};
    }
}
